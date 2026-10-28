package com.gushenrui.commitgenie.prompt

import com.gushenrui.commitgenie.settings.CommitGenieSettings

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
        val styleSection = renderSamples(sampleMessages)

        if (custom.isNotEmpty()) {
            return custom
                .replace(MACRO_SAMPLES, styleSection)
                .replace(MACRO_DIFF, diff)
        }

        val languageClause = languageInstruction(settings.language)
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
        }
    }

    private fun renderSamples(messages: List<String>): String {
        if (messages.isEmpty()) return ""
        return messages.mapIndexed { index, message ->
            "==== Example commit message ${index + 1} ====\n$message"
        }.joinToString(SAMPLE_SEPARATOR) + "\n==== End of examples ===="
    }

    private fun languageInstruction(language: String): String = when {
        language.isBlank() || language.equals(CommitGenieSettings.LANGUAGE_AUTO, ignoreCase = true) ->
            "Write the commit message in the same language as the sample commit messages."
        else -> "Write the commit message in $language."
    }

    const val MACRO_SAMPLES = "\$SAMPLE_COMMIT_MESSAGES"
    const val MACRO_DIFF = "\$COMMIT_DIFF"
}
