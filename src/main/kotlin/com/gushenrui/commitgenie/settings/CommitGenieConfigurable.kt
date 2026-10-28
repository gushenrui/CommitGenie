package com.gushenrui.commitgenie.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.gushenrui.commitgenie.prompt.PromptBuilder
import javax.swing.JComponent

/**
 * CommitGenie 设置页，只负责界面与配置对象之间的搬运。
 */
class CommitGenieConfigurable : Configurable {

    private var ui: DialogPanel? = null
    private var apiKeyField: Cell<JBPasswordField>? = null

    private var baseUrl = ""
    private var model = ""
    private var sampleCommitCount = 0
    private var maxDiffChars = 0
    private var includeHistory = true
    private var language = ""
    private var customPrompt = ""

    override fun getDisplayName(): String = "CommitGenie"

    override fun createComponent(): JComponent {
        val target = panel {
            group("模型接口") {
                row("Base URL:") {
                    textField()
                        .bindText({ baseUrl }, { baseUrl = it })
                        .resizableColumn()
                        .comment("需包含版本路径，例如 https://api.openai.com/v1")
                }
                row("Model:") {
                    textField()
                        .bindText({ model }, { model = it })
                        .resizableColumn()
                        .comment("例如 gpt-4o-mini、deepseek-chat")
                }
                row("API Key:") {
                    // 密码框不保留明文 text，只能直接读写 password 字段
                    passwordField()
                        .also { apiKeyField = it }
                        .resizableColumn()
                        .comment("保存在系统钥匙串中，不会写入项目文件")
                }
            }
            group("生成选项") {
                row("输出语言:") {
                    textField()
                        .bindText({ language }, { language = it })
                        .comment("填写 ${CommitGenieSettings.LANGUAGE_AUTO} 表示跟随历史提交的语言")
                }
                row("历史提交采样条数:") {
                    spinner(SAMPLE_COUNT_RANGE, 1)
                        .bindIntValue({ sampleCommitCount }, { sampleCommitCount = it })
                        .comment("参考仓库既有提交风格，填 0 表示不参考")
                }
                row("Diff 最大字符数:") {
                    spinner(MAX_DIFF_RANGE, 1000)
                        .bindIntValue({ maxDiffChars }, { maxDiffChars = it })
                        .comment("超出部分会被截断，避免请求体过大")
                }
                row {
                    checkBox("采样仓库历史提交作为风格参考")
                        .bindSelected({ includeHistory }, { includeHistory = it })
                }
            }
            group("高级") {
                row("自定义提示词:") {
                    textArea()
                        .bindText({ customPrompt }, { customPrompt = it })
                        .rows(TEXT_AREA_ROWS)
                        .resizableColumn()
                        .comment(
                            "留空使用内置提示词。可用 ${PromptBuilder.MACRO_SAMPLES} 与 " +
                                "${PromptBuilder.MACRO_DIFF} 作为占位符"
                        )
                }
            }
        }
        ui = target
        reset()
        return target
    }

    override fun isModified(): Boolean {
        val panel = ui ?: return false
        return panel.isModified() || currentApiKey() != CommitGenieSettings.instance.apiKey
    }

    override fun apply() {
        ui?.apply()
        val settings = CommitGenieSettings.instance
        settings.state.baseUrl = baseUrl.trim().ifEmpty { CommitGenieSettings.DEFAULT_BASE_URL }
        settings.state.model = model.trim().ifEmpty { CommitGenieSettings.DEFAULT_MODEL }
        settings.state.sampleCommitCount = sampleCommitCount
        settings.state.maxDiffChars = maxDiffChars
        settings.state.includeHistory = includeHistory
        settings.state.language = language.trim().ifEmpty { CommitGenieSettings.LANGUAGE_AUTO }
        settings.state.customPrompt = customPrompt
        settings.apiKey = currentApiKey()
    }

    override fun reset() {
        val settings = CommitGenieSettings.instance
        val state = settings.state
        baseUrl = state.baseUrl
        model = state.model
        sampleCommitCount = state.sampleCommitCount
        maxDiffChars = state.maxDiffChars
        includeHistory = state.includeHistory
        language = state.language
        customPrompt = state.customPrompt
        apiKeyField?.component?.text = settings.apiKey
        ui?.reset()
    }

    override fun disposeUIResources() {
        ui = null
        apiKeyField = null
    }

    private fun currentApiKey(): String =
        apiKeyField?.component?.password?.concatToString().orEmpty()

    private companion object {
        val SAMPLE_COUNT_RANGE = 0..50
        val MAX_DIFF_RANGE = 1_000..500_000
        const val TEXT_AREA_ROWS = 7
    }
}
