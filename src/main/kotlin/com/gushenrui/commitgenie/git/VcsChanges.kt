package com.gushenrui.commitgenie.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从 VCS 读取本次提交涉及的变更与历史提交消息。
 */
object VcsChanges {

    /**
     * 取当前变更列表中的全部变更（含未纳入版本控制的新文件）。
     *
     * CommitWorkflowUi 的 included/displayed 系列均标记为 @ApiStatus.Internal，
     * 独立插件无法使用，这里改用公开的 ChangeListManager。
     * 提交面板默认全选，因此语义上基本一致。
     */
    fun includedChanges(project: Project): List<Change> {
        val manager = ChangeListManager.getInstance(project)
        val unversioned = manager.unversionedFilesPaths.map { path ->
            Change(null, CurrentContentRevision(path))
        }
        return manager.allChanges + unversioned
    }

    /**
     * 采样最近的提交信息，用于让模型模仿仓库既有的书写风格。
     *
     * 逐个仓库读取，任一仓库失败只跳过该仓库，不影响其余采样结果。
     */
    suspend fun sampleCommitMessages(project: Project, limit: Int): List<String> {
        if (limit <= 0) return emptyList()

        return withContext(Dispatchers.IO) {
            val roots = runCatching {
                GitRepositoryManager.getInstance(project).repositories.map { it.root }
            }.getOrDefault(emptyList())

            val messages = mutableListOf<String>()
            for (root in roots) {
                if (messages.size >= limit) break
                val remaining = limit - messages.size
                val metadata = runCatching {
                    GitHistoryUtils.collectCommitsMetadata(project, root, "--max-count=$remaining")
                }.getOrNull() ?: continue
                metadata.forEach { messages += sanitize(it.fullMessage) }
            }
            messages
        }
    }

    /** 去掉 Change-Id、Signed-off-by 这类与提交语义无关的尾注。 */
    private fun sanitize(message: String): String = message.replace(TRAILER_LINE, "").trimEnd()

    private val TRAILER_LINE = Regex("\n(Change-Id: \\w+\\s*|Signed-off-by: .*)$", RegexOption.MULTILINE)
}
