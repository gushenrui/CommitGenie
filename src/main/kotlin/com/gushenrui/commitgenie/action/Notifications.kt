package com.gushenrui.commitgenie.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

internal object Notifications {

    private const val GROUP_ID = "CommitGenie"

    fun warn(project: Project, content: String) = notify(project, content, NotificationType.WARNING)

    fun error(project: Project, content: String) = notify(project, content, NotificationType.ERROR)

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            ?.createNotification("CommitGenie", content, type)
            ?.notify(project)
    }
}
