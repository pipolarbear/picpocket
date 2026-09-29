package com.picpocket.app.data.store

import android.content.Context
import com.picpocket.app.data.model.DocumentId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only "last seen" timestamps per document. Deliberately not part of the
 * synced metadata: opening a document would otherwise bump its metadata version
 * and trigger a Drive upload on every view.
 */
@Singleton
class DocumentAccessStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("document_access", Context.MODE_PRIVATE)

    private val _timestamps = MutableStateFlow(load())
    val timestamps: StateFlow<Map<DocumentId, Long>> = _timestamps.asStateFlow()

    fun snapshot(): Map<DocumentId, Long> = _timestamps.value

    fun touch(documentId: DocumentId) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(documentId, now).apply()
        _timestamps.value = _timestamps.value + (documentId to now)
    }

    fun remove(documentIds: Collection<DocumentId>) {
        if (documentIds.isEmpty()) return
        val editor = prefs.edit()
        documentIds.forEach { editor.remove(it) }
        editor.apply()
        _timestamps.value = _timestamps.value - documentIds.toSet()
    }

    private fun load(): Map<DocumentId, Long> =
        prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
}
