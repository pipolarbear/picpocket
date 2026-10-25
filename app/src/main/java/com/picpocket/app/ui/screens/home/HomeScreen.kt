package com.picpocket.app.ui.screens.home

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.FileOpen
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.model.Tag
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.R
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
    onWorkflowsClick: () -> Unit = {},
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
                is HomeViewModel.ImportEvent.ShowMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
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
                    Column {
                        Text(
                            if (state.selectionMode) {
                                "${state.selectedDocumentIds.size} selected"
                            } else {
                                "PicPocket"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                        )
                        if (!state.selectionMode) {
                            Text(
                                "Your documents, pocketed.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
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
                            Icon(Icons.Default.FileOpen, contentDescription = "Import PDF")
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.selectionMode && state.documents.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onScanClick,
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 6.dp,
                        pressedElevation = 10.dp,
                    ),
                ) {
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
                                Icons.AutoMirrored.Filled.TextSnippet,
                                contentDescription = if (state.searchInContent) "Search names only"
                                else "Search in content",
                                tint = if (state.searchInContent)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
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
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

            if (state.deferredCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${state.deferredCount} workflow run(s) waiting for network",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onWorkflowsClick) { Text("View") }
                }
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading...")
                }
            } else if (state.documents.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(104.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.secondaryContainer,
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(82.dp),
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            if (state.searchQuery.isNotEmpty()) "No documents match your search"
                            else "No documents yet",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (state.searchQuery.isNotEmpty()) {
                                "Try a different name or filter."
                            } else {
                                "Scan a document to get started.\nEverything stays on this device."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (state.searchQuery.isBlank()) {
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = onScanClick,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary,
                                ),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Scan a document")
                            }
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
                            loadThumbnail = viewModel::thumbnail,
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

    if (state.savingToShareTarget) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Saving to Drive") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Writing the PDF…")
                }
            },
            confirmButton = {},
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
    loadThumbnail: suspend (Document) -> Bitmap?,
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
        shape = RoundedCornerShape(24.dp),
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 0.dp else 1.dp),
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
            DocumentThumbnail(document = document, load = loadThumbnail)
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
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDate(document.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.take(3).forEach { tag ->
                            val dotColor = TagColors.getOrElse(tag.colorIndex) { TagColors[0] }
                            Box(
                                modifier = Modifier
                                    .background(dotColor.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentThumbnail(
    document: Document,
    load: suspend (Document) -> Bitmap?,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, document.id, document.updatedAt) {
        value = load(document)
    }
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
