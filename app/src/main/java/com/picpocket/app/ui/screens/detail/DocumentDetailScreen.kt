package com.picpocket.app.ui.screens.detail

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Merge
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.scan.QualityTier
import com.picpocket.app.ui.components.ShareOptionsSheet
import com.picpocket.app.ui.components.TagSelectorSheet
import com.picpocket.app.ui.theme.TagColors
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DocumentDetailScreen(
    documentId: DocumentId,
    onNavigateBack: () -> Unit,
    onPageView: (DocumentId, Int) -> Unit = { _, _ -> },
    onAddPage: (DocumentId) -> Unit,
    viewModel: DocumentDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val contextForExport = LocalContext.current

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportPdf(contextForExport, uri)
        }
    }

    val activity = contextForExport as? Activity
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pageNumber = state.rescanPageNumber
        viewModel.clearPendingRescanIntentSender()
        if (pageNumber != null) {
            viewModel.handleRescanScannerResult(pageNumber, result.data)
        }
    }

    LaunchedEffect(state.pendingRescanIntentSender) {
        state.pendingRescanIntentSender?.let { sender ->
            val request = IntentSenderRequest.Builder(sender).build()
            scannerLauncher.launch(request)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.rescanEvents.collect { event ->
            when (event) {
                is DocumentDetailViewModel.RescanEvent.ShowError -> {
                    Toast.makeText(contextForExport, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(documentId) {
        viewModel.loadDocument(documentId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.document?.name ?: "Document")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleInfoPane() }) {
                        Icon(
                            if (state.showInfoPane) Icons.Default.ExpandLess
                            else Icons.Default.ExpandMore,
                            contentDescription = if (state.showInfoPane) "Collapse info" else "Expand info",
                        )
                    }
                    IconButton(onClick = { viewModel.toggleEditMode() }, enabled = !state.isLoading) {
                        Icon(
                            if (state.isEditMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (state.isEditMode) "Done editing" else "Edit",
                        )
                    }
                    Box {
                        IconButton(onClick = { viewModel.toggleOverflowMenu() }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = state.showOverflowMenu,
                            onDismissRequest = { viewModel.hideOverflowMenu() },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export as PDF") },
                                onClick = {
                                    viewModel.hideOverflowMenu()
                                    viewModel.showExportDialog()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    viewModel.hideOverflowMenu()
                                    viewModel.showRenameDialog()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    viewModel.hideOverflowMenu()
                                    viewModel.showShareSheet()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Collate pages") },
                                enabled = state.pages.size >= 2,
                                onClick = {
                                    viewModel.hideOverflowMenu()
                                    viewModel.showCollateDialog()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Merge, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    viewModel.hideOverflowMenu()
                                    viewModel.showDeleteConfirmation()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.isEditMode) {
                FloatingActionButton(onClick = { onAddPage(documentId) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add page")
                }
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                state.document?.let { doc ->
                    if (state.showInfoPane) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Created: ${formatDate(doc.createdAt)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "Updated: ${formatDate(doc.updatedAt)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "Pages: ${state.pages.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (doc.totalFileSize > 0) {
                                    Text(
                                        "Size: ${formatFileSize(doc.totalFileSize)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                val qualityTier = QualityTier.entries.getOrNull(doc.qualityTier)
                                if (qualityTier != null) {
                                    Text(
                                        "Quality: ${qualityTier.label}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Text(
                                    if (doc.ocrComplete) "OCR: Complete"
                                    else "OCR: Pending",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (doc.ocrComplete)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.tertiary,
                                )
                                if (state.documentTags.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Tags", style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.height(4.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        state.documentTags.forEach { tag ->
                                            val chipColor = TagColors.getOrElse(tag.colorIndex) { TagColors[0] }
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(chipColor.copy(alpha = 0.15f))
                                                    .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(chipColor),
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    tag.name,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = chipColor,
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.showTagsSheet() }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Add tags",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Exclude from sync", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = state.syncExcluded,
                                        onCheckedChange = { viewModel.toggleSyncExclude(it) },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Pages", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                val lazyGridState = rememberLazyGridState()
                val reorderableState = rememberReorderableLazyGridState(
                    lazyGridState = lazyGridState,
                    onMove = { from, to ->
                        viewModel.reorderLocally(from.index, to.index)
                    },
                )

                val verticalSpacing = if (state.isEditMode) 24.dp else 12.dp

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(164.dp),
                    state = lazyGridState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                    contentPadding = PaddingValues(4.dp),
                ) {
                        items(state.reorderablePages, key = { it.id }) { page ->
                            ReorderableItem(reorderableState, key = page.id) { _ ->
                                val index = state.reorderablePages.indexOf(page)
                                val itemModifier = if (state.isEditMode) Modifier.draggableHandle() else Modifier
                                PageThumbnail(
                                    imageUri = page.imageUri,
                                    pageNumber = index + 1,
                                    ocrText = page.ocrText,
                                    isEditMode = state.isEditMode,
                                    isMarkedForDeletion = page.filename in state.markedForDeletion,
                                    showRescan = !state.isEditMode && !state.ocrRunning && !state.showRescanProgress,
                                    onDelete = { viewModel.toggleMarkForDeletion(page.filename) },
                                    onView = { onPageView(documentId, index) },
                                    onRescan = {
                                        activity?.let { viewModel.beginRescan(it, index + 1) }
                                    },
                                    modifier = itemModifier,
                                )
                            }
                        }
                }
            }
        }
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
                TextButton(onClick = { viewModel.renameDocument() }) {
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

    if (state.showEmptyDeleteDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelEmptyDelete() },
            title = { Text("Delete document?") },
            text = { Text("Removing all pages will delete this document.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmEmptyDelete()
                    onNavigateBack()
                }) {
                    Text("Delete Document", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelEmptyDelete() }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            title = { Text("Delete document?") },
            text = {
                Text("This document and all its pages will be permanently deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDocument()
                    onNavigateBack()
                }) {
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
            onDone = { viewModel.applyTags() },
            onDismiss = { viewModel.hideTagsSheet() },
        )
    }

    if (state.showShareSheet) {
        val ctx = LocalContext.current
        ShareOptionsSheet(
            onDismiss = { viewModel.hideShareSheet() },
            onShareVia = {
                viewModel.hideShareSheet()
                viewModel.shareViaSystem(ctx)
            },
            onSaveToDrive = { uri ->
                viewModel.hideShareSheet()
                viewModel.saveToDrive(uri)
            },
        )
    }

    if (state.showRescanProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Rescanning page") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Processing new image...")
                }
            },
            confirmButton = {},
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
                    exportPdfLauncher.launch("${state.document?.name?.replace(" ", "_") ?: "document"}.pdf")
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

    if (state.showCollateDialog) {
        CollateEditorScreen(state = state, viewModel = viewModel)
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageThumbnail(
    imageUri: String,
    pageNumber: Int,
    ocrText: String? = null,
    isEditMode: Boolean = false,
    isMarkedForDeletion: Boolean = false,
    showRescan: Boolean = false,
    onDelete: () -> Unit = {},
    onView: () -> Unit = {},
    onRescan: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.7f),
    ) {
        Box {
            SubcomposeAsyncImage(
                model = imageUri,
                contentDescription = "Page $pageNumber",
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (!isEditMode) Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = null,
                            onDoubleClick = onView,
                        ) else Modifier
                    ),
                contentScale = ContentScale.Fit,
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Failed to load", style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
            if (isMarkedForDeletion) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                )
            }
            Text(
                "Page $pageNumber",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
            if (ocrText == null) {
                Text(
                    "No OCR",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (showRescan) {
                IconButton(
                    onClick = onRescan,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "Rescan page",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (isEditMode) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Long press to reorder",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp)
                        .background(
                            if (isMarkedForDeletion) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                            shape = MaterialTheme.shapes.small,
                        ),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete page",
                        tint = if (isMarkedForDeletion) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy  HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}

