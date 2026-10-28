package com.gushenrui.commitgenie.action

import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLayeredPane
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.vcs.commit.CommitMessageUi
import java.awt.Component
import java.awt.Container

/**
 * 提交信息输入框的加载态控制。
 *
 * 平台没有公开「生成中」的 API，只能复用提交框内部的 [JBLoadingPanel]。
 * 结构一旦变化，这些调用会自动降级为无效果，不影响生成结果写回。
 */
internal object CommitMessageLoading {

    private const val LOADING_TEXT = "Generating commit message"

    /** 记录进入加载态前的文案，退出时还原。 */
    class Snapshot(val oldLoadingText: String)

    fun start(ui: CommitMessageUi): Snapshot {
        editorField(ui)?.isVisible = false
        ui.startLoading()
        val panel = loadingPanel(ui)
        val oldText = panel?.getLoadingText().orEmpty()
        panel?.setLoadingText(LOADING_TEXT)
        return Snapshot(oldText)
    }

    fun stop(ui: CommitMessageUi, snapshot: Snapshot) {
        editorField(ui)?.isVisible = true
        ui.stopLoading()
        // stopLoading 之后平台仍保留遮罩层，需要手动隐藏
        loadingPanel(ui)?.let { panel ->
            panel.hideLoadingLayer()
            panel.setLoadingText(snapshot.oldLoadingText)
        }
    }

    private fun editorField(ui: CommitMessageUi): EditorTextField? =
        (ui as? CommitMessage)?.editorField

    private fun loadingPanel(ui: CommitMessageUi): JBLoadingPanel? =
        (ui as? Container)?.childComponents?.filterIsInstance<JBLoadingPanel>()?.firstOrNull()

    private fun JBLoadingPanel.hideLoadingLayer() {
        val layeredPane = childComponents.filterIsInstance<JBLayeredPane>().firstOrNull() ?: return
        layeredPane.getComponentsInLayer(JBLayeredPane.DRAG_LAYER).forEach { it.isVisible = false }
    }

    private val Container.childComponents: List<Component>
        get() = components?.toList().orEmpty()
}
