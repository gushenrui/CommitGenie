package com.gushenrui.commitgenie.prompt

import com.gushenrui.commitgenie.settings.CommitGenieSettings
import java.io.IOException

/**
 * 组装发送给模型的提示词。
 *
 * 内置模板与 Android Studio Gemini 插件保持一致的语义：
 * 约束模型聚焦具体改动、不编造个人信息与工单号，并参考仓库历史提交的行文风格。
 */
object PromptBuilder {

    private const val SAMPLE_SEPARATOR = "\n"

    fun build(sampleMessages: List<String>, diff: String, settings: CommitGenieSettings.State): String {
        val custom = settings.customPrompt.trim()
        if (custom.length > MAX_TEMPLATE_CHARS) {
            throw IOException("自定义提示词超过 $MAX_TEMPLATE_CHARS 字符，请缩短模板")
        }
        val styleSection = renderSamples(sampleMessages)

        if (custom.isNotEmpty()) {
            // 先计算展开后的长度，避免重复宏先分配巨大的字符串再报错。
            val expandedLength =
                MACRO_PATTERN.findAll(custom).fold(custom.length.toLong()) { size, match ->
                    size - match.value.length + if (match.value == MACRO_SAMPLES) styleSection.length else diff.length
                }
            if (expandedLength > MAX_PROMPT_CHARS) throw budgetExceeded()
            // 只扫描原模板一次，插入的历史样本和 diff 不再参与宏展开。
            return MACRO_PATTERN.replace(custom) { match ->
                if (match.value == MACRO_SAMPLES) styleSection else diff
            }
        }

        if (diff.length > MAX_PROMPT_CHARS) throw budgetExceeded()
        val languageClause = languageInstruction(settings.language, sampleMessages.isNotEmpty())
        return buildString {
            appendLine("You are an experienced software engineer and efficient technical communicator.")
            appendLine("Summarize the given diffs into a concise commit message.")
            appendLine("Focus on specific changes.")
            appendLine("Do NOT output names, e-mail addresses, or any other personally identifiable information if they are not explicitly in the diffs.")
            appendLine("Do NOT output bug IDs or any other unique identifiers if they are not explicitly in the diffs.")
            if (languageClause.isNotEmpty()) {
                appendLine(languageClause)
            }
            appendLine()
            if (styleSection.isNotEmpty()) {
                appendLine("Here are few sample commit messages to follow their style:")
                appendLine(styleSection)
            }
            appendLine("The diffs for the commit message are as follows:")
            appendLine(diff)
            append("==== End of diff ====")
        }.also(::checkBudget)
    }

    internal const val MAX_SAMPLE_CHARS = 2_000
    internal const val MAX_SAMPLES_CHARS = 12_000
    internal const val MAX_TEMPLATE_CHARS = 32_000
    internal const val MAX_PROMPT_CHARS = 550_000
    private const val TRUNCATED_SAMPLE = "… [sample truncated]"

    fun checkBudget(prompt: String) {
        if (prompt.length > MAX_PROMPT_CHARS) throw budgetExceeded()
    }

    private fun budgetExceeded() = IOException(
        "完整提示词超过 $MAX_PROMPT_CHARS 字符，请减少历史采样、缩短模板或调小 Diff 最大字符数"
    )

    private fun renderSamples(messages: List<String>): String {
        if (messages.isEmpty()) return ""
        val out = StringBuilder()
        val end = "\n==== End of examples ===="
        for ((index, message) in messages.withIndex()) {
            val header = (if (index == 0) "" else SAMPLE_SEPARATOR) +
                    "==== Example commit message ${index + 1} ====\n"
            val available = MAX_SAMPLES_CHARS - out.length - header.length - end.length
            if (available < TRUNCATED_SAMPLE.length) break
            out.append(header).append(truncateSample(message, minOf(MAX_SAMPLE_CHARS, available)))
        }
        return if (out.isEmpty()) "" else out.append(end).toString()
    }

    private fun truncateSample(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var end = limit - TRUNCATED_SAMPLE.length
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + TRUNCATED_SAMPLE
    }

    private fun languageInstruction(language: String, hasSamples: Boolean): String = when {
        language.isBlank() || language.equals(CommitGenieSettings.LANGUAGE_AUTO, ignoreCase = true) ->
            // 没有历史样本时无法「跟随示例语言」，交给模型按 diff 自行判断
            if (hasSamples) {
                "Write the commit message in the same language as the sample commit messages."
            } else {
                "Write the commit message in the most appropriate language for the diffs."
            }
        else -> "Write the commit message in $language."
    }

    const val MACRO_SAMPLES = "\$SAMPLE_COMMIT_MESSAGES"
    const val MACRO_DIFF = "\$COMMIT_DIFF"

    private val MACRO_PATTERN = Regex(
        "${Regex.escape(MACRO_SAMPLES)}|${Regex.escape(MACRO_DIFF)}"
    )
}
