package com.picpocket.app.data.workflow

import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.di.ApplicationScope
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
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
            } finally {
                inRun.decrementAndGet()
            }
            workflowRepository.recordRun(workflow, event.event, startedAt, result)
        }
    }
}
