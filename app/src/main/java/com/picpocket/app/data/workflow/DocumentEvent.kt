package com.picpocket.app.data.workflow

import com.picpocket.app.domain.workflow.model.TriggerEvent

/**
 * A document mutation emitted by the data layer. `fromWorkflow` marks events
 * produced while a workflow run is mutating data, so the registry can ignore
 * them (re-entrancy guard).
 */
data class DocumentEvent(
    val event: TriggerEvent,
    val documentId: String,
    val fromWorkflow: Boolean = false,
)
