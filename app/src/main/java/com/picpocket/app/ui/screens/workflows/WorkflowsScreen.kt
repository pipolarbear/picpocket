package com.picpocket.app.ui.screens.workflows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.Workflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(
    onNavigateBack: () -> Unit,
    initialWorkflowId: Long? = null,
    viewModel: WorkflowsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(initialWorkflowId) {
        if (initialWorkflowId != null) viewModel.openEditor(initialWorkflowId)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
        )
        viewModel.consumeMessage()
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Workflows") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openEditor(null) }) {
                Icon(Icons.Default.Add, contentDescription = "New workflow")
            }
        },
    ) { padding ->
        if (state.workflows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No workflows yet — tap + to create one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(state.workflows, key = { it.id }) { workflow ->
                    WorkflowRow(
                        workflow = workflow,
                        onEdit = { viewModel.openEditor(workflow.id) },
                        onDuplicate = { viewModel.clone(workflow.id) },
                        onDelete = { viewModel.requestDelete(workflow) },
                        onToggleEnabled = { viewModel.setEnabled(workflow.id, it) },
                    )
                }
            }
        }
    }

    if (state.showEditor) {
        WorkflowEditorSheet(
            workflowId = state.editorWorkflowId,
            onDismiss = { viewModel.closeEditor() },
        )
    }

    state.pendingDelete?.let { workflow ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete workflow?") },
            text = { Text("This removes \"${workflow.name}\" and its run history. You can undo right after.") },
            confirmButton = {
                TextButton(onClick = viewModel::delete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun WorkflowRow(
    workflow: Workflow,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val actionCount = countNodes(workflow.roots)
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(workflow.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${workflow.triggers.size} trigger(s) · $actionCount action(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(
                checked = workflow.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.semantics {
                    contentDescription = if (workflow.enabled) "Enabled" else "Disabled"
                },
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit workflow", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp).testTag("duplicate_workflow")) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate workflow", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp).testTag("delete_workflow")) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete workflow",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun countNodes(nodes: List<ActionNode>): Int =
    nodes.sumOf { 1 + countNodes(it.children) }
