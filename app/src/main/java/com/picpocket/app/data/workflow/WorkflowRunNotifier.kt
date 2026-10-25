package com.picpocket.app.data.workflow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes workflow outcomes visible outside the editor: a run that failed or is
 * waiting for a network must not disappear silently.
 */
@Singleton
class WorkflowRunNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun notifyDeferred(workflowName: String, documentName: String?) =
        post(
            DEFERRED_ID,
            "Workflow waiting",
            "${describe(workflowName, documentName)} is waiting for network",
        )

    fun notifyAwaitingForeground(workflowName: String, documentName: String?) =
        post(
            DEFERRED_ID,
            "Workflow waiting",
            "Open PicPocket to finish ${describe(workflowName, documentName)}",
        )

    fun notifyFailed(workflowName: String, documentName: String?, reason: String?) =
        post(
            FAILED_ID,
            "Workflow failed",
            "${describe(workflowName, documentName)}: ${reason ?: "unknown error"}",
        )

    private fun describe(workflowName: String, documentName: String?): String =
        if (documentName.isNullOrBlank()) {
            "\"$workflowName\""
        } else {
            "\"$workflowName\" on \"$documentName\""
        }

    /** Removes the "waiting" notification once nothing is left in the queue. */
    fun clearDeferred() {
        runCatching { NotificationManagerCompat.from(context).cancel(DEFERRED_ID) }
    }

    private fun post(id: Int, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Workflows", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "workflow"
        const val DEFERRED_ID = 1002
        const val FAILED_ID = 1003
    }
}
