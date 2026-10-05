package com.picpocket.app.ui.screens.workflows

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.picpocket.app.data.model.Tag
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.CompareOp
import com.picpocket.app.domain.workflow.model.Condition
import com.picpocket.app.domain.workflow.model.NodeStatus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.WorkflowApps
import java.text.DateFormat
import java.util.Date

private val COMPARE_LABELS = mapOf(
    CompareOp.GE to "≥",
    CompareOp.LE to "≤",
    CompareOp.EQ to "=",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorSheet(
    workflowId: Long?,
    onDismiss: () -> Unit,
    viewModel: WorkflowEditorViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        WorkflowEditorContent(workflowId = workflowId, viewModel = viewModel)
    }
}

@Composable
fun WorkflowEditorContent(
    workflowId: Long?,
    viewModel: WorkflowEditorViewModel,
) {
    LaunchedEffect(workflowId) { viewModel.load(workflowId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var folderTargetNodeId by remember { mutableStateOf<String?>(null) }
    var showAddRootMenu by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val target = folderTargetNodeId
        if (uri != null && target != null) {
            viewModel.onFolderPicked(target, uri)
        }
        folderTargetNodeId = null
    }

    // Transient messages (saved, run result, validation) surface as a Snackbar
    // instead of a line of text that scrolls away.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp),
            ) {
                EditorBody(
                    state = state,
                    viewModel = viewModel,
                    showAddRootMenu = showAddRootMenu,
                    onShowAddRootMenu = { showAddRootMenu = it },
                    onPickFolder = {
                        folderTargetNodeId = it
                        folderPicker.launch(null)
                    },
                )
            }
            // Save / Run stay visible no matter how long the editor gets.
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = viewModel::save) { Text("Save") }
                OutlinedButton(onClick = viewModel::runNow) { Text("Run now") }
                Spacer(Modifier.weight(1f))
                state.runs.firstOrNull()?.let { run ->
                    Text(
                        "Last run: ${runStatusLabel(run.status)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (run.status == RunStatus.SUCCESS) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
        )
    }
}

