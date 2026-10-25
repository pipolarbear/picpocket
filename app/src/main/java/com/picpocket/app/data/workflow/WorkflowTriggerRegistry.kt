package com.picpocket.app.data.workflow

import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.debug.Category
import com.picpocket.app.debug.Tracing
import com.picpocket.app.di.ApplicationScope
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
import com.picpocket.app.domain.workflow.model.NodeStatus
import com.picpocket.app.domain.workflow.model.RunResult
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.domain.workflow.model.saveTargetName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes to [DocumentEventBus], matches enabled workflows by trigger, runs
 * them, and records the outcome. Events produced during a run are ignored
 * (re-entrancy guard) so a workflow cannot trigger itself.
 */
@Singleton
class WorkflowTriggerRegistry @Inject constructor(
    private val bus: DocumentEventBus,
    private val documentRepository: DocumentRepository,
    private val workflowRepository: WorkflowRepository,
    private val engine: WorkflowEngine,
    private val activityBus: WorkflowActivityBus,
    private val notifier: WorkflowRunNotifier,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val inRun = AtomicInteger(0)

    /** Begins listening for document events (idempotent per process). */
    fun start() {
        scope.launch { bus.events.collect { handle(it) } }
    }

    /** Evaluates and runs every enabled workflow whose triggers match [event]. */
    suspend fun handle(event: DocumentEvent) {
        if (event.fromWorkflow) return
        if (inRun.get() > 0) return
        val document = documentRepository.getDocument(event.documentId).getOrNull() ?: return
        for (workflow in workflowRepository.listEnabled()) {
            if (event.event !in workflow.triggers) continue
            val startedAt = System.currentTimeMillis()
            inRun.incrementAndGet()
            val result = try {
                engine.run(workflow, event.event, document)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unexpected error must still leave a trace in the history
                // instead of killing the event collector.
                Tracing.e(
                    Category.WORKFLOW,
                    TAG,
                    "run ${workflow.name} on ${document.name} crashed: $e",
                )
                RunResult(RunStatus.FAILURE, emptyList())
            } finally {
                inRun.decrementAndGet()
            }
            workflowRepository.recordRun(
                workflow,
                event.event,
                document.id,
                document.name,
                startedAt,
                result,
            )
            publish(workflow, document.id, document.name, result)
        }
    }

    /** Emits the outcome for the UI and makes failed/deferred runs visible. */
    private fun publish(
        workflow: Workflow,
        documentId: String,
        documentName: String?,
        result: RunResult,
    ) {
        // Empty nodes with SUCCESS means the conditions did not hold; there is
        // nothing to report. A failure with no nodes (e.g. render failure) still
        // needs to be visible.
        if (result.nodes.isEmpty() && result.status == RunStatus.SUCCESS) return
        val failureReason = result.nodes.firstOrNull { it.status == NodeStatus.FAILED }?.reason
        Tracing.i(
            Category.WORKFLOW,
            TAG,
            "run ${workflow.name} on $documentName -> ${result.status}",
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
            RunStatus.DEFERRED -> notifier.notifyDeferred(workflow.name, documentName)
            RunStatus.FAILURE, RunStatus.PARTIAL_FAILURE ->
                notifier.notifyFailed(workflow.name, documentName, failureReason)
            RunStatus.SUCCESS -> Unit
        }
    }

    private companion object {
        const val TAG = "WorkflowTriggerRegistry"
    }
}
