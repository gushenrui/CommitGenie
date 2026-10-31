package com.gushenrui.commitgenie.action

import com.gushenrui.commitgenie.git.VcsChanges
import com.gushenrui.commitgenie.settings.CommitGenieSettings.Companion.instance
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.vcs.commit.CommitMessageUi
import com.intellij.vcs.commit.CommitWorkflowHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 依据提交面板中勾选的变更生成提交信息。
 *
 * 生成过程中再次点击即取消；结果写回提交消息输入框。
 */
class SuggestCommitMessageAction : AnAction() {

    // 状态文案、图标与提交面板数据都在 EDT 上读取，避免跨线程访问 Swing
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val presentation = event.presentation
        val project = event.project
        val workflow = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val handler = event.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)

        if (project == null || workflow == null || handler == null) {
            presentation.isEnabledAndVisible = false
            return
        }

        if (project.getUserData(KEY) != null) {
            presentation.icon = com.intellij.icons.AllIcons.Actions.Cancel
            presentation.setText(STOP_TEXT)
            presentation.description = "点击取消本次提交信息生成"
            presentation.isEnabled = true
            disableRichTooltip(presentation)
            return
        }

        val hasChanges = VcsChanges.hasIncludedChanges(project, workflow)
        val isAmend = handler.isAmendMode
        // 密钥在后台加载：加载期间按钮保持可用，由任务内部等待结果后再判断
        val configured = instance.isApiKeyPending || instance.isConfigured

        presentation.icon = CommitGenieIcons.SUGGEST_COMMIT_MESSAGE
        presentation.setText("")
        presentation.description = when {
            isAmend -> "Amend 模式下不支持生成提交信息"
            !hasChanges -> "请先勾选至少一个变更的文件"
            !configured -> "请先在 $SETTINGS_PATH 中配置模型与 API Key"
            else -> "根据当前选中的变更生成提交信息"
        }
        presentation.isEnabled = !isAmend && hasChanges && configured
        enableRichTooltip(presentation, this)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workflow = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val messageUi = workflow.commitMessageUi

        // 已有任务在跑，说明用户此刻要的是取消。
        // 界面立即还原：任务可能正阻塞在网络读取上，等它的 finally 会让加载态残留很久
        project.getUserData(KEY)?.let { running ->
            project.putUserData(KEY, null)
            running.job?.cancel()
            // 密钥尚未加载完就被取消时还没有进入加载态，无需还原
            running.snapshot?.let { snapshot ->
                if (running.revealed) {
                    messageUi.text = snapshot.oldMessage
                }
                CommitMessageLoading.stop(messageUi, snapshot)
            }
            return
        }

        if (!instance.isConfigured && !instance.isApiKeyPending) {
            Notifications.warn(project, "请先在 $SETTINGS_PATH 中配置 Base URL、Model 与 API Key")
            return
        }

        // 勾选状态属于 Swing 组件，必须在 EDT 上读取，再把结果交给后台任务
        val changes = VcsChanges.includedChanges(project, workflow)
        if (changes.isEmpty()) {
            Notifications.warn(project, "没有可生成的变更")
            return
        }

        // 先占位再启动：任务可能瞬间完成，晚写入会把状态覆盖回“运行中”
        val running = RunningJob()
        project.putUserData(KEY, running)
        val generator = CommitMessageGenerator.getInstance(project)

        running.job = generator.scope.launch {
            // 密钥可能仍在后台读取：先等它就绪再进入加载态，避免遮罩闪一下就消失
            if (!generator.awaitConfigured()) {
                withContext(Dispatchers.EDT) {
                    if (project.getUserData(KEY) !== running) return@withContext
                    project.putUserData(KEY, null)
                    notifyFailure(project, CommitMessageGenerator.Result.NotConfigured)
                }
                return@launch
            }

            val snapshot = withContext(Dispatchers.EDT) {
                // 排队期间可能已被取消；此时不得再进入加载态
                if (project.getUserData(KEY) !== running) return@withContext null
                CommitMessageLoading.start(messageUi).also { running.snapshot = it }
            } ?: return@launch

            // 无界通道不阻塞网络读取；渲染结束后正常排空，避免最后一段内容被丢弃
            val partial = Channel<String>(Channel.UNLIMITED)
            val thinking = Channel<Unit>(Channel.UNLIMITED)
            val renderer = launch(Dispatchers.EDT) {
                for (text in partial) {
                    if (project.isDisposed || project.getUserData(KEY) !== running) break
                    // 首字到达后隐藏遮罩，输入框保持只读直至生成结束
                    if (!running.revealed) {
                        CommitMessageLoading.reveal(messageUi)
                        running.revealed = true
                    }
                    messageUi.text = text
                }
            }
            val thinkingRenderer = launch(Dispatchers.EDT) {
                for (event in thinking) {
                    if (project.isDisposed || project.getUserData(KEY) !== running) break
                    if (!running.revealed) CommitMessageLoading.thinking(messageUi)
                }
            }

            // 取消会以 CancellationException 抛出，此时保持 null，界面由取消分支负责还原
            var result: CommitMessageGenerator.Result? = null
            try {
                result = generator.generate(
                    project,
                    changes,
                    onDelta = { partial.trySend(it) },
                    onThinking = { thinking.trySend(Unit) },
                )
            } finally {
                partial.close()
                thinking.close()
                withContext(NonCancellable) {
                    renderer.join()
                    thinkingRenderer.join()
                    finish(project, running, messageUi, snapshot, result)
                }
            }
        }
    }

    /** 收尾：只有当前任务仍持有占位时才更新界面。 */
    private suspend fun finish(
        project: Project,
        running: RunningJob,
        messageUi: CommitMessageUi,
        snapshot: CommitMessageLoading.Snapshot,
        result: CommitMessageGenerator.Result?,
    ) = withContext(Dispatchers.EDT) {
        // 项目关闭时输入框可能已被释放，不能再触碰组件
        if (project.isDisposed) return@withContext
        // 已被用户取消或被新任务取代时，界面不再归本任务管
        if (project.getUserData(KEY) !== running) return@withContext
        project.putUserData(KEY, null)
        when (result) {
            is CommitMessageGenerator.Result.Success -> messageUi.text = result.message
            // 生成失败时不留半截内容，还原用户原来的输入
            else -> if (running.revealed) messageUi.text = snapshot.oldMessage
        }
        CommitMessageLoading.stop(messageUi, snapshot)
        result?.let { notifyFailure(project, it) }
    }

    /** 把服务返回的失败原因转换成用户可见的提示；成功但 diff 被截断时也提示。 */
    private fun notifyFailure(project: Project, result: CommitMessageGenerator.Result) {
        when (result) {
            CommitMessageGenerator.Result.NotConfigured ->
                Notifications.warn(
                    project,
                    "请先在 $SETTINGS_PATH 中配置 Base URL、Model 与 API Key"
                )

            CommitMessageGenerator.Result.EmptyDiff ->
                Notifications.warn(project, "无法为所选变更生成 diff")

            is CommitMessageGenerator.Result.PayloadTooLarge -> {
                LOG.warn("变更过大，模型无法处理", result.cause)
                Notifications.warn(
                    project,
                    "变更过大，无法由模型总结。请缩小勾选范围或调小 Diff 最大字符数。"
                )
            }

            is CommitMessageGenerator.Result.Timeout -> {
                LOG.warn("模型响应超时", result.cause)
                Notifications.warn(project, "模型未在超时时间内响应，请稍后重试。")
            }

            is CommitMessageGenerator.Result.Failed -> {
                LOG.warn("生成提交信息失败", result.cause)
                Notifications.error(
                    project,
                    "生成失败：${result.cause.message ?: result.cause.javaClass.simpleName}"
                )
            }

            is CommitMessageGenerator.Result.Success -> if (result.diffTruncated) {
                Notifications.warn(
                    project,
                    "部分变更因 Diff 字符上限被省略，提交信息仅依据保留的完整变更块生成。请检查遗漏内容，或调整勾选范围和字符上限后重试。"
                )
            }
        }
    }

    /** 占位对象，job 在启动后回填，保证「运行中」状态没有竞态窗口。 */
    private class RunningJob {
        @Volatile
        var job: Job? = null

        /** 加载态快照；进入加载态后才创建，取消时可能仍为空。 */
        @Volatile
        var snapshot: CommitMessageLoading.Snapshot? = null

        /** 是否已开始流式写入输入框；只在 EDT 上读写。 */
        var revealed = false
    }

    /**
     * 给按钮挂富文本提示（标题 + 说明 + 快捷键），与 Gemini 原版一致。
     *
     * 普通 tooltip 只会显示 description，禁用时甚至可能不显示；
     * [ActionButton.CUSTOM_HELP_TOOLTIP] 能保证禁用态也给出原因。
     */
    private fun enableRichTooltip(presentation: Presentation, action: AnAction) {
        val tooltip = HelpTooltip()
        // 按钮只显示图标、text 为空，原版此时不设标题，这里保持一致
        presentation.text?.takeIf { it.isNotEmpty() }
            ?.let { tooltip.setTitle(HtmlChunk.text(it)) }
        presentation.description?.let { tooltip.setDescription(HtmlChunk.text(it)) }
        val shortcut = KeymapUtil.getFirstKeyboardShortcutText(action.shortcutSet)
        if (shortcut.isNotEmpty()) {
            tooltip.setShortcut(shortcut)
        }
        presentation.putClientProperty(ActionButton.CUSTOM_HELP_TOOLTIP, tooltip)
    }

    private fun disableRichTooltip(presentation: Presentation) {
        presentation.putClientProperty(ActionButton.CUSTOM_HELP_TOOLTIP, null)
    }

    private companion object {
        const val STOP_TEXT = "Stop Generating Commit Message"
        const val SETTINGS_PATH = "Settings → Tools → CommitGenie"
        val KEY = Key<RunningJob>("commitgenie.suggestCommitMessage.job")
        val LOG: Logger = Logger.getInstance(SuggestCommitMessageAction::class.java)

        val CommitWorkflowHandler.isAmendMode: Boolean
            // isAmendCommitModeSupported 是普通方法，isAmendCommitMode 是 Kotlin 属性
            get() = amendCommitHandler.let { it.isAmendCommitModeSupported() && it.isAmendCommitMode }
    }
}
