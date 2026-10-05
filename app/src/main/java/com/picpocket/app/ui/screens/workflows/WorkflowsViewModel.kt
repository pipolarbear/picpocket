package com.picpocket.app.ui.screens.workflows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.domain.workflow.model.Workflow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkflowsUiState(
    val workflows: List<Workflow> = emptyList(),
    val showEditor: Boolean = false,
    val editorWorkflowId: Long? = null,
    /** Set when the user tapped delete but has not confirmed yet. */
    val pendingDelete: Workflow? = null,
    /** A workflow just deleted, offered for undo. */
    val lastDeleted: Workflow? = null,
    val message: String? = null,
)

@HiltViewModel
class WorkflowsViewModel @Inject constructor(
    private val workflowRepository: WorkflowRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkflowsUiState())
    val uiState: StateFlow<WorkflowsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            workflowRepository.observeWorkflows().collect { workflows ->
                _uiState.update { it.copy(workflows = workflows) }
            }
        }
    }

    /** Opens the editor; [workflowId] null creates a new workflow. */
    fun openEditor(workflowId: Long?) =
        _uiState.update { it.copy(showEditor = true, editorWorkflowId = workflowId) }

    fun closeEditor() = _uiState.update { it.copy(showEditor = false, editorWorkflowId = null) }

    /** Asks for confirmation before deleting; [delete] performs the delete. */
    fun requestDelete(workflow: Workflow) =
        _uiState.update { it.copy(pendingDelete = workflow) }

    fun cancelDelete() = _uiState.update { it.copy(pendingDelete = null) }

    fun delete() {
        val workflow = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            workflowRepository.delete(workflow.id)
            _uiState.update {
                it.copy(
                    pendingDelete = null,
                    lastDeleted = workflow,
                    message = "Deleted \"${workflow.name}\"",
                )
            }
        }
    }

    /** Restores the most recently deleted workflow (same id and rule). */
    fun undoDelete() {
        val workflow = _uiState.value.lastDeleted ?: return
        viewModelScope.launch {
            workflowRepository.save(workflow)
            _uiState.update { it.copy(lastDeleted = null, message = null) }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    /** Duplicates a workflow (a copy named "<name> copy"); the copy is added to the list. */
    fun clone(id: Long) {
        viewModelScope.launch { workflowRepository.clone(id) }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { workflowRepository.setEnabled(id, enabled) }
    }
}
