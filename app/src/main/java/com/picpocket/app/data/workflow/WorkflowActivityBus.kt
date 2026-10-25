package com.picpocket.app.data.workflow

import com.picpocket.app.domain.workflow.model.RunStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What a workflow last did to a document. Used for the "document removed" message. */
data class WorkflowActivity(
    val documentId: String,
    val documentName: String?,
    val workflowName: String,
    val status: RunStatus,
    /** The folder name of a save-to-folder step, when the run had one. */
    val saveTarget: String?,
    val failureReason: String?,
)

/**
 * Process-lifetime stream of workflow outcomes keyed by document. The detail
 * screen subscribes when it opens and also reads [lastFor] so it can explain
 * why a document vanished just before it was shown.
 */
@Singleton
class WorkflowActivityBus @Inject constructor() {

    private val _events = MutableSharedFlow<WorkflowActivity>(extraBufferCapacity = 16)
    val events: SharedFlow<WorkflowActivity> = _events.asSharedFlow()

    private val last = ConcurrentHashMap<String, WorkflowActivity>()

    fun emit(event: WorkflowActivity) {
        last[event.documentId] = event
        _events.tryEmit(event)
    }

    fun lastFor(documentId: String): WorkflowActivity? = last[documentId]
}
