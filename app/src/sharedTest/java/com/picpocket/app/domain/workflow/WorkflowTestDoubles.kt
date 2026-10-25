package com.picpocket.app.domain.workflow

import com.picpocket.app.domain.workflow.action.ActionRegistry
import com.picpocket.app.domain.workflow.action.Execution
import com.picpocket.app.domain.workflow.action.WorkflowAction
import com.picpocket.app.domain.workflow.engine.ForegroundExecutor
import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.network.NetworkMonitor
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.Artifact

/** Records invocations and returns a configured result. */
class RecordingAction(
    override val type: ActionType,
    override val execution: Execution = Execution.HEADLESS,
    private val result: ActionResult = ActionResult.Success(Artifact("file://x", "application/pdf")),
    private val needsNetwork: Boolean = false,
) : WorkflowAction {
    val inputs = mutableListOf<Artifact?>()
    var runs = 0
    override suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult {
        runs++
        inputs.add(input)
        return result
    }

    override fun requiresNetwork(ctx: WorkflowContext, params: ActionParams): Boolean = needsNetwork
}

class FakeActionRegistry(private val actions: Map<ActionType, WorkflowAction>) : ActionRegistry {
    override fun action(type: ActionType): WorkflowAction = actions.getValue(type)
    override val all: List<WorkflowAction> get() = actions.values.toList()
}

class FakeForegroundExecutor(var foreground: Boolean = true) : ForegroundExecutor {
    override val isForeground: Boolean get() = foreground
    override suspend fun run(block: suspend () -> ActionResult): ActionResult = block()
}

/** Test network state; flip [online] to simulate losing/regaining connectivity. */
class FakeNetworkMonitor(var online: Boolean = true) : NetworkMonitor {
    override fun isOnline(): Boolean = online
}

class FakeWorkflowQueueScheduler : com.picpocket.app.domain.workflow.queue.WorkflowQueueScheduler {
    var schedules = 0
    override fun schedule() { schedules++ }
    override fun requestDrainNow() { schedules++ }
}
