package com.gushenrui.commitgenie.action

import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.vcs.commit.CommitMessageUi
import java.awt.Component
import java.awt.Container

/**
 * 提交信息输入框的加载态控制。
 *
 * 通过平台的加载 API 成对切换，由提交框恢复编辑器的可见与可编辑状态。
 */
internal object CommitMessageLoading {

    private const val LOADING_TEXT = "Generating commit message"
    private const val THINKING_TEXT = "Thinking..."

    /**
     * 记录进入加载态前的状态。
     *
     * @property oldLoadingText 退出加载态时还原的遮罩文案
     * @property oldMessage 流式写入会覆盖输入框，取消或失败时用它还原
     */
    class Snapshot(val oldLoadingText: String, val oldMessage: String)

    fun start(ui: CommitMessageUi): Snapshot {
        val oldMessage = ui.text
        val panel = loadingPanel(ui)
        val oldText = panel?.getLoadingText().orEmpty()
        panel?.setLoadingText(LOADING_TEXT)
        editorField(ui)?.isEnabled = false
        ui.startLoading()
        return Snapshot(oldText, oldMessage)
    }

    /** 收到思考事件时更新遮罩，不展示具体思考内容。 */
    fun thinking(ui: CommitMessageUi) {
        loadingPanel(ui)?.setLoadingText(THINKING_TEXT)
    }

    /** 首字到达后仅隐藏遮罩，输入框保持只读直至生成结束。 */
    fun reveal(ui: CommitMessageUi) {
        ui.stopLoading()
        editorField(ui)?.isEnabled = false
    }

    fun stop(ui: CommitMessageUi, snapshot: Snapshot) {
        ui.stopLoading()
        editorField(ui)?.isEnabled = true
        loadingPanel(ui)?.setLoadingText(snapshot.oldLoadingText)
    }

    private fun editorField(ui: CommitMessageUi): EditorTextField? =
        (ui as? CommitMessage)?.editorField

    private fun loadingPanel(ui: CommitMessageUi): JBLoadingPanel? =
        (ui as? CommitMessage)?.childComponents?.filterIsInstance<JBLoadingPanel>()?.firstOrNull()

    private val Container.childComponents: List<Component>
        get() = components?.toList().orEmpty()
}
