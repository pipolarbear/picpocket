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

    fun delete(id: Long) {
        viewModelScope.launch { workflowRepository.delete(id) }
    }

    /** Duplicates a workflow (a copy named "<name> copy"); the copy is added to the list. */
    fun clone(id: Long) {
        viewModelScope.launch { workflowRepository.clone(id) }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { workflowRepository.setEnabled(id, enabled) }
    }
}
