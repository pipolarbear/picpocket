package com.picpocket.app.domain.workflow.queue

import android.content.Context
import com.picpocket.app.data.repository.DeferredRun
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.data.workflow.WorkflowActivity
import com.picpocket.app.data.workflow.WorkflowActivityBus
import com.picpocket.app.data.workflow.WorkflowRunNotifier
import com.picpocket.app.debug.Category
import com.picpocket.app.debug.Tracing
import com.picpocket.app.domain.workflow.action.ActionRegistry
import com.picpocket.app.domain.workflow.action.Execution
import com.picpocket.app.domain.workflow.engine.ForegroundExecutor
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.NodeStatus
import com.picpocket.app.domain.workflow.model.RunResult
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.domain.workflow.model.saveTargetName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replays workflow runs that were deferred because the device was offline.
 * Headless runs execute in the background as soon as a connection exists. A
 * run containing a foreground action (share) cannot open from a background
 * worker, so it waits for the app to be opened — the user is notified once.
 */
@Singleton
class DeferredRunProcessor @Inject constructor(
    private val workflowRepository: WorkflowRepository,
    private val documentRepository: DocumentRepository,
    private val engine: WorkflowEngine,
    private val registry: ActionRegistry,
    private val foreground: ForegroundExecutor,
    private val activityBus: WorkflowActivityBus,
    private val notifier: WorkflowRunNotifier,
    @ApplicationContext private val context: Context,
) {

    /** Replays every queued run and reports what the worker should do next. */
    suspend fun process(): DrainOutcome {
        val pending = workflowRepository.listDeferred()
        if (pending.isEmpty()) {
            notifier.clearDeferred()
            return DrainOutcome.DONE
        }
        var networkRetry = false
        var waitingForForeground = false
        for (run in pending) {
            when (replay(run)) {
                Outcome.CLEARED -> workflowRepository.removeDeferred(run.id)
                Outcome.RETRY -> networkRetry = true
                Outcome.WAIT_FOR_FOREGROUND -> waitingForForeground = true
            }
        }
        val outcome = when {
            networkRetry -> DrainOutcome.RETRY
            waitingForForeground -> DrainOutcome.WAITING_FOR_FOREGROUND
            else -> DrainOutcome.DONE
        }
        if (outcome == DrainOutcome.DONE) notifier.clearDeferred()
        return outcome
    }

    private suspend fun replay(run: DeferredRun): Outcome {
        val workflow = workflowRepository.get(run.workflowId)
        if (workflow == null) {
            Tracing.w(Category.WORKFLOW, TAG, "dropping queued run ${run.id}: workflow ${run.workflowId} is gone")
            return Outcome.CLEARED
        }

        val document = documentRepository.getDocument(run.documentId).getOrNull()
        if (document == null) {
            Tracing.w(
                Category.WORKFLOW,
                TAG,
                "queued run ${run.id}: document ${run.documentName ?: run.documentId} is gone",
            )
            workflowRepository.recordRun(
                workflow,
                run.trigger,
                run.documentId,
                run.documentName,
                run.createdAt,
                RunResult(RunStatus.FAILURE, emptyList()),
            )
            return Outcome.CLEARED
        }

        if (hasForegroundAction(workflow) && !foreground.isForeground) {
            // A cold start can schedule the worker a moment before the activity
            // is counted as foreground; give it one short beat before parking.
            delay(FOREGROUND_GRACE_MS)
            if (!foreground.isForeground) {
                notifier.notifyAwaitingForeground(workflow.name, document.name)
                return Outcome.WAIT_FOR_FOREGROUND
            }
        }

        val startedAt = System.currentTimeMillis()
        val result = engine.run(
            workflow = workflow,
            event = run.trigger,
            document = document,
            ignoreTriggerCheck = true,
            checkConditions = false,
            enqueueWhenDeferred = false,
        )
        // The connection dropped again (or the run was re-deferred): keep the
        // row so the next network callback retries it.
        if (result.status == RunStatus.DEFERRED) return Outcome.RETRY

        workflowRepository.recordRun(
            workflow,
            run.trigger,
            document.id,
            document.name,
            startedAt,
            result,
        )
        publish(workflow, document.id, document.name, result)
        return Outcome.CLEARED
    }

    private fun publish(
        workflow: Workflow,
        documentId: String,
        documentName: String?,
        result: RunResult,
    ) {
        if (result.nodes.isEmpty() && result.status == RunStatus.SUCCESS) return
        val failureReason = result.nodes.firstOrNull { it.status == NodeStatus.FAILED }?.reason
        Tracing.i(
            Category.WORKFLOW,
            TAG,
            "replayed ${workflow.name} on $documentName -> ${result.status}",
        )
        activityBus.emit(
            WorkflowActivity(
                documentId = documentId,
                documentName = documentName,
                workflowName = workflow.name,
                status = result.status,
                saveTarget = workflow.saveTargetName(),
                failureReason = failureReason,
            ),
        )
        when (result.status) {
            RunStatus.FAILURE, RunStatus.PARTIAL_FAILURE ->
                notifier.notifyFailed(workflow.name, documentName, failureReason)
            RunStatus.DEFERRED, RunStatus.SUCCESS -> Unit
        }
    }

    private fun hasForegroundAction(workflow: Workflow): Boolean =
        workflow.roots.any { subtreeHasForeground(it) }

    private fun subtreeHasForeground(node: ActionNode): Boolean =
        registry.action(node.type).execution == Execution.FOREGROUND ||
            node.children.any { subtreeHasForeground(it) }

    private enum class Outcome { CLEARED, RETRY, WAIT_FOR_FOREGROUND }

    private companion object {
        const val TAG = "DeferredRunProcessor"
        const val FOREGROUND_GRACE_MS = 750L
    }
}

/** What the queue worker should do after a drain. */
enum class DrainOutcome {
    /** Nothing is left in the queue. */
    DONE,

    /** The network dropped or is still unavailable; keep the work queued. */
    RETRY,

    /** Runs are waiting for the app to be opened; no background retry is useful. */
    WAITING_FOR_FOREGROUND,
}
