package com.picpocket.app.ui.screens.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.data.model.Tag
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.util.fuzzyMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TagManagementUiState(
    val allTags: List<Tag> = emptyList(),
    val searchQuery: String = "",
    val selectionMode: Boolean = false,
    val selectedTagIds: Set<Long> = emptySet(),
    val showCreateDialog: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val pendingDeleteTagId: Long? = null,
    val allWorkflows: List<Workflow> = emptyList(),
    val pendingDeleteTagIds: Set<Long> = emptySet(),
    val showDeleteBlocked: Boolean = false,
    val editingTagId: Long? = null,
    val editingTagName: String = "",
    val message: String? = null,
)

@HiltViewModel
@OptIn(FlowPreview::class)
class TagManagementViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val workflowRepository: WorkflowRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TagManagementUiState())
    val uiState: StateFlow<TagManagementUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _debouncedQuery = _searchQuery.debounce(200)
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        viewModelScope.launch {
            combine(
                repository.observeAllTags(),
                _debouncedQuery,
            ) { tags, query ->
                if (query.isBlank()) tags
                else tags.filter { fuzzyMatch(query, it.name) }
            }.collect { filtered ->
                _uiState.update {
                    it.copy(
                        allTags = filtered,
                        selectedTagIds = it.selectedTagIds.filter { id ->
                            filtered.any { t -> t.id == id }
                        }.toSet(),
                    )
                }
            }
        }
        // Workflows are read here only to know which tags are in use; the
        // Workflows screen owns editing them.
        viewModelScope.launch {
            workflowRepository.observeWorkflows().collect { workflows ->
                _uiState.update { state ->
                    val stillBlocked = state.pendingDeleteTagIds.any { id ->
                        workflows.any { it.referencesTag(id) }
                    }
                    val wasBlocked = state.showDeleteBlocked
                    state.copy(
                        allWorkflows = workflows,
                        showDeleteBlocked = wasBlocked && stillBlocked,
                        showDeleteConfirmation =
                            if (wasBlocked && !stillBlocked) true else state.showDeleteConfirmation,
                    )
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun showCreateDialog() = _uiState.update { it.copy(showCreateDialog = true) }

    fun hideCreateDialog() = _uiState.update { it.copy(showCreateDialog = false) }

    fun createTag(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createTag(name.trim())
            _uiState.update { it.copy(showCreateDialog = false) }
        }
    }

    fun startEditing(tagId: Long) {
        val tag = _uiState.value.allTags.find { it.id == tagId } ?: return
        _uiState.update { it.copy(editingTagId = tagId, editingTagName = tag.name, message = null) }
    }

    fun updateEditingName(name: String) = _uiState.update { it.copy(editingTagName = name) }

    fun saveEdit() {
        val state = _uiState.value
        val tagId = state.editingTagId ?: return
        val name = state.editingTagName.trim()
        if (name.isBlank()) {
            cancelEdit()
            return
        }
        if (state.allTags.any { it.id != tagId && it.name.equals(name, ignoreCase = true) }) {
            _uiState.update { it.copy(message = "A tag named \"$name\" already exists") }
            return
        }
        viewModelScope.launch {
            repository.renameTag(tagId, name)
            _uiState.update { it.copy(editingTagId = null, editingTagName = "", message = null) }
        }
    }

    fun cancelEdit() = _uiState.update { it.copy(editingTagId = null, editingTagName = "", message = null) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun enterSelectionMode(tagId: Long) {
        _uiState.update { it.copy(selectionMode = true, selectedTagIds = setOf(tagId)) }
    }

    fun toggleSelection(tagId: Long) {
        _uiState.update { state ->
            val newSelection = if (tagId in state.selectedTagIds) {
                state.selectedTagIds - tagId
            } else {
                state.selectedTagIds + tagId
            }
            state.copy(selectedTagIds = newSelection, selectionMode = newSelection.isNotEmpty())
        }
    }

    fun exitSelectionMode() = _uiState.update { it.copy(selectionMode = false, selectedTagIds = emptySet()) }

    fun showDeleteConfirmation() = _uiState.update { it.copy(showDeleteConfirmation = true) }

    fun showDeleteConfirmationForTag(tagId: Long) =
        _uiState.update { it.copy(showDeleteConfirmation = true, pendingDeleteTagId = tagId) }

    fun hideDeleteConfirmation() = _uiState.update {
        it.copy(
            showDeleteConfirmation = false,
            pendingDeleteTagId = null,
            pendingDeleteTagIds = emptySet(),
        )
    }

    /** Workflows that reference any of the tags queued for deletion. */
    fun blockingWorkflows(): List<Workflow> {
        val ids = _uiState.value.pendingDeleteTagIds
        return _uiState.value.allWorkflows.filter { wf -> ids.any { wf.referencesTag(it) } }
    }

    fun confirmDelete() {
        val state = _uiState.value
        val tagId = state.pendingDeleteTagId
        val ids = if (tagId != null) listOf(tagId) else state.selectedTagIds.toList()
        if (ids.isEmpty()) {
            hideDeleteConfirmation()
            return
        }
        val blocking = state.allWorkflows.filter { wf -> ids.any { wf.referencesTag(it) } }
        if (blocking.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    showDeleteConfirmation = false,
                    showDeleteBlocked = true,
                    pendingDeleteTagIds = ids.toSet(),
                )
            }
            return
        }
        viewModelScope.launch {
            repository.deleteTags(ids)
            if (tagId != null) {
                _uiState.update { it.copy(showDeleteConfirmation = false, pendingDeleteTagId = null) }
            } else {
                exitSelectionMode()
                hideDeleteConfirmation()
            }
        }
    }

    fun hideDeleteBlocked() = _uiState.update {
        it.copy(showDeleteBlocked = false, pendingDeleteTagIds = emptySet())
    }
}
