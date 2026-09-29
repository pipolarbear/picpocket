package com.picpocket.app.domain.workflow.action

import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.Artifact

/** Whether an action needs the app in the foreground (it starts an Activity). */
enum class Execution { HEADLESS, FOREGROUND }

/** A single step in a workflow. Stateless; the node's params are passed in. */
interface WorkflowAction {
    val type: ActionType
    val execution: Execution
    suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult
}
