package com.picpocket.app.ui.screens.workflows

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.data.model.Tag
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.data.repository.WorkflowRun
import com.picpocket.app.domain.storage.FolderAccess
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.Condition
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class WorkflowEditorUiState(
    val workflow: Workflow = Workflow(name = ""),
    val isNew: Boolean = true,
    val allTags: List<Tag> = emptyList(),
    val runs: List<WorkflowRun> = emptyList(),
    val message: String? = null,
    val saved: Boolean = false,
    /** index in `workflow.conditions` -> reason it is invalid. */
    val conditionErrors: Map<Int, String> = emptyMap(),
    /** action node id -> reason it is invalid. */
    val nodeErrors: Map<String, String> = emptyMap(),
)

@HiltViewModel
class WorkflowEditorViewModel @Inject constructor(
    private val workflowRepository: WorkflowRepository,
    private val documentRepository: DocumentRepository,
    private val engine: WorkflowEngine,
    private val folderAccess: FolderAccess,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkflowEditorUiState())
    val uiState: StateFlow<WorkflowEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            documentRepository.observeAllTags().collect { tags ->
                _uiState.update { state ->
                    state.copy(
                        allTags = tags,
                        conditionErrors = validateConditions(state.workflow.conditions, tags),
                    )
                }
            }
        }
    }

    fun load(workflowId: Long?) {
        if (workflowId == null || workflowId == 0L) {
            _uiState.value = WorkflowEditorUiState(
                workflow = Workflow(name = "New workflow", triggers = TriggerEvent.selectable),
                isNew = true,
                allTags = _uiState.value.allTags,
            )
            return
        }
        viewModelScope.launch {
            val workflow = workflowRepository.get(workflowId)
            _uiState.update {
                it.copy(
                    workflow = workflow ?: it.workflow,
                    isNew = workflow == null,
                    nodeErrors = validateNodes((workflow ?: it.workflow).roots),
                )
            }
            workflowRepository.observeRuns(workflowId).collect { runs ->
                _uiState.update { it.copy(runs = runs) }
            }
        }
    }

    fun setName(name: String) = updateWorkflow { it.copy(name = name) }

    fun toggleTrigger(event: TriggerEvent) = updateWorkflow { workflow ->
        val triggers = workflow.triggers
        workflow.copy(triggers = if (event in triggers) triggers - event else triggers + event)
    }

    fun setAllTriggers(selected: Boolean) = updateWorkflow { workflow ->
        workflow.copy(triggers = if (selected) TriggerEvent.selectable else emptyList())
    }

    fun addCondition(condition: Condition) = updateWorkflow { workflow ->
        workflow.copy(conditions = workflow.conditions + condition)
    }

    fun updateCondition(index: Int, condition: Condition) = updateWorkflow { workflow ->
        workflow.copy(
            conditions = workflow.conditions.mapIndexed { i, existing ->
                if (i == index) condition else existing
            },
        )
    }

    fun removeCondition(index: Int) = updateWorkflow { workflow ->
        workflow.copy(conditions = workflow.conditions.filterIndexed { i, _ -> i != index })
    }

    fun addRoot(type: ActionType) = updateWorkflow(recomputeNodeErrors = true) { workflow ->
        workflow.copy(roots = workflow.roots + newNode(type))
    }

    fun addChild(parentId: String, type: ActionType) = updateWorkflow(recomputeNodeErrors = true) { workflow ->
        workflow.copy(roots = addChild(workflow.roots, parentId, newNode(type)))
    }

    fun removeNode(id: String) = updateWorkflow(recomputeNodeErrors = true) { workflow ->
        workflow.copy(roots = removeNode(workflow.roots, id))
    }

    fun setNodeParams(id: String, params: ActionParams) =
        updateWorkflow(recomputeNodeErrors = true) { workflow ->
            workflow.copy(roots = setParams(workflow.roots, id, params))
        }

    /** Called after the user picks a SAF folder: keeps the grant and stores its real name. */
    fun onFolderPicked(nodeId: String, uri: Uri) {
        val persisted = folderAccess.takePersistable(uri)
        setNodeParams(nodeId, ActionParams.SaveToFolder(uri.toString(), folderAccess.displayName(uri)))
        if (!persisted) {
            _uiState.update { it.copy(message = "Couldn't keep access to that folder — pick another") }
        }
    }

    /** The current folder name for a stored tree URI (falls back to the stored name at the call site). */
    fun folderDisplayName(folderUri: String): String {
        if (folderUri.isBlank()) return "Folder"
        val uri = runCatching { Uri.parse(folderUri) }.getOrNull() ?: return "Folder"
        return folderAccess.displayName(uri)
    }

    fun save() {
        val state = _uiState.value
        val workflow = state.workflow
        if (workflow.name.isBlank()) {
            _uiState.update { it.copy(message = "Name is required") }
            return
        }
        val conditionErrors = validateConditions(workflow.conditions, state.allTags)
        val nodeErrors = validateNodes(workflow.roots)
        if (conditionErrors.isNotEmpty() || nodeErrors.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    conditionErrors = conditionErrors,
                    nodeErrors = nodeErrors,
                    message = "Fix the highlighted items",
                )
            }
            return
        }
        viewModelScope.launch {
            val normalized = workflow.copy(conditions = normalize(workflow.conditions))
            val id = workflowRepository.save(normalized)
            _uiState.update {
                it.copy(
                    isNew = false,
                    saved = true,
                    message = "Saved",
                    workflow = normalized.copy(id = id),
                    conditionErrors = emptyMap(),
                    nodeErrors = emptyMap(),
                )
            }
        }
    }

    fun runNow() {
        viewModelScope.launch {
            val workflow = _uiState.value.workflow
            val document = documentRepository.getAllDocuments().getOrNull()?.firstOrNull()
            if (document == null) {
                _uiState.update { it.copy(message = "No documents to run against") }
                return@launch
            }
            val startedAt = System.currentTimeMillis()
            val result = engine.run(workflow, TriggerEvent.MANUAL, document, ignoreTriggerCheck = true)
            workflowRepository.recordRun(workflow, TriggerEvent.MANUAL, startedAt, result)
            val text = when (result.status) {
                RunStatus.SUCCESS -> "Run succeeded"
                RunStatus.PARTIAL_FAILURE -> "Run partially failed"
                RunStatus.FAILURE -> "Run failed"
            }
            _uiState.update { it.copy(message = text) }
        }
    }

    fun clearHistory() {
        val id = _uiState.value.workflow.id
        viewModelScope.launch { workflowRepository.clearHistory(if (id == 0L) null else id) }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    private fun updateWorkflow(
        recomputeNodeErrors: Boolean = false,
        block: (Workflow) -> Workflow,
    ) = _uiState.update { state ->
        val workflow = block(state.workflow)
        state.copy(
            workflow = workflow,
            conditionErrors = validateConditions(workflow.conditions, state.allTags),
            nodeErrors = if (recomputeNodeErrors) validateNodes(workflow.roots) else state.nodeErrors,
        )
    }

    private fun validateNodes(roots: List<ActionNode>): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        fun walk(nodes: List<ActionNode>, rootLevel: Boolean) {
            nodes.forEach { node ->
                val params = node.params
                if (params is ActionParams.SaveToFolder) {
                    val reason = when {
                        params.folderUri.isBlank() -> "Pick a folder"
                        !folderAccess.isPersisted(Uri.parse(params.folderUri)) ->
                            "Folder access was revoked; pick it again"
                        else -> null
                    }
                    if (reason != null) errors[node.id] = reason
                }
                // Delete is terminal and cannot be a top-level action.
                if (node.type == ActionType.DELETE && (rootLevel || node.children.isNotEmpty())) {
                    errors[node.id] = "Delete must be the last step"
                }
                walk(node.children, rootLevel = false)
            }
        }
        walk(roots, rootLevel = true)
        return errors
    }

    private fun newNode(type: ActionType) =
        ActionNode(id = UUID.randomUUID().toString(), type = type, params = defaultParams(type))

    private companion object {
        fun defaultParams(type: ActionType): ActionParams = when (type) {
            ActionType.ENCRYPT -> ActionParams.Encrypt("")
            ActionType.SAVE_TO_FOLDER -> ActionParams.SaveToFolder("", "")
            ActionType.SEND_TO_APP -> ActionParams.SendToApp("com.whatsapp", "WhatsApp")
            ActionType.NOTIFY -> ActionParams.Notify("")
            else -> ActionParams.None
        }

        /** Trims pattern whitespace then drops conditions with equal type + argument. */
        fun normalize(conditions: List<Condition>): List<Condition> =
            conditions.map { condition ->
                when (condition) {
                    is Condition.NameMatches -> condition.copy(pattern = condition.pattern.trim())
                    is Condition.OcrTextMatches -> condition.copy(pattern = condition.pattern.trim())
                    else -> condition
                }
            }.distinct()

        fun validateConditions(conditions: List<Condition>, tags: List<Tag>): Map<Int, String> {
            val errors = mutableMapOf<Int, String>()
            conditions.forEachIndexed { index, condition ->
                val reason = when (condition) {
                    is Condition.HasTag ->
                        if (tags.none { it.id == condition.tagId }) "Choose a tag" else null
                    is Condition.PageCount ->
                        if (condition.n < 0) "Enter a page count" else null
                    is Condition.NameMatches -> patternError(condition.pattern)
                    is Condition.OcrTextMatches -> patternError(condition.pattern)
                }
                if (reason != null) errors[index] = reason
            }
            return errors
        }

        fun patternError(pattern: String): String? = when {
            pattern.isBlank() -> "Enter a pattern"
            runCatching { Regex(pattern) }.isFailure -> "Invalid regex pattern"
            else -> null
        }

        fun addChild(nodes: List<ActionNode>, parentId: String, child: ActionNode): List<ActionNode> =
            nodes.map {
                if (it.id == parentId) it.copy(children = it.children + child)
                else it.copy(children = addChild(it.children, parentId, child))
            }

        fun removeNode(nodes: List<ActionNode>, id: String): List<ActionNode> =
            nodes.filter { it.id != id }.map { it.copy(children = removeNode(it.children, id)) }

        fun setParams(nodes: List<ActionNode>, id: String, params: ActionParams): List<ActionNode> =
            nodes.map {
                if (it.id == id) it.copy(params = params)
                else it.copy(children = setParams(it.children, id, params))
            }
    }
}