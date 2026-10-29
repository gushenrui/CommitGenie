package com.gushenrui.commitgenie.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference

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
        indicator: ProgressIndicator?,
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
                activeConnection.getAndSet(null)?.disconnect()
            }
        }

        try {
            // post 的第二个参数是 Content-Type，请求体要在 connect 回调里写入
            HttpRequests.post(endpoint(), "application/json")
                .tuner { connection ->
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    if (protocol == ModelProtocol.ANTHROPIC) {
                        connection.setRequestProperty("x-api-key", apiKey)
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
                    activeConnection.set(request.connection as? HttpURLConnection)
                    // 登记连接前可能已被取消，此时子协程已收尾，需要在这里自行退出
                    context.ensureActive()
                    request.write(body)
                    request.reader.use { reader ->
                        while (true) {
                            context.ensureActive()
                            if (indicator?.isCanceled == true) {
                                throw CancellationException("用户取消生成")
                            }
                            val line = reader.readLine() ?: break
                            if (sink.consume(line)) {
                                onDelta(sink.text)
                            }
                        }
                    }
                    sink.text
                }
        } catch (e: HttpRequests.HttpStatusException) {
            LOG.warn(
                "请求失败 status=${e.statusCode} endpoint=${endpoint()} " +
                        "model=${model.trim()} bodyLen=${prompt.length} " +
                        "detail=${e.message.orEmpty().take(500)}",
                e,
            )
            val detail = e.message.orEmpty()
            if (isPayloadTooLarge(e.statusCode, detail)) {
                throw PayloadTooLargeException("请求内容超出模型上限：$detail", e)
            }
            throw IOException("模型接口返回 ${e.statusCode}: ${detail.take(500)}", e)
        } catch (e: IOException) {
            // 取消时断开连接也会表现为 IOException，此时应按取消处理而不是报错
            context.ensureActive()
            // 部分网关不触发 HttpStatusException，错误会以普通 IOException 抛出
            LOG.warn(
                "请求异常 type=${e.javaClass.name} endpoint=${endpoint()} " +
                        "model=${model.trim()} bodyLen=${prompt.length} msg=${e.message}",
                e,
            )
            if (isPayloadTooLarge(extractStatusCode(e.message), e.message.orEmpty())) {
                throw PayloadTooLargeException("请求内容超出模型上限：${e.message}", e)
            }
            throw e
        } finally {
            // 正常结束时先解除登记，避免收尾时误断连接
            activeConnection.set(null)
            disconnectOnCancel.cancel()
        }
    }

    /** 413 明确表示体过大；400 需要结合响应文案判断。 */
    private fun isPayloadTooLarge(status: Int, detail: String): Boolean {
        if (status == 413) return true
        if (status != 400) return false
        val lower = detail.lowercase()
        return OVERSIZE_HINTS.any { lower.contains(it) }
    }

    private fun extractStatusCode(message: String?): Int =
        message?.let { STATUS_PATTERN.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: -1

    /** 请求体超出模型上下文或服务端限制。 */
    class PayloadTooLargeException(message: String, cause: Throwable) : IOException(message, cause)

    private fun endpoint(): String = when (protocol) {
        ModelProtocol.OPENAI -> baseUrl.trimEnd('/') + "/chat/completions"
        ModelProtocol.ANTHROPIC -> baseUrl.trimEnd('/') + "/messages"
    }

    private fun requestBody(prompt: String): String = when (protocol) {
        ModelProtocol.OPENAI ->
            """{"model":${quote(model)},"stream":true,"temperature":1,""" +
                    """"messages":[{"role":"user","content":${quote(prompt)}}]}"""

        ModelProtocol.ANTHROPIC ->
            """{"model":${quote(model)},"stream":true,"max_tokens":$MAX_OUTPUT_TOKENS,""" +
                    """"messages":[{"role":"user","content":${quote(prompt)}}]}"""
    }

    /** 最小化的 JSON 转义，避免为大请求引入额外对象图。 */
    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch == '\b' -> append("\\b")
                ch == '\u000C' -> append("\\f")
                // 完整的代理对原样输出；孤立代理项替换为替代字符，
                // 否则编码不出合法 UTF-8，服务端会因 JSON 解析失败返回 400
                Character.isHighSurrogate(ch) &&
                        i + 1 < value.length && Character.isLowSurrogate(value[i + 1]) -> {
                    append(ch).append(value[i + 1])
                    i++
                }

                Character.isSurrogate(ch) -> append('�')
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
            i++
        }
        append('"')
    }

    /** 解析所选协议的响应行并累积正文，同时兼容部分网关直接返回的整段 JSON。 */
    private class StreamingCollector(private val protocol: ModelProtocol) {
        private val buffer = StringBuilder()

        val text: String get() = buffer.toString()

        /** 返回本次是否产生了新的正文内容。 */
        fun consume(rawLine: String): Boolean {
            val line = rawLine.trim()
            if (line.isEmpty()) return false
            val payload = when {
                line.startsWith("data:") -> line.removePrefix("data:").trim()
                line.startsWith("{") -> line
                else -> return false
            }
            if (payload == DONE) return false
            return appendDelta(payload)
        }

        private fun appendDelta(json: String): Boolean {
            val content = runCatching { extractContent(json) }.getOrNull()
            if (content.isNullOrEmpty()) return false
            buffer.append(content)
            return true
        }

        private fun extractContent(json: String): String? {
            val root = JsonParser.parseString(json).asJsonObject
            return when (protocol) {
                ModelProtocol.OPENAI -> extractOpenAiContent(root)
                ModelProtocol.ANTHROPIC -> extractAnthropicContent(root)
            }
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
                if (delta.get("type")
                        ?.takeIf(JsonElement::isJsonPrimitive)?.asString != "text_delta"
                ) {
                    return null
                }
                return delta.textOrNull()
            }
            return root.getAsJsonArray("content")
                ?.firstOrNull()
                ?.asJsonObject
                ?.textOrNull()
        }

        private fun JsonObject.contentOrNull(): String? =
            get("content")?.takeIf(JsonElement::isJsonPrimitive)?.asString

        private fun JsonObject.textOrNull(): String? =
            get("text")?.takeIf(JsonElement::isJsonPrimitive)?.asString
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 120_000
        private const val DONE = "[DONE]"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_OUTPUT_TOKENS = 1_024

        /** 响应文案里暗示「内容过大」的关键词，用于把 400 细化成更明确的提示。 */
        private val OVERSIZE_HINTS = listOf(
            "too large", "too long", "maximum context", "context length",
            "max_tokens", "exceeds", "payload", "request entity",
        )
        private val STATUS_PATTERN = Regex("""response code:?\s*(\d{3})""", RegexOption.IGNORE_CASE)

        private val LOG = Logger.getInstance(ModelClient::class.java)
    }
}
