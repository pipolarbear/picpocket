package com.picpocket.app.data.workflow

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Process-lifetime stream of document events that feed the workflow triggers. */
@Singleton
class DocumentEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<DocumentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DocumentEvent> = _events.asSharedFlow()

    fun emit(event: DocumentEvent) {
        _events.tryEmit(event)
    }
}
