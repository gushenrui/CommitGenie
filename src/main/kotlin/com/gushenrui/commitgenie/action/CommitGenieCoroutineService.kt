package com.gushenrui.commitgenie.action

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 承载生成任务的协程作用域，随项目关闭一起释放，避免任务泄漏。
 */
@Service(Service.Level.PROJECT)
class CommitGenieCoroutineService : Disposable {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): CommitGenieCoroutineService = project.service()
    }
}
