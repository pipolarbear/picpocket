package com.picpocket.app.domain.workflow

import com.picpocket.app.domain.workflow.action.ActionRegistry
import com.picpocket.app.domain.workflow.action.Execution
import com.picpocket.app.domain.workflow.action.WorkflowAction
import com.picpocket.app.domain.workflow.engine.ForegroundExecutor
import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.Artifact

/** Records invocations and returns a configured result. */
class RecordingAction(
    override val type: ActionType,
    override val execution: Execution = Execution.HEADLESS,
    private val result: ActionResult = ActionResult.Success(Artifact("file://x", "application/pdf")),
) : WorkflowAction {
    val inputs = mutableListOf<Artifact?>()
    var runs = 0
    override suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult {
        runs++
        inputs.add(input)
        return result
    }
}

class FakeActionRegistry(private val actions: Map<ActionType, WorkflowAction>) : ActionRegistry {
    override fun action(type: ActionType): WorkflowAction = actions.getValue(type)
    override val all: List<WorkflowAction> get() = actions.values.toList()
}

class FakeForegroundExecutor : ForegroundExecutor {
    override val isForeground = true
    override suspend fun run(block: suspend () -> ActionResult): ActionResult = block()
}
