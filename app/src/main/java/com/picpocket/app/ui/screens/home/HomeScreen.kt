package com.picpocket.app.ui.screens.home

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TabUnselected
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.model.Tag
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.ui.components.MatchMode
import com.picpocket.app.ui.components.ShareOptionsSheet
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.ui.components.TagChip
import com.picpocket.app.ui.components.TagSelectorSheet
import com.picpocket.app.ui.theme.TagColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onScanClick: () -> Unit,
    onDocumentClick: (DocumentId) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val allTags by viewModel.allTags.collectAsState()

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportPdf(context, uri)
        }
    }

    val importPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importPdf(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.importEvents.collect { event ->
            when (event) {
                is HomeViewModel.ImportEvent.NavigateToDocument -> {
                    Toast.makeText(context, "PDF imported successfully", Toast.LENGTH_SHORT).show()
                    onDocumentClick(event.documentId)
                }
                is HomeViewModel.ImportEvent.ShowError -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(state.pendingExportDocIds) {
        if (state.pendingExportDocIds.isNotEmpty() && !state.showExportDialog) {
            val doc = state.documents.find { it.id == state.pendingExportDocIds.first() }
            val name = doc?.name?.replace(" ", "_") ?: "document"
            exportPdfLauncher.launch("${name}.pdf")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.selectionMode) {
                            "${state.selectedDocumentIds.size} selected"
                        } else {
                            "PicPocket"
                        }
                    )
                },
                actions = {
                    if (state.selectionMode) {
                        val allSelected = state.selectedDocumentIds.size == state.documents.size
                        IconButton(onClick = { viewModel.toggleSelectAll() }) {
                            Icon(
                                if (allSelected) Icons.Default.TabUnselected else Icons.Default.SelectAll,
                                contentDescription = if (allSelected) "Deselect all" else "Select all",
                            )
                        }
                        if (state.selectedDocumentIds.size == 1) {
                            IconButton(onClick = { viewModel.showRenameDialog() }) {
                                Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "Rename")
                            }
                        }
                        IconButton(onClick = { viewModel.showTagsSheet() }) {
                            Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Add tags")
                        }
                        IconButton(onClick = { viewModel.showShareSheet() }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { viewModel.showExportDialog() }) {
                            Icon(Icons.Default.Description, contentDescription = "Export as PDF")
                        }
                        IconButton(onClick = { viewModel.showDeleteConfirmation() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                        IconButton(onClick = { viewModel.exitSelectionMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection mode")
                        }
                    } else {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    if (state.sortReversed) Icons.Default.SwapVert
                                    else Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Sort",
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                order.label,
                                                color = if (order == state.sortOrder)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurface,
                                            )
                                        },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            showSortMenu = false
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Reverse order",
                                            color = if (state.sortReversed)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.SwapVert, contentDescription = null)
                                    },
                                    trailingIcon = if (state.sortReversed) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                    onClick = {
                                        viewModel.toggleSortReversed()
                                        showSortMenu = false
                                    },
                                )
                            }
                        }
                        if (state.filterTagIds.isNotEmpty()) {
                            IconButton(onClick = { viewModel.showFilteredShareSheet() }) {
                                Icon(Icons.Default.Share, contentDescription = "Share filtered")
                            }
                        }
                        IconButton(onClick = {
                            importPdfLauncher.launch(arrayOf("application/pdf"))
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Import PDF")
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                FloatingActionButton(onClick = onScanClick) {
                    Icon(Icons.Default.Add, contentDescription = "Scan document")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = {
                    Text(
                        if (state.searchInContent) "Search names + content..." else "Search names..."
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    Row {
                        IconButton(onClick = { viewModel.toggleSearchInContent() }) {
                            Icon(
                                if (state.searchInContent) Icons.AutoMirrored.Filled.TextSnippet
                                else Icons.Default.FilterList,
                                contentDescription = if (state.searchInContent) "Search names only"
                                else "Search in content",
                                tint = if (state.searchInContent)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        IconButton(onClick = { viewModel.showTagFilterSheet() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Label,
                                contentDescription = "Filter by tags",
                                tint = if (state.filterTagIds.isNotEmpty())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                ),
            )

            if (state.filterTagIds.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.filterTagIds.forEach { tagId ->
                        val tag = allTags.find { it.id == tagId }
                        if (tag != null) {
                            TagChip(
                                tag = tag,
                                onRemove = { viewModel.toggleFilterTag(tagId) },
                            )
                        }
                    }
                }
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading...")
                }
            } else if (state.documents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (state.searchQuery.isNotEmpty()) "No documents match your search"
                            else "No documents yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        if (state.searchQuery.isBlank()) {
                            Text(
                                "Tap + to scan your first document",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(state.documents, key = { it.id }) { document ->
                        DocumentCard(
                            document = document,
                            tags = state.documentTags[document.id].orEmpty(),
                            isSelected = document.id in state.selectedDocumentIds,
                            selectionMode = state.selectionMode,
                            onClick = {
                                if (viewModel.onDocumentTap(document.id)) {
                                    onDocumentClick(document.id)
                                }
                            },
                            onLongClick = { viewModel.onDocumentLongPress(document.id) },
                        )
                    }
                }
            }
        }
    }

    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            title = { Text("Delete documents?") },
            text = {
                Text("${state.selectedDocumentIds.size} document(s) will be permanently deleted.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSelected() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirmation() }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.showTagsSheet) {
        TagSelectorSheet(
            allTags = allTags,
            selectedTagIds = state.selectedTagIds,
            onToggleTag = { viewModel.toggleTag(it) },
            onCreateTag = { viewModel.createTagAndSelect(it) },
            onDone = { viewModel.applyTagsToSelected() },
            onDismiss = { viewModel.hideTagsSheet() },
        )
    }

    if (state.showTagFilterSheet) {
        TagSelectorSheet(
            title = "Filter by Tags",
            showCreate = false,
            matchMode = state.filterMatchMode,
            onMatchModeChange = { viewModel.setFilterMatchMode(it) },
            allTags = allTags,
            selectedTagIds = state.filterTagIds,
            onToggleTag = { viewModel.toggleFilterTag(it) },
            onCreateTag = {},
            onDone = { viewModel.hideTagFilterSheet() },
            onDismiss = { viewModel.hideTagFilterSheet() },
        )
    }

    if (state.showRenameDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideRenameDialog() },
            title = { Text("Rename document") },
            text = {
                OutlinedTextField(
                    value = state.renameText,
                    onValueChange = { viewModel.updateRenameText(it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renameSelected() }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideRenameDialog() }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.showRenameOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelRenameOverwrite() },
            title = { Text("Overwrite document?") },
            text = { Text("A document named \"${state.renameOverwriteTargetName}\" already exists. Overwrite?") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRenameOverwrite() }) {
                    Text("Overwrite")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelRenameOverwrite() }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.showShareSheet) {
        ShareOptionsSheet(
            onDismiss = { viewModel.hideShareSheet() },
            onShareVia = {
                viewModel.shareViaSystem(context)
                viewModel.hideShareSheet()
            },
            onSaveToDrive = { uri ->
                viewModel.saveToDrive(context, uri)
                viewModel.hideShareSheet()
            },
        )
    }

    if (state.showImportProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing PDF") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Rendering pages...")
                }
            },
            confirmButton = {},
        )
    }

    if (state.importErrorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissImportError() },
            title = { Text("Import Failed") },
            text = { Text(state.importErrorMessage ?: "Could not import PDF") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissImportError() }) {
                    Text("OK")
                }
            },
        )
    }

    if (state.showExportDialog) {
        var showPageSizeMenu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { viewModel.hideExportDialog() },
            title = { Text("Export as PDF") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Page Size", modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { showPageSizeMenu = true }) {
                            Text(state.exportPageSize.label)
                        }
                        DropdownMenu(
                            expanded = showPageSizeMenu,
                            onDismissRequest = { showPageSizeMenu = false },
                        ) {
                            PageSize.entries.forEach { size ->
                                DropdownMenuItem(
                                    text = { Text(size.label) },
                                    onClick = {
                                        viewModel.setExportPageSize(size)
                                        showPageSizeMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.hideExportDialog()
                }) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideExportDialog() }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    document: Document,
    tags: List<Tag>,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "cardBg",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = bgColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = document.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!document.ocrComplete) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "OCR pending",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDate(document.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                )
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.take(3).forEach { tag ->
                            val dotColor = TagColors.getOrElse(tag.colorIndex) { TagColors[0] }
                            Box(
                                modifier = Modifier
                                    .background(dotColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = dotColor,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (tags.size > 3) {
                            Text(
                                text = "+${tags.size - 3}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
