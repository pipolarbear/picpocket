package com.picpocket.app.ui.screens.deleted

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.store.TrashedDocument
import com.picpocket.app.drive.sync.DeviceRegistry
import com.picpocket.app.drive.sync.OrphanedDocument
import com.picpocket.app.domain.storage.TrashSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeletedDocumentsUiState(
    val orphans: List<OrphanedDocument> = emptyList(),
    /** Documents removed by a workflow, recoverable for [retentionDays]. */
    val trashed: List<TrashedDocument> = emptyList(),
    val retentionDays: Int = TrashSettings.DEFAULT_DAYS,
    val processing: Set<String> = emptySet(),
    val cleaning: Boolean = false,
)

@HiltViewModel
class DeletedDocumentsViewModel @Inject constructor(
    private val deviceRegistry: DeviceRegistry,
    private val documents: DocumentRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeletedDocumentsUiState())
    val uiState: StateFlow<DeletedDocumentsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun keepOrphan(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processing = it.processing + docId) }
            deviceRegistry.keepOrphan(docId)
            refresh()
        }
    }

    fun deleteOrphanLocally(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processing = it.processing + docId) }
            deviceRegistry.deleteOrphanLocally(docId)
            refresh()
        }
    }

    fun dismissOrphan(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processing = it.processing + docId) }
            deviceRegistry.dismissOrphan(docId)
            refresh()
        }
    }

    fun restoreTrashed(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processing = it.processing + docId) }
            documents.restoreTrashedDocument(docId)
            refresh()
        }
    }

    fun deleteTrashed(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processing = it.processing + docId) }
            documents.deleteTrashedDocument(docId)
            refresh()
        }
    }

    fun setRetention(days: Int) {
        TrashSettings.write(context, days)
        viewModelScope.launch {
            documents.purgeTrash(days)
            refresh()
        }
    }

    fun cleanDrive() {
        viewModelScope.launch {
            _uiState.update { it.copy(cleaning = true) }
            deviceRegistry.cleanDrive()
            refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val trashed = documents.listTrashedDocuments().getOrNull().orEmpty()
            _uiState.update {
                it.copy(
                    orphans = deviceRegistry.getOrphans(),
                    trashed = trashed,
                    retentionDays = TrashSettings.read(context),
                    processing = emptySet(),
                    cleaning = false,
                )
            }
        }
    }
}
