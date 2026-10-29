package com.gushenrui.commitgenie.git

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewWorkflowManager
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.InclusionModel
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.vcs.commit.SingleChangeListCommitWorkflowUi
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Field

/**
 * 从 VCS 读取本次提交涉及的变更与历史提交消息。
 */
object VcsChanges {

    /**
     * 取提交面板中已勾选的变更（含未纳入版本控制的新文件）。
     *
     * 平台没有公开 API 暴露非模态提交面板的勾选状态：
     * CommitWorkflowUi 的 included/displayed 系列与 ChangesViewProxy 均为 @ApiStatus.Internal。
     * 这里按优先级尝试两条路径：
     *  1. [SingleChangeListCommitWorkflowUi.getInclusionModel]（模态提交对话框）
     *  2. 反射读取 ChangesViewCommitWorkflowHandler 的 inclusionModel（非模态提交面板）
     * 两条都失败时返回空列表，不做兜底，以便直接暴露勾选状态读取失败的问题。
     */
    fun includedChanges(project: Project, workflow: CommitWorkflowUi): List<Change> {
        val inclusion = inclusionOf(project, workflow).orEmpty()

        val changes = mutableListOf<Change>()
        val unversioned = mutableListOf<FilePath>()
        inclusion.forEach { item ->
            when (item) {
                is Change -> changes += item
                is FilePath -> unversioned += item
            }
        }
        unversioned.forEach { changes += Change(null, CurrentContentRevision(it)) }
        return changes
    }

    /** 工具栏刷新只判断是否有勾选，避免复制并分类完整变更集合。 */
    fun hasIncludedChanges(project: Project, workflow: CommitWorkflowUi): Boolean =
        inclusionOf(project, workflow)?.isNotEmpty() == true

    private fun inclusionOf(project: Project, workflow: CommitWorkflowUi): Set<*>? {
        // 这两个 accessor 在 Kotlin 侧都是普通方法而非属性，必须带括号调用
        (workflow as? SingleChangeListCommitWorkflowUi)
            ?.getInclusionModel()
            ?.getInclusion()
            ?.let { return it }
        return runCatching { readHandlerInclusion(project) }
            .onFailure { LOG.warn("读取提交面板勾选状态失败", it) }
            .getOrNull()
    }

    /**
     * 非模态提交面板的勾选集由 handler 内部的 PartialCommitInclusionModel 持有，
     * 字段为 private 且无公开 getter，只能反射读取。
     */
    private fun readHandlerInclusion(project: Project): Set<*>? {
        val manager = ChangesViewWorkflowManager.getInstance(project)
        val handler = manager.commitWorkflowHandler ?: return null
        val field = INCLUSION_FIELD[handler.javaClass] ?: return null
        val model = field.get(handler) as? InclusionModel ?: return null
        // 同理，InclusionModel.getInclusion 在 Kotlin 侧是普通方法
        return model.getInclusion()
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

    // 按实际类型缓存字段解析结果，避免工具栏每次刷新都遍历类层次并构造查找异常
    private val INCLUSION_FIELD = object : ClassValue<Field?>() {
        override fun computeValue(type: Class<*>): Field? {
            var current: Class<*>? = type
            while (current != null) {
                val field = runCatching { current.getDeclaredField("inclusionModel") }.getOrNull()
                if (field != null) return field.apply { isAccessible = true }
                current = current.superclass
            }
            return null
        }
    }

    private val LOG: Logger = Logger.getInstance(VcsChanges::class.java)
}
