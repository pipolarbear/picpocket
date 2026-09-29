package com.picpocket.app.domain.workflow.model

/** A messaging app the `send-to-app` action can target. */
data class WorkflowApp(val displayName: String, val packageName: String)

object WorkflowApps {
    val all: List<WorkflowApp> = listOf(
        WorkflowApp("WhatsApp", "com.whatsapp"),
        WorkflowApp("Telegram", "org.telegram.messenger"),
        WorkflowApp("Viber", "com.viber.voip"),
        WorkflowApp("Gmail", "com.google.android.gm"),
    )
}
