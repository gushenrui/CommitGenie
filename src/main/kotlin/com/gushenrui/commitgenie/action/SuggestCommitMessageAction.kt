package com.gushenrui.commitgenie.action

import com.gushenrui.commitgenie.git.DiffBuilder
import com.gushenrui.commitgenie.git.VcsChanges
import com.gushenrui.commitgenie.model.OpenAiClient
import com.gushenrui.commitgenie.prompt.PromptBuilder
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
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.commit.CommitWorkflowHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
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

        val hasChanges = VcsChanges.includedChanges(project, workflow).isNotEmpty()
        val isAmend = handler.isAmendMode
        val configured = instance.isConfigured

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
            if (running.revealed) {
                messageUi.text = running.snapshot.oldMessage
            }
            CommitMessageLoading.stop(messageUi, running.snapshot)
            return
        }

        if (!instance.isConfigured) {
            Notifications.warn(project, "请先在 $SETTINGS_PATH 中配置 Base URL、Model 与 API Key")
            return
        }

        // 勾选状态属于 Swing 组件，必须在 EDT 上读取，再把结果交给后台任务
        val changes = VcsChanges.includedChanges(project, workflow)
        if (changes.isEmpty()) {
            Notifications.warn(project, "没有可生成的变更")
            return
        }

        val snapshot = CommitMessageLoading.start(messageUi)
        // 先占位再启动：任务可能瞬间完成，晚写入会把状态覆盖回“运行中”
        val running = RunningJob(snapshot)
        project.putUserData(KEY, running)

        running.job = CommitGenieCoroutineService.getInstance(project).scope.launch {
            // StateFlow 只保留最新值：模型出字比 EDT 刷新快时，中间状态会被合并而不是堆积
            val partial = MutableStateFlow("")
            val renderer = launch(Dispatchers.EDT) {
                partial.collect { text ->
                    if (text.isEmpty() || project.getUserData(KEY) !== running) return@collect
                    // 收到首段内容就撤掉遮罩，之后直接在输入框里实时展示
                    if (!running.revealed) {
                        running.revealed = true
                        CommitMessageLoading.stop(messageUi, snapshot)
                    }
                    messageUi.text = text
                }
            }

            var result: String? = null
            try {
                result = generate(project, changes) { partial.value = normalize(it).trimStart() }
            } finally {
                renderer.cancel()
                // finally 中不能直接用 withContext：协程已被取消时会立即抛出，界面会卡在加载态
                val text = result
                withContext(Dispatchers.EDT + NonCancellable) {
                    // 已被用户取消或被新任务取代时，界面不再归本任务管
                    if (project.getUserData(KEY) !== running) return@withContext
                    project.putUserData(KEY, null)
                    when {
                        !text.isNullOrBlank() -> messageUi.text = text
                        // 生成失败时不留半截内容，还原用户原来的输入
                        running.revealed -> messageUi.text = snapshot.oldMessage
                    }
                    CommitMessageLoading.stop(messageUi, snapshot)
                }
            }
        }
    }

    private suspend fun generate(
        project: Project,
        changes: List<Change>,
        onDelta: (String) -> Unit,
    ): String? {
        val settings = instance
        val diff = DiffBuilder.buildDiff(project, changes, settings.state.maxDiffChars)
        if (diff.isBlank()) {
            Notifications.warn(project, "无法为所选变更生成 diff")
            return null
        }

        val samples = if (settings.state.includeHistory) {
            VcsChanges.sampleCommitMessages(project, settings.state.sampleCommitCount)
        } else {
            emptyList()
        }

        val prompt = PromptBuilder.build(samples, diff, settings.state)
        val client = OpenAiClient(
            baseUrl = settings.state.baseUrl,
            apiKey = settings.apiKey,
            model = settings.state.model,
        )

        return try {
            normalize(client.generate(prompt, indicator = null, onDelta)).trim()
        } catch (e: CancellationException) {
            // 取消信号必须继续向上传播，否则 finally 里的收尾逻辑会误判为正常结束
            throw e
        } catch (e: OpenAiClient.PayloadTooLargeException) {
            LOG.warn("变更过大，模型无法处理", e)
            Notifications.warn(
                project,
                "变更过大，无法由模型总结。请缩小勾选范围或调小 Diff 最大字符数。"
            )
            null
        } catch (e: java.net.SocketTimeoutException) {
            LOG.warn("模型响应超时", e)
            Notifications.warn(project, "模型未在超时时间内响应，请稍后重试。")
            null
        } catch (e: Exception) {
            LOG.warn("生成提交信息失败", e)
            Notifications.error(project, "生成失败：${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun normalize(text: String): String = text.replace("\r\n", "\n")

    /** 占位对象，job 在启动后回填，保证「运行中」状态没有竞态窗口。 */
    private class RunningJob(val snapshot: CommitMessageLoading.Snapshot) {
        @Volatile
        var job: Job? = null

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