@Composable
private fun EditorBody(
    state: WorkflowEditorUiState,
    viewModel: WorkflowEditorViewModel,
    showAddRootMenu: Boolean,
    onShowAddRootMenu: (Boolean) -> Unit,
    onPickFolder: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Workflow", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.workflow.name,
            onValueChange = viewModel::setName,
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Section(
            title = "When",
            subtitle = "Fires when any selected event happens.",
        ) {
            TriggerTree(
                selected = state.workflow.triggers,
                onToggle = viewModel::toggleTrigger,
                onSelectAll = viewModel::setAllTriggers,
            )
            if (state.workflow.triggers.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "No events selected — this workflow only runs when you tap Run now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section(
            title = "Conditions",
            subtitle = "All conditions must hold.",
        ) {
            state.workflow.conditions.forEachIndexed { index, condition ->
                ConditionRow(
                    condition = condition,
                    tags = state.allTags,
                    viewModel = viewModel,
                    index = index,
                    error = state.conditionErrors[index],
                )
            }
            AddConditionRow(viewModel, state.allTags)
        }

        Section(
            title = "Then",
            subtitle = "Actions at this level run in parallel. Add a step under an action to run it afterwards.",
        ) {
            if (state.workflow.roots.isEmpty()) {
                Text(
                    "No actions yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.workflow.roots.forEach { node ->
                ActionNodeRow(
                    node = node,
                    nodeErrors = state.nodeErrors,
                    viewModel = viewModel,
                    onPickFolder = onPickFolder,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedIconButton(
                onClick = { onShowAddRootMenu(true) },
                modifier = Modifier.testTag("add_action"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add action")
            }
            DropdownMenu(expanded = showAddRootMenu, onDismissRequest = { onShowAddRootMenu(false) }) {
                // Terminal actions (delete) cannot be a top-level action.
                ActionType.entries.filterNot { it.terminal }.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        onClick = { viewModel.addRoot(type); onShowAddRootMenu(false) },
                    )
                }
            }
        }

        if (state.runs.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "History",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearHistory) { Text("Clear") }
            }
            state.runs.forEach { run ->
                val finished = run.finishedAt.let { if (it > 0) DateFormat.getTimeInstance().format(Date(it)) else "" }
                Text(
                    "${runStatusLabel(run.status)} · ${run.trigger.label} · $finished",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (run.status == RunStatus.SUCCESS) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                run.nodes.forEach { node ->
                    val mark = if (node.status == NodeStatus.FAILED) "✗" else "✓"
                    val duration = node.durationMs.takeIf { it > 0 }?.let { " (${it}ms)" } ?: ""
                    Text(
                        "  $mark ${node.type.label}$duration${node.reason?.let { ": $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun runStatusLabel(status: RunStatus): String = when (status) {
    RunStatus.SUCCESS -> "Succeeded"
    RunStatus.PARTIAL_FAILURE -> "Partially failed"
    RunStatus.FAILURE -> "Failed"
}

@Composable
private fun Section(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    Column(content = content)
    Spacer(Modifier.height(16.dp))
}

/** Indents content under a vertical guide line to convey hierarchy. */
@Composable
private fun TreeIndent(content: @Composable ColumnScope.() -> Unit) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        VerticalDivider(
            modifier = Modifier.fillMaxHeight().padding(start = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Column(modifier = Modifier.padding(start = 12.dp), content = content)
    }
}

@Composable
private fun TriggerTree(
    selected: List<TriggerEvent>,
    onToggle: (TriggerEvent) -> Unit,
    onSelectAll: (Boolean) -> Unit,
) {
    val selectable = TriggerEvent.selectable
    val selectedCount = selectable.count { it in selected }
    val parentState = when {
        selectedCount == 0 -> ToggleableState.Off
        selectedCount == selectable.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(
                state = parentState,
                onClick = { onSelectAll(selectedCount != selectable.size) },
                modifier = Modifier.testTag("trigger_all"),
            )
            Text("All events (any)", style = MaterialTheme.typography.bodyMedium)
        }
        TreeIndent {
            selectable.forEach { event ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = event in selected, onCheckedChange = { onToggle(event) })
                    Text(
                        event.label,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun conditionLabel(condition: Condition): String = when (condition) {
    is Condition.HasTag -> "Tag:"
    is Condition.PageCount -> "Pages:"
    is Condition.NameMatches -> "Name:"
    is Condition.OcrTextMatches -> "OCR:"
}

@Composable
private fun ConditionRow(
    condition: Condition,
    tags: List<Tag>,
    viewModel: WorkflowEditorViewModel,
    index: Int,
    error: String?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                conditionLabel(condition),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(58.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                ConditionEditor(condition, tags, viewModel, index)
            }
            TextButton(onClick = { viewModel.removeCondition(index) }) { Text("Remove") }
        }
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConditionEditor(
    condition: Condition,
    tags: List<Tag>,
    viewModel: WorkflowEditorViewModel,
    index: Int,
) {
    when (condition) {
        is Condition.HasTag -> TagPicker(condition, tags) { viewModel.updateCondition(index, Condition.HasTag(it)) }
        is Condition.PageCount -> PageCountEditor(condition) { viewModel.updateCondition(index, it) }
        is Condition.NameMatches -> OutlinedTextField(
            value = condition.pattern,
            onValueChange = { viewModel.updateCondition(index, Condition.NameMatches(it)) },
            label = { Text("pattern (regex)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("name_pattern"),
        )
        is Condition.OcrTextMatches -> OutlinedTextField(
            value = condition.pattern,
            onValueChange = { viewModel.updateCondition(index, Condition.OcrTextMatches(it)) },
            label = { Text("pattern (regex)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("ocr_pattern"),
        )
    }
}

@Composable
private fun TagPicker(condition: Condition.HasTag, tags: List<Tag>, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = tags.find { it.id == condition.tagId }?.name
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("tag_picker")) {
            Text(selectedName ?: "Choose tag")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tags.forEach { tag ->
                DropdownMenuItem(text = { Text(tag.name) }, onClick = { onSelect(tag.id); expanded = false })
            }
        }
    }
}

@Composable
private fun PageCountEditor(condition: Condition.PageCount, onChange: (Condition.PageCount) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(COMPARE_LABELS[condition.op] ?: condition.op.name)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                CompareOp.entries.forEach { op ->
                    DropdownMenuItem(
                        text = { Text(COMPARE_LABELS[op] ?: op.name) },
                        onClick = { onChange(condition.copy(op = op)); expanded = false },
                    )
                }
            }
        }
        OutlinedTextField(
            value = condition.n.toString(),
            onValueChange = { text ->
                val digits = text.filter { it.isDigit() }.take(4)
                onChange(condition.copy(n = digits.toIntOrNull() ?: 0))
            },
            label = { Text("count") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(110.dp).testTag("page_count_value"),
        )
    }
}

@Composable
private fun AddConditionRow(viewModel: WorkflowEditorViewModel, tags: List<Tag>) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }) { Text("+ Add condition") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Has tag") },
            enabled = tags.isNotEmpty(),
            onClick = {
                tags.firstOrNull()?.let { viewModel.addCondition(Condition.HasTag(it.id)) }
                expanded = false
            },
        )
        DropdownMenuItem(text = { Text("Page count") }, onClick = {
            viewModel.addCondition(Condition.PageCount(CompareOp.GE, 1)); expanded = false
        })
        DropdownMenuItem(text = { Text("Name matches") }, onClick = {
            viewModel.addCondition(Condition.NameMatches(".*")); expanded = false
        })
        DropdownMenuItem(text = { Text("OCR text matches") }, onClick = {
            viewModel.addCondition(Condition.OcrTextMatches(".*")); expanded = false
        })
    }
}

@Composable
private fun ActionNodeRow(
    node: ActionNode,
    nodeErrors: Map<String, String>,
    viewModel: WorkflowEditorViewModel,
    onPickFolder: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                node.type.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            var addChild by remember { mutableStateOf(false) }
            if (!node.type.terminal) {
                IconButton(onClick = { addChild = true }, modifier = Modifier.testTag("add_step")) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add step",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(expanded = addChild, onDismissRequest = { addChild = false }) {
                    ActionType.entries.forEach { type ->
                        DropdownMenuItem(text = { Text(type.label) }, onClick = {
                            viewModel.addChild(node.id, type); addChild = false
                        })
                    }
                }
            }
            IconButton(onClick = { viewModel.removeNode(node.id) }, modifier = Modifier.testTag("remove_node")) {
                Icon(Icons.Default.Close, contentDescription = "Remove action")
            }
        }
        NodeParamsEditor(node, viewModel, onPickFolder)
        nodeErrors[node.id]?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (node.children.isNotEmpty()) {
            TreeIndent {
                node.children.forEach { child ->
                    ActionNodeRow(child, nodeErrors, viewModel, onPickFolder)
                }
            }
        }
    }
}

@Composable
private fun NodeParamsEditor(
    node: ActionNode,
    viewModel: WorkflowEditorViewModel,
    onPickFolder: (String) -> Unit,
) {
    when (val params = node.params) {
        is ActionParams.Encrypt -> {
            var passphraseVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = params.passphrase,
                onValueChange = { viewModel.setNodeParams(node.id, ActionParams.Encrypt(it)) },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = if (passphraseVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                        Icon(
                            imageVector = if (passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passphraseVisible) "Hide passphrase" else "Show passphrase",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is ActionParams.Notify -> OutlinedTextField(
            value = params.message,
            onValueChange = { viewModel.setNodeParams(node.id, ActionParams.Notify(it)) },
            label = { Text("Message") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is ActionParams.SaveToFolder -> {
            val resolved = remember(params.folderUri) { viewModel.folderDisplayName(params.folderUri) }
            val name = resolved.takeIf { it != "Folder" } ?: params.displayName.ifBlank { "Folder" }
            OutlinedButton(onClick = { onPickFolder(node.id) }) {
                Text("Folder: $name")
            }
        }
        is ActionParams.SendToApp -> {
            var expanded by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { expanded = true }) { Text("App: ${params.displayName}") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                WorkflowApps.all.forEach { app ->
                    DropdownMenuItem(text = { Text(app.displayName) }, onClick = {
                        viewModel.setNodeParams(node.id, ActionParams.SendToApp(app.packageName, app.displayName))
                        expanded = false
                    })
                }
            }
        }
        ActionParams.None -> Unit
    }
}