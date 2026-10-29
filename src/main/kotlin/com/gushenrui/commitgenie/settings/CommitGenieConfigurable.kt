package com.gushenrui.commitgenie.settings

import com.gushenrui.commitgenie.prompt.PromptBuilder
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import javax.swing.JComponent

/**
 * CommitGenie 设置页，只负责界面与配置对象之间的搬运。
 */
class CommitGenieConfigurable : Configurable {

    private var ui: DialogPanel? = null
    private var apiKeyField: Cell<JBPasswordField>? = null
    private var promptField: Cell<JBTextArea>? = null

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
            // 外层行撑满剩余空间，内部分组才能继续分配宽高
            row {
                panel {
                    group("高级") {
                        row {
                            // 标签放在上方，文本域才能占满整行宽度
                            textArea()
                                .label("自定义提示词:", LabelPosition.TOP)
                                .bindText({ customPrompt }, { customPrompt = it })
                                .rows(TEXT_AREA_ROWS)
                                .align(Align.FILL)
                                .resizableColumn()
                                .applyToComponent {
                                    emptyText.text =
                                        "留空使用内置提示词，可点击下方「填入示例」参考写法"
                                }
                                .also { promptField = it }
                        }.resizableRow()
                        row {
                            link("填入示例") { fillExamplePrompt() }
                        }
                        // 备注独占一行，默认按可用宽度自动换行
                        row {
                            comment(
                                "${PromptBuilder.MACRO_SAMPLES}：替换为采样的历史提交；" +
                                        "${PromptBuilder.MACRO_DIFF}：替换为本次勾选变更的 diff。" +
                                        "自定义提示词生效时，「输出语言」设置不再自动追加，请在提示词中自行说明。"
                            ).resizableColumn()
                        }
                    }
                }.align(Align.FILL)
            }.resizableRow()
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
        promptField = null
    }

    /** 已有内容时先确认，避免误覆盖用户写好的提示词。 */
    private fun fillExamplePrompt() {
        val area = promptField?.component ?: return
        if (area.text.isNotBlank()) {
            val answer = Messages.showYesNoDialog(
                area,
                "当前自定义提示词将被示例覆盖，是否继续？",
                "填入示例",
                null,
            )
            if (answer != Messages.YES) return
        }
        area.text = EXAMPLE_PROMPT
        area.caretPosition = 0
    }

    private fun currentApiKey(): String =
        apiKeyField?.component?.password?.concatToString().orEmpty()

    private companion object {
        val SAMPLE_COUNT_RANGE = 0..50
        val MAX_DIFF_RANGE = 1_000..500_000

        // 保持默认列宽，宽度和高度由可伸缩布局随窗口分配
        const val TEXT_AREA_ROWS = 12

        val EXAMPLE_PROMPT = """
            |你是一名资深软件工程师，请根据下面的 diff 生成一条简洁的 Git 提交信息。
            |要求：
            |1. 使用中文，遵循 Conventional Commits 格式：type(scope): subject
            |2. 标题不超过 50 个字符；必要时空一行，再用正文说明改动原因
            |3. 只描述 diff 中真实存在的改动，不要编造工单号、人名或邮箱
            |4. 只输出提交信息本身，不要附加任何解释
            |
            |参考以下历史提交的风格：
            |${PromptBuilder.MACRO_SAMPLES}
            |
            |本次改动的 diff：
            |${PromptBuilder.MACRO_DIFF}
        """.trimMargin()
    }
}
