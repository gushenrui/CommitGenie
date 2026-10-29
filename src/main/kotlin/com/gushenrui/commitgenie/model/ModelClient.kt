package com.gushenrui.commitgenie.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * 调用模型网关的聊天接口。
 *
 * 使用流式响应，逐块回调已累积的文本，便于界面实时展示生成进度。
 */
class ModelClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val protocol: ModelProtocol,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    /**
     * @param onDelta 每收到一段增量文本时回调，参数是到目前为止的完整文本
     * @return 模型输出的完整文本
     */
    suspend fun generate(
        prompt: String,
        onDelta: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        // connect 的回调不是挂起函数，先取出上下文供循环内检查取消
        val context = currentCoroutineContext()
        val sink = StreamingCollector(protocol)
        val body = requestBody(prompt)
        // readLine 阻塞时感知不到协程取消。子协程在取消时断开连接，让阻塞的读取立即抛出
        val activeConnection = AtomicReference<HttpURLConnection?>()
        val disconnectOnCancel = launch {
            try {
                awaitCancellation()
            } finally {
                // 取消时子协程的 finally 立即执行，disconnect 无需挂起，可直接打断阻塞的 readLine
                activeConnection.getAndSet(null)?.disconnect()
            }
        }

        try {
            // post 的第二个参数是 Content-Type，请求体要在 connect 回调里写入
            HttpRequests.post(endpoint(), "application/json")
                .tuner { connection ->
                    val cleanKey = apiKey.trim()
                    // 同时携带 Bearer 与 x-api-key，兼容官方 Anthropic 与各类 OpenAI 中转网关
                    connection.setRequestProperty("Authorization", "Bearer $cleanKey")
                    if (protocol == ModelProtocol.ANTHROPIC) {
                        connection.setRequestProperty("x-api-key", cleanKey)
                        connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                    }
                    connection.setRequestProperty("Accept", "text/event-stream")
                    // Java 默认请求 gzip；部分网关会缓冲压缩后的 SSE，直到完成才一次性返回
                    connection.setRequestProperty("Accept-Encoding", "identity")
                }
                .connectTimeout(timeoutMs)
                .readTimeout(timeoutMs)
                .throwStatusCodeException(true)
                .connect<String> { request ->
                    val connection = request.connection as? HttpURLConnection
                    activeConnection.set(connection)
                    // 登记连接前可能已被取消，此时子协程已收尾，需要在这里自行退出
                    context.ensureActive()
                    request.write(body)
                    // 非 2xx（含 3xx 重定向、4xx/5xx 错误）都在回调内先读错误流再抛出，
                    // 因为平台的状态码检查发生在回调正常返回之后，异常退出时拿不到响应体
                    val status = connection?.responseCode ?: -1
                    if (status !in 200..299) {
                        throw HttpErrorException(status, readErrorBody(connection))
                    }
                    request.reader.use { reader ->
                        while (true) {
                            context.ensureActive()
                            val line = reader.readLine() ?: break
                            if (sink.consume(line)) {
                                onDelta(sink.text)
                            }
                        }
                    }
                    if (sink.finish()) {
                        onDelta(sink.text)
                    }
                    sink.text
                }
        } catch (e: IOException) {
            // 取消时断开连接也会表现为 IOException，此时应按取消处理而不是报错
            context.ensureActive()
            throw classifyFailure(
                error = e,
                promptLength = prompt.length,
            )
        } finally {
            // 正常结束时先解除登记，避免收尾时误断连接
            activeConnection.set(null)
            disconnectOnCancel.cancel()
        }
    }

    /**
     * 把请求失败归类成带可读提示的异常。
     *
     * 状态码与响应体都来自回调内主动抛出的 [HttpErrorException]；
     * 此时平台已关闭连接，异常分支里再读错误流只会得到空串，因此不做无谓尝试。
     */
    private fun classifyFailure(
        error: IOException,
        promptLength: Int,
    ): IOException {
        val status = when (error) {
            is HttpErrorException -> error.status
            is HttpRequests.HttpStatusException -> error.statusCode
            // 超时后探测状态码可能又阻塞一个读超时，直接按未知处理
            is SocketTimeoutException -> -1
            else -> extractStatusCode(error.message)
        }
        // 只有回调内主动抛出的 HttpErrorException 带响应体；
        // 平台 HttpStatusException 的 message 是本地化文案而非 body，不作为 detail 使用
        val detail = when (error) {
            is HttpErrorException -> errorDetail(error.detail)
            else -> ""
        }

        // 已识别为 HTTP 状态失败时只记摘要，其余异常保留堆栈以便定位
        if (error is HttpErrorException || error is HttpRequests.HttpStatusException) {
            LOG.warn(
                "请求失败 status=$status endpoint=${endpoint()} " +
                        "model=${model.trim()} promptLen=$promptLength detail=$detail"
            )
        } else {
            LOG.warn(
                "请求异常 type=${error.javaClass.name} endpoint=${endpoint()} " +
                        "model=${model.trim()} promptLen=$promptLength msg=${error.message}",
                error,
            )
        }

        if (isPayloadTooLarge(status, detail)) {
            return PayloadTooLargeException(
                if (detail.isEmpty()) "请求内容超出模型上限" else "请求内容超出模型上限：$detail"
            )
        }

        // 3xx 重定向通常意味着 Base URL 配错，单独给出可操作的提示
        if (status in 300..399) {
            val suffix = if (detail.isEmpty()) "" else ": $detail"
            return IOException("接口返回重定向 $status，请检查 Base URL 是否正确$suffix", error)
        }

        // 已被识别的状态异常换成干净文案，避免把平台本地化前缀或 URL 一起抛给用户
        if (error is HttpErrorException || error is HttpRequests.HttpStatusException) {
            return IOException(
                if (status > 0) "模型接口返回 $status${if (detail.isEmpty()) "" else ": $detail"}"
                else detail.ifEmpty { "模型接口请求失败" },
                error,
            )
        }
        return error
    }

    /**
     * 只有 413 与 400（或流内报错）可能代表内容过大。
     *
     * 401/403 等鉴权失败不参与判断，因此不会因为文案里恰好出现关键词而被误判；
     * 400 也必须命中下面这些明确指向「输入长度/体积超限」的措辞才算。
     */
    private fun isPayloadTooLarge(status: Int, detail: String): Boolean {
        if (status == 413) return true
        if (status != 400 && status != -1) return false
        val lower = detail.lowercase()
        return OVERSIZE_HINTS.any { lower.contains(it) }
    }

    private fun extractStatusCode(message: String?): Int =
        message?.let { STATUS_PATTERN.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: -1

    /**
     * 读取错误响应体。
     *
     * 在回调内、连接仍然可用时调用；读不到内容不算错误，返回空串交由调用方兜底。
     * 错误流绕过了平台的解压逻辑，这里自行按 Content-Encoding 解 gzip；
     * 网关的错误页可能是大段 HTML，读取长度也限制在上限内。
     */
    private fun readErrorBody(connection: HttpURLConnection?): String {
        if (connection == null) return ""
        return runCatching {
            // 已声明 identity，但仍可能遇到不遵守的网关，按 Content-Encoding 决定是否解压
            val gzip = connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
            val raw = connection.errorStream
            val stream = when {
                raw == null -> null
                gzip -> GZIPInputStream(raw)
                else -> raw
            }
            stream?.use { readable ->
                val reader = readable.reader(connection.contentTypeCharset())
                val buffer = CharArray(ERROR_READ_CHUNK)
                val out = StringBuilder()
                while (out.length < MAX_ERROR_BODY) {
                    val read =
                        reader.read(buffer, 0, minOf(buffer.size, MAX_ERROR_BODY - out.length))
                    if (read <= 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        }.getOrNull().orEmpty()
    }

    /** 错误流没有 charset 约定，优先用 Content-Type 里声明的编码。 */
    private fun HttpURLConnection.contentTypeCharset(): java.nio.charset.Charset {
        val declared = contentType
            ?.let { CHARSET_PATTERN.find(it)?.groupValues?.getOrNull(1) }
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }
            ?: return Charsets.UTF_8
        return runCatching { java.nio.charset.Charset.forName(declared) }.getOrDefault(Charsets.UTF_8)
    }

    /**
     * 把错误响应体整理成可写日志、可展示给用户的单行摘要。
     *
     * 优先取 JSON 的 `error.message`，避免把字段名、请求回显等噪声也算进关键词匹配；
     * 解析不出时退回原始文本。最后统一折叠空白并截断，防止超长 body 或 HTML 污染日志。
     */
    private fun errorDetail(message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val text = extractErrorMessage(raw)?.trim().takeUnless { it.isNullOrEmpty() } ?: raw
        val collapsed = text.replace(WHITESPACE, " ")
        if (collapsed.length <= MAX_ERROR_DETAIL) return collapsed
        // 截断点落在代理对中间时退一格，避免产生孤立代理
        val end = if (Character.isHighSurrogate(collapsed[MAX_ERROR_DETAIL - 1])) {
            MAX_ERROR_DETAIL - 1
        } else {
            MAX_ERROR_DETAIL
        }
        return collapsed.take(end) + "…"
    }

    /** 兼容 OpenAI / Anthropic 的 `{"error":{"message":...}}` 与部分网关的 `{"message":...}`（自动跳过前置 BOM 或日志前缀）。 */
    private fun extractErrorMessage(raw: String): String? {
        val jsonStart = raw.indexOf('{')
        if (jsonStart < 0) return null
        val cleanJson = if (jsonStart == 0) raw else raw.substring(jsonStart)
        return runCatching {
            val root = JsonParser.parseString(cleanJson).asJsonObject
            root.getAsJsonObject("error")?.get("message")?.stringOrNull()
                ?: root.get("message")?.stringOrNull()
        }.getOrNull()
    }

    /** 请求体超出模型上下文或服务端限制。 */
    class PayloadTooLargeException(message: String) : IOException(message)

    /** 携带状态码与服务端响应体，供上层统一归类。 */
    private class HttpErrorException(val status: Int, val detail: String) :
        IOException("HTTP $status")

    private fun endpoint(): String = when (protocol) {
        ModelProtocol.OPENAI -> baseUrl.trim().trimEnd('/') + "/chat/completions"
        ModelProtocol.ANTHROPIC -> baseUrl.trim().trimEnd('/') + "/messages"
    }

    private fun requestBody(prompt: String): String {
        val root = JsonObject().apply {
            addProperty("model", model.trim())
            addProperty("stream", true)
            if (protocol == ModelProtocol.ANTHROPIC) {
                addProperty("max_tokens", MAX_OUTPUT_TOKENS)
            }
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            })
        }
        return GSON.toJson(root)
    }

    /**
     * 解析所选协议的响应行并累积正文。
     *
     * 同时支持：
     * 1. 标准 SSE 流（`data: {...}`）
     * 2. 流内报错事件（HTTP 200 下通过 JSON 返回 `error` 对象）
     * 3. 网关忽略 `stream: true` 直接返回的单行或多行格式化 JSON
     */
    private class StreamingCollector(private val protocol: ModelProtocol) {
        private val buffer = StringBuilder()
        private val rawJsonFallback = StringBuilder()
        private var seenSseData = false

        val text: String get() = buffer.toString()

        /** 返回本次是否产生了新的正文内容。 */
        fun consume(rawLine: String): Boolean {
            val line = rawLine.trim()
            if (line.isEmpty()) return false
            if (line.startsWith("data:")) {
                seenSseData = true
                val payload = line.removePrefix("data:").trim()
                if (payload == DONE) return false
                return appendDelta(payload)
            }
            if (!seenSseData) {
                // 单行整段 JSON 直接解析；若为多行格式化 JSON 则先暂存，留到 finish() 一次性解析
                if (line.startsWith("{") && line.endsWith("}")) {
                    if (appendDelta(line)) return true
                }
                // 超限后不再累积：非流式整段 JSON 一旦解析失败，多攒的内容也不会被用到
                if (rawJsonFallback.length < MAX_FALLBACK_CHARS) {
                    rawJsonFallback.append(line).append('\n')
                }
            }
            return false
        }

        /** 流结束时兜底解析可能存在的多行整段 JSON，返回是否追加了新内容。 */
        fun finish(): Boolean {
            if (seenSseData || buffer.isNotEmpty() || rawJsonFallback.isEmpty()) return false
            val fullJson = rawJsonFallback.toString().trim()
            if (!fullJson.startsWith("{")) return false
            return appendDelta(fullJson)
        }

        private fun appendDelta(json: String): Boolean {
            val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
                ?: return false

            // 识别 HTTP 200 流式传输中途或非流式 JSON 顶层返回的错误事件，交由外层 classifyFailure 统一归类
            checkStreamError(root)?.let { errorRaw ->
                throw HttpErrorException(-1, errorRaw)
            }

            val content = when (protocol) {
                ModelProtocol.OPENAI -> extractOpenAiContent(root)
                ModelProtocol.ANTHROPIC -> extractAnthropicContent(root)
            }
            if (content.isNullOrEmpty()) return false
            buffer.append(content)
            return true
        }

        private fun checkStreamError(root: JsonObject): String? {
            val errorElement = root.get("error")
            if (errorElement != null && !errorElement.isJsonNull) {
                return root.toString()
            }
            if (root.get("type")?.stringOrNull() == "error") {
                return root.toString()
            }
            return null
        }

        private fun extractOpenAiContent(root: JsonObject): String? {
            val choices = root.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return null
            val first = choices[0].asJsonObject
            return first.getAsJsonObject("delta")?.contentOrNull()
                ?: first.getAsJsonObject("message")?.contentOrNull()
        }

        private fun extractAnthropicContent(root: JsonObject): String? {
            val delta = root.getAsJsonObject("delta")
            if (delta != null) {
                if (delta.get("type")?.stringOrNull() != "text_delta") {
                    return null
                }
                return delta.textOrNull()
            }
            return root.getAsJsonArray("content")
                ?.firstOrNull()
                ?.takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
                ?.textOrNull()
        }

        private fun JsonObject.contentOrNull(): String? =
            get("content")?.stringOrNull()

        private fun JsonObject.textOrNull(): String? =
            get("text")?.stringOrNull()
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 120_000
        private const val DONE = "[DONE]"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_OUTPUT_TOKENS = 4_096

        /** 错误摘要的字符上限：够看清原因，又不会把整段 HTML 塞进日志。 */
        private const val MAX_ERROR_DETAIL = 500

        /** 错误响应体的读取上限；摘要只需要开头部分。 */
        private const val MAX_ERROR_BODY = 8_192
        private const val ERROR_READ_CHUNK = 2_048

        /** 非流式整段 JSON 的暂存上限，避免网关持续输出时无限增长。 */
        private const val MAX_FALLBACK_CHARS = 1_048_576

        /** 响应文案里明确暗示「输入内容过大/超长」的关键词，避免普通参数错误被误判。 */
        private val OVERSIZE_HINTS = listOf(
            "too large", "too long", "maximum context", "context length",
            "context_length_exceeded", "request entity", "token limit", "exceeds the maximum",
        )

        // 平台文案形如 "Request failed with status code 400"，部分网关则写 "response code: 400"
        private val STATUS_PATTERN =
            Regex("""(?:response|status) code:?\s*(\d{3})""", RegexOption.IGNORE_CASE)
        private val WHITESPACE = Regex("""\s+""")
        private val CHARSET_PATTERN = Regex("""charset\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)

        // diff 里常见 <、>、&，关闭 HTML 转义以免无谓膨胀请求体
        private val GSON: Gson = GsonBuilder().disableHtmlEscaping().create()

        private val LOG = Logger.getInstance(ModelClient::class.java)

        private fun JsonElement.stringOrNull(): String? =
            takeIf(JsonElement::isJsonPrimitive)?.asString
    }
}
