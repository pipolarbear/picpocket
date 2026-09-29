package com.picpocket.app.domain.workflow.action

import com.picpocket.app.domain.workflow.model.ActionType
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves an action type to its implementation; drives the editor and engine. */
interface ActionRegistry {
    fun action(type: ActionType): WorkflowAction
    val all: List<WorkflowAction>
}

@Singleton
class DefaultActionRegistry @Inject constructor(
    encrypt: EncryptAction,
    zip: ZipAction,
    saveToFolder: SaveToFolderAction,
    sendToApp: SendToAppAction,
    notify: NotifyAction,
    delete: DeleteAction,
) : ActionRegistry {

    private val actions: Map<ActionType, WorkflowAction> =
        listOf(encrypt, zip, saveToFolder, sendToApp, notify, delete).associateBy { it.type }

    override fun action(type: ActionType): WorkflowAction =
        actions[type] ?: error("no action registered for $type")

    override val all: List<WorkflowAction> get() = actions.values.toList()
}
