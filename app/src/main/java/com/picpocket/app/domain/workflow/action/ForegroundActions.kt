package com.picpocket.app.domain.workflow.action

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.Artifact
import java.io.File
import javax.inject.Inject

/** Opens a messaging app's share flow (foreground-only). */
class SendToAppAction @Inject constructor() : WorkflowAction {
    override val type = ActionType.SEND_TO_APP
    override val execution = Execution.FOREGROUND

    override suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult {
        val app = params as? ActionParams.SendToApp
            ?: return ActionResult.Failure("send-to-app: app not configured")
        val source = ctx.ensurePdf(input)
        return ctx.foreground.run {
            val path = Uri.parse(source.uri).path
                ?: return@run ActionResult.Failure("send-to-app: unsupported artifact uri")
            val fileUri = FileProvider.getUriForFile(
                ctx.app,
                "${ctx.app.packageName}.fileprovider",
                File(path),
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = source.mime
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(app.packageName)
            }
            if (send.resolveActivity(ctx.app.packageManager) != null) {
                ctx.app.startActivity(send)
            } else {
                val chooser = Intent.createChooser(send, "Share to ${app.displayName}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.app.startActivity(chooser)
            }
            ActionResult.Success(source)
        }
    }
}

/** Posts a local notification and passes the artifact through. */
class NotifyAction @Inject constructor() : WorkflowAction {
    override val type = ActionType.NOTIFY
    override val execution = Execution.HEADLESS

    override suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult {
        val message = (params as? ActionParams.Notify)?.message ?: "Workflow finished"
        postNotification(ctx, message)
        return ActionResult.Success(ctx.ensurePdf(input))
    }

    private fun postNotification(ctx: WorkflowContext, message: String) {
        val manager = ctx.app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Workflows", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = NotificationCompat.Builder(ctx.app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PicPocket workflow")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx.app).notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "workflow"
        const val NOTIFICATION_ID = 1001
    }
}
