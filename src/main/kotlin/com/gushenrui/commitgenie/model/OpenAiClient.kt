package com.gushenrui.commitgenie.model

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 调用 OpenAI 兼容的 `/chat/completions` 接口。
 *
 * 使用流式响应，逐块回调已累积的文本，便于界面实时展示生成进度。
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
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
        val sink = StreamingCollector()
        val body = requestBody(prompt)

        try {
            // post 的第二个参数是 Content-Type，请求体要在 connect 回调里写入
            HttpRequests.post(endpoint(), "application/json")
                .tuner { connection ->
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    connection.setRequestProperty("Accept", "text/event-stream")
                }
                .connectTimeout(timeoutMs)
                .readTimeout(timeoutMs)
                .throwStatusCodeException(true)
                .connect<String> { request ->
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

    private fun endpoint(): String = baseUrl.trimEnd('/') + "/chat/completions"

    private fun requestBody(prompt: String): String =
        """{"model":${quote(model)},"stream":true,"temperature":1,""" +
                """"messages":[{"role":"user","content":${quote(prompt)}}]}"""

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

    /**
     * 解析 SSE 数据行并累积正文。
     *
     * 兼容两种常见返回：标准 `data:` 事件流与部分网关直接返回的整段 JSON。
     */
    private class StreamingCollector {
        private val buffer = StringBuilder()

        val text: String get() = buffer.toString()

        /** 返回本次是否产生了新的正文内容。 */
        fun consume(rawLine: String): Boolean {
            val line = rawLine.trim()
            if (line.isEmpty()) return false
            return when {
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trim()
                    payload != DONE && appendDelta(payload)
                }
                line.startsWith("{") -> appendDelta(line)
                else -> false
            }
        }

        private fun appendDelta(json: String): Boolean {
            val content = runCatching { extractContent(json) }.getOrNull()
            if (content.isNullOrEmpty()) return false
            buffer.append(content)
            return true
        }

        private fun extractContent(json: String): String? {
            val root = JsonParser.parseString(json).asJsonObject
            val choices = root.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return null
            val first = choices[0].asJsonObject
            return first.getAsJsonObject("delta")?.contentOrNull()
                ?: first.getAsJsonObject("message")?.contentOrNull()
        }

        private fun com.google.gson.JsonObject.contentOrNull(): String? =
            get("content")?.takeIf { it.isJsonPrimitive }?.asString
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 120_000
        private const val DONE = "[DONE]"

        /** 响应文案里暗示「内容过大」的关键词，用于把 400 细化成更明确的提示。 */
        private val OVERSIZE_HINTS = listOf(
            "too large", "too long", "maximum context", "context length",
            "max_tokens", "exceeds", "payload", "request entity",
        )
        private val STATUS_PATTERN = Regex("""response code:?\s*(\d{3})""", RegexOption.IGNORE_CASE)

        private val LOG = Logger.getInstance(OpenAiClient::class.java)
    }
}
