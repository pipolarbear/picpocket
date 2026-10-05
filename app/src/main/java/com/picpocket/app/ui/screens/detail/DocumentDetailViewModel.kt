package com.picpocket.app.ui.screens.detail

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.Tag
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.di.SearchablePdf
import com.picpocket.app.domain.collate.Collate
import com.picpocket.app.domain.collate.CollateAxis
import com.picpocket.app.domain.collate.CollateLayout
import com.picpocket.app.domain.collate.CollatePlacement
import com.picpocket.app.domain.ocr.OcrManager
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.export.PdfGenerator
import com.picpocket.app.domain.render.PageRenderer
import com.picpocket.app.domain.render.PageThumbCache
import com.picpocket.app.domain.scanner.ScannerManager
import com.picpocket.app.domain.scanner.ScannerResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import javax.inject.Inject

private const val COLLATE_RENDER_WIDTH = 1600

data class DetailUiState(
    val document: Document? = null,
    val pages: List<Page> = emptyList(),
    val reorderablePages: List<Page> = emptyList(),
    val isLoading: Boolean = true,
    val showInfoPane: Boolean = false,
    val previousInfoPaneState: Boolean = false,
    val showRenameDialog: Boolean = false,
    val renameText: String = "",
    val isEditMode: Boolean = false,
    val markedForDeletion: Set<String> = emptySet(),
    val showDeleteConfirmation: Boolean = false,
    val showEmptyDeleteDialog: Boolean = false,
    val showTagsSheet: Boolean = false,
    val selectedTagIds: Set<Long> = emptySet(),
    val documentTags: List<Tag> = emptyList(),
    val showShareSheet: Boolean = false,
    val showOverflowMenu: Boolean = false,
    val showExportDialog: Boolean = false,
    val exportPageSize: PageSize = PageSize.A4,
    val showRenameOverwriteDialog: Boolean = false,
    val renameOverwriteTargetName: String = "",
    val showRescanProgress: Boolean = false,
    val rescanPageNumber: Int? = null,
    val pendingRescanIntentSender: IntentSender? = null,
    val ocrRunning: Boolean = false,
    val syncExcluded: Boolean = false,
    val showCollateDialog: Boolean = false,
    val collateSelection: Set<Int> = emptySet(),
    val collateOrder: List<Int> = emptyList(),
    val collateLayout: CollateLayout = CollateLayout.AUTO,
    val collateMatchSizes: Boolean = true,
    val collateRemoveSources: Boolean = false,
    val collateBusy: Boolean = false,
    val collateError: String? = null,
    val collatePreview: android.graphics.Bitmap? = null,
    val collateAxis: CollateAxis = CollateAxis.VERTICAL,
    val collateJoints: List<Int> = emptyList(),
    val collateResetToken: Int = 0,
)


@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    application: Application,
    private val repository: DocumentRepository,
    private val documentStore: DocumentStore,
    @SearchablePdf private val searchablePdfGenerator: PdfGenerator,
    private val ocrManager: OcrManager,
    private val scannerManager: ScannerManager,
    private val pageRenderer: PageRenderer = PageRenderer(),
    private val pageThumbCache: PageThumbCache? = null,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val prefs = getApplication<Application>().getSharedPreferences("settings", 0)

    private var currentDocumentId: DocumentId = ""

    private val _rescanEvents = MutableSharedFlow<RescanEvent>()
    val rescanEvents = _rescanEvents.asSharedFlow()

    sealed interface RescanEvent {
        data class ShowError(val message: String) : RescanEvent
    }

    private val _allTags = repository.observeAllTags()
    val allTags: StateFlow<List<Tag>> = _allTags.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    fun loadDocument(documentId: DocumentId) {
        currentDocumentId = documentId
        viewModelScope.launch { repository.markDocumentAccessed(documentId) }
        viewModelScope.launch {
            repository.observeDocument(documentId).collect { doc ->
                _uiState.update { it.copy(document = doc) }
                if (doc != null && !doc.ocrComplete) {
                    _uiState.update { it.copy(ocrRunning = true) }
                    launch {
                        ocrManager.runOcr(documentId)
                        _uiState.update { it.copy(ocrRunning = false) }
                    }
                } else if (doc != null) {
                    _uiState.update { it.copy(ocrRunning = false) }
                }
            }
        }
        viewModelScope.launch {
            val initialPages = repository.observePages(documentId).first()
            _uiState.update { it.copy(pages = initialPages, reorderablePages = initialPages) }

            repository.observePages(documentId).collect { pages ->
                val current = _uiState.value
                if (!current.isEditMode || current.reorderablePages.isEmpty()) {
                    _uiState.update { it.copy(pages = pages, reorderablePages = pages, isLoading = false) }
                } else {
                    _uiState.update { it.copy(pages = pages, isLoading = false) }
                }
            }
        }
        viewModelScope.launch {
            repository.observeDocumentTags(documentId).collect { tags ->
                _uiState.update { it.copy(documentTags = tags) }
            }
        }
        viewModelScope.launch {
            val doc = documentStore.readMetadata(documentId).getOrNull()
            _uiState.update { it.copy(syncExcluded = doc?.syncExclude ?: false) }
        }
    }

    fun toggleSyncExclude(excluded: Boolean) {
        viewModelScope.launch {
            documentStore.updateSyncExclude(currentDocumentId, excluded)
            _uiState.update { it.copy(syncExcluded = excluded) }
        }
    }

    fun toggleInfoPane() {
        _uiState.update { it.copy(showInfoPane = !it.showInfoPane) }
    }

    fun showTagsSheet() {
        _uiState.update { it.copy(
            showTagsSheet = true,
            selectedTagIds = it.documentTags.map { it.id }.toSet(),
        ) }
    }

    fun hideTagsSheet() {
        _uiState.update { it.copy(showTagsSheet = false) }
    }

    fun toggleTag(tagId: Long) {
        _uiState.update { state ->
            val newSet = if (tagId in state.selectedTagIds) {
                state.selectedTagIds - tagId
            } else {
                state.selectedTagIds + tagId
            }
            state.copy(selectedTagIds = newSet)
        }
    }

    fun createTagAndSelect(name: String) {
        viewModelScope.launch {
            val tagId = repository.createTag(name)
            _uiState.update { it.copy(selectedTagIds = it.selectedTagIds + tagId) }
        }
    }

    fun applyTags() {
        val docId = currentDocumentId
        if (docId.isEmpty()) return
        val tagIds = _uiState.value.selectedTagIds.toList()
        viewModelScope.launch {
            repository.setDocumentTags(docId, tagIds)
            _uiState.update { it.copy(showTagsSheet = false) }
        }
    }

    fun showRenameDialog() {
        _uiState.update {
            it.copy(
                showRenameDialog = true,
                renameText = it.document?.name.orEmpty(),
            )
        }
    }

    fun hideRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = false) }
    }

    fun updateRenameText(text: String) {
        _uiState.update { it.copy(renameText = text) }
    }

    fun renameDocument() {
        val docId = _uiState.value.document?.id ?: return
        val name = _uiState.value.renameText
        if (name.isBlank()) return
        viewModelScope.launch {
            val existing = repository.getDocumentsByName(name).getOrNull() ?: emptyList()
            val conflict = existing.firstOrNull { it.id != docId }
            if (conflict != null) {
                _uiState.update { it.copy(showRenameOverwriteDialog = true, renameOverwriteTargetName = name) }
                return@launch
            }
            repository.updateDocumentName(docId, name)
            hideRenameDialog()
        }
    }

    fun confirmRenameOverwrite() {
        val docId = _uiState.value.document?.id ?: return
        val name = _uiState.value.renameOverwriteTargetName
        if (name.isBlank()) return
        _uiState.update { it.copy(showRenameOverwriteDialog = false) }
        viewModelScope.launch {
            repository.deleteDocumentsByName(name)
            repository.updateDocumentName(docId, name)
            hideRenameDialog()
        }
    }

    fun cancelRenameOverwrite() {
        _uiState.update { it.copy(showRenameOverwriteDialog = false) }
    }

    fun toggleEditMode() {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.isEditMode) {
            val docId = currentDocumentId
            val keptFilenames = state.reorderablePages
                .map { it.filename }
                .filter { it !in state.markedForDeletion }

            if (keptFilenames.isEmpty()) {
                _uiState.update { it.copy(showEmptyDeleteDialog = true) }
                return
            }

            viewModelScope.launch {
                repository.replacePages(docId, keptFilenames)
                _uiState.update {
                    it.copy(
                        isEditMode = false,
                        markedForDeletion = emptySet(),
                        showInfoPane = it.previousInfoPaneState,
                    )
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    isEditMode = true,
                    previousInfoPaneState = it.showInfoPane,
                    showInfoPane = false,
                )
            }
        }
    }

    fun toggleMarkForDeletion(filename: String) {
        _uiState.update { state ->
            val newSet = if (filename in state.markedForDeletion) {
                state.markedForDeletion - filename
            } else {
                state.markedForDeletion + filename
            }
            state.copy(markedForDeletion = newSet)
        }
    }

    fun reorderLocally(fromIndex: Int, toIndex: Int) {
        val pages = _uiState.value.reorderablePages.toMutableList()
        if (fromIndex < 0 || fromIndex >= pages.size) return
        if (toIndex < 0 || toIndex >= pages.size) return
        val item = pages.removeAt(fromIndex)
        pages.add(toIndex, item)
        _uiState.update { it.copy(reorderablePages = pages) }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun deleteDocument() {
        val docId = _uiState.value.document?.id ?: return
        viewModelScope.launch {
            repository.deleteDocument(docId)
        }
    }

    fun confirmEmptyDelete() {
        _uiState.update { it.copy(showEmptyDeleteDialog = false) }
        deleteDocument()
    }

    fun cancelEmptyDelete() {
        _uiState.update { it.copy(showEmptyDeleteDialog = false) }
    }

    fun movePage(pageId: Long, newIndex: Int) {
        val docId = currentDocumentId
        if (docId.isEmpty()) return
        val pages = _uiState.value.pages.toMutableList()
        val currentIndex = pages.indexOfFirst { it.id == pageId }
        if (currentIndex < 0) return
        val page = pages.removeAt(currentIndex)
        pages.add(newIndex, page)
        viewModelScope.launch {
            repository.reorderPages(docId, pages.map { it.pageNumber })
        }
    }

    fun reorderPages(pageIds: List<Long>) {
        val docId = currentDocumentId
        if (docId.isEmpty()) return
        viewModelScope.launch {
            val pages = _uiState.value.pages
            val pageNumbers = pageIds.mapNotNull { id -> pages.find { it.id == id }?.pageNumber }
            repository.reorderPages(docId, pageNumbers)
        }
    }

    fun toggleOverflowMenu() {
        _uiState.update { it.copy(showOverflowMenu = !it.showOverflowMenu) }
    }

    fun hideOverflowMenu() {
        _uiState.update { it.copy(showOverflowMenu = false) }
    }

    fun showShareSheet() {
        _uiState.update { it.copy(showShareSheet = true) }
    }

    fun hideShareSheet() {
        _uiState.update { it.copy(showShareSheet = false) }
    }

    fun showExportDialog() {
        val doc = _uiState.value.document ?: return
        val docPageSize = doc.pageSize?.let { try { PageSize.valueOf(it) } catch (_: Exception) { null } }
        val pageSize = docPageSize ?: prefs.getString("page_size", PageSize.A4.name)?.let { try { PageSize.valueOf(it) } catch (_: Exception) { null } } ?: PageSize.A4
        _uiState.update { it.copy(showExportDialog = true, exportPageSize = pageSize) }
    }

    fun hideExportDialog() {
        _uiState.update { it.copy(showExportDialog = false) }
    }

    fun setExportPageSize(pageSize: PageSize) {
        _uiState.update { it.copy(exportPageSize = pageSize) }
    }

    fun exportPdf(context: Context, outputUri: Uri) {
        val docId = currentDocumentId
        val pageSize = _uiState.value.exportPageSize
        if (docId.isEmpty()) return
        viewModelScope.launch {
            try {
                val pages = repository.getPages(docId).getOrNull() ?: emptyList()
                val result = searchablePdfGenerator.generate(context, pages, outputUri, pageSize)
                when (result) {
                    is com.picpocket.app.domain.export.PdfResult.Success -> {
                        hideExportDialog()
                    }
                    is com.picpocket.app.domain.export.PdfResult.Error -> { }
                }
            } catch (_: Exception) { }
        }
    }

    fun rescanPage(pageNumber: Int, imageUri: String) {
        val docId = currentDocumentId
        if (docId.isEmpty()) return
        _uiState.update { it.copy(showRescanProgress = true, rescanPageNumber = pageNumber) }
        viewModelScope.launch {
            repository.rescanPage(docId, pageNumber, imageUri)
                .onSuccess {
                    _uiState.update { it.copy(showRescanProgress = false, rescanPageNumber = null) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(showRescanProgress = false, rescanPageNumber = null) }
                    _rescanEvents.emit(RescanEvent.ShowError(error.message ?: "Rescan failed"))
                }
        }
    }

    fun beginRescan(activity: Activity, pageNumber: Int) {
        val docId = currentDocumentId
        if (docId.isEmpty()) return
        viewModelScope.launch {
            try {
                val sender = scannerManager.getStartScanIntentSender(activity, pageLimit = 1)
                _uiState.update {
                    it.copy(pendingRescanIntentSender = sender, rescanPageNumber = pageNumber)
                }
            } catch (e: Exception) {
                _rescanEvents.emit(RescanEvent.ShowError(e.message ?: "Scanner unavailable"))
            }
        }
    }

    fun clearPendingRescanIntentSender() {
        _uiState.update { it.copy(pendingRescanIntentSender = null) }
    }

    fun handleRescanScannerResult(pageNumber: Int, data: Intent?) {
        viewModelScope.launch {
            when (val result = scannerManager.handleResult(data)) {
                is ScannerResult.PageCaptured -> {
                    rescanPage(pageNumber, result.imageUri.toString())
                }
                is ScannerResult.MultiplePagesCaptured -> {
                    if (result.imageUris.size == 1) {
                        rescanPage(pageNumber, result.imageUris.first().toString())
                    } else {
                        _rescanEvents.emit(
                            RescanEvent.ShowError("Rescan accepts a single image only")
                        )
                    }
                }
                is ScannerResult.Cancelled -> {
                    _uiState.update { it.copy(rescanPageNumber = null) }
                }
                is ScannerResult.Error -> {
                    _uiState.update { it.copy(rescanPageNumber = null) }
                    _rescanEvents.emit(RescanEvent.ShowError(result.exception.message ?: "Rescan failed"))
                }
            }
        }
    }

    fun shareViaSystem(context: Context) {
        val doc = _uiState.value.document ?: return
        val docId = doc.id
        viewModelScope.launch {
            val pdfUri = generatePdfToTempFile(docId) ?: return@launch
            val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(pdfUri.path!!))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share PDF"))
        }
    }

    fun saveToDrive(folderUri: Uri) {
        val doc = _uiState.value.document ?: return
        val docId = doc.id
        viewModelScope.launch {
            val pdfUri = generatePdfToTempFile(docId) ?: return@launch
            try {
                val app = getApplication<Application>()
                val safeName = doc.name.replace(" ", "_").replace("/", "_") + ".pdf"
                app.contentResolver.openInputStream(pdfUri)?.use { input ->
                    app.contentResolver.openOutputStream(folderUri)?.use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private suspend fun generatePdfToTempFile(documentId: DocumentId): Uri? {
        val pages = repository.getPages(documentId).getOrNull() ?: return null
        if (pages.isEmpty()) return null
        val app = getApplication<Application>()
        val tempDir = File(app.cacheDir, "exports")
        tempDir.mkdirs()
        val tempFile = File(tempDir, "${documentId}.pdf")
        val doc = _uiState.value.document
        val docPageSize = doc?.pageSize?.let { try { PageSize.valueOf(it) } catch (_: Exception) { null } }
        val pageSize = docPageSize ?: prefs.getString("page_size", PageSize.A4.name)?.let { try { PageSize.valueOf(it) } catch (_: Exception) { null } } ?: PageSize.A4
        val result = searchablePdfGenerator.generate(app, pages, Uri.fromFile(tempFile), pageSize)
        return when (result) {
            is com.picpocket.app.domain.export.PdfResult.Success -> Uri.parse(result.uri)
            is com.picpocket.app.domain.export.PdfResult.Error -> null
        }
    }

    // --- Collate: merge selected pages into one continuous image ---

    /** Renders a page for list display (PDF pages need on-demand rendering). */
    suspend fun pageThumbnail(page: Page): Bitmap? {
        val cache = pageThumbCache
        if (cache != null) return cache.thumbnail(page)
        return pageRenderer.render(
            File(java.net.URI(page.imageUri)),
            page.kind,
            page.pdfPageIndex,
            400,
        )
    }

    private var collateSources: List<Bitmap> = emptyList()
    private var collatePlacements: List<CollatePlacement> = emptyList()
    private var collateAxis: CollateAxis = CollateAxis.VERTICAL

    fun showCollateDialog() {
        if (_uiState.value.pages.size < 2) return
        val allPages = _uiState.value.pages.map { it.pageNumber }
        collateSources = emptyList()
        collatePlacements = emptyList()
        _uiState.update {
            it.copy(
                showCollateDialog = true,
                collateSelection = allPages.toSet(),
                collateOrder = allPages,
                collateLayout = CollateLayout.AUTO,
                collateMatchSizes = true,
                collateRemoveSources = false,
                collateBusy = false,
                collateError = null,
                collatePreview = null,
            )
        }
    }

    fun hideCollateDialog() =
        _uiState.update { it.copy(showCollateDialog = false, collatePreview = null, collateError = null) }

    fun toggleCollateSelection(pageNumber: Int) = _uiState.update { state ->
        if (pageNumber in state.collateSelection) {
            state.copy(
                collateSelection = state.collateSelection - pageNumber,
                collateOrder = state.collateOrder - pageNumber,
            )
        } else {
            state.copy(
                collateSelection = state.collateSelection + pageNumber,
                collateOrder = state.collateOrder + pageNumber,
            )
        }
    }

    fun selectAllCollate() = _uiState.update { state ->
        val all = state.pages.map { it.pageNumber }
        state.copy(collateSelection = all.toSet(), collateOrder = all)
    }

    fun clearCollateSelection() = _uiState.update {
        it.copy(collateSelection = emptySet(), collateOrder = emptyList())
    }

    fun setCollateLayout(layout: CollateLayout) {
        _uiState.update { it.copy(collateLayout = layout) }
        if (_uiState.value.collatePreview != null) buildCollatePreview()
    }

    fun setCollateMatchSizes(match: Boolean) {
        _uiState.update { it.copy(collateMatchSizes = match) }
        if (_uiState.value.collatePreview != null) buildCollatePreview()
    }

    fun setCollateRemoveSources(remove: Boolean) = _uiState.update { it.copy(collateRemoveSources = remove) }

    /** Reorders the selected sources; re-aligns the preview if one is shown. */
    fun moveCollateSource(from: Int, to: Int) {
        _uiState.update { state ->
            val order = state.collateOrder.toMutableList()
            if (from !in order.indices || to !in order.indices) return@update state
            val item = order.removeAt(from)
            order.add(to, item)
            state.copy(collateOrder = order)
        }
        if (_uiState.value.collatePreview != null) buildCollatePreview()
    }

    /** Loads the selected pages, normalizes their sizes, aligns them, and shows a preview. */
    fun buildCollatePreview() {
        val state = _uiState.value
        val order = state.collateOrder.ifEmpty { state.pages.map { it.pageNumber } }
        val selected = order.filter { it in state.collateSelection }
            .mapNotNull { number -> state.pages.find { it.pageNumber == number } }
        if (selected.size < 2) {
            _uiState.update { it.copy(collateError = "Select at least two pages") }
            return
        }
        _uiState.update { it.copy(collateBusy = true, collateError = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val bitmaps = selected.mapNotNull { page ->
                        pageRenderer.render(
                            File(java.net.URI(page.imageUri)),
                            page.kind,
                            page.pdfPageIndex,
                            COLLATE_RENDER_WIDTH,
                        )
                    }
                    if (bitmaps.size != selected.size) return@runCatching null
                    val resolved = Collate.place(bitmaps, state.collateLayout)
                    if (resolved is Collate.PlacementResult.Failure) return@runCatching resolved
                    val axis = (resolved as Collate.PlacementResult.Success).axis
                    val normalized = if (state.collateMatchSizes) Collate.normalize(bitmaps, axis) else bitmaps
                    val placed = Collate.place(normalized, state.collateLayout)
                    if (placed is Collate.PlacementResult.Failure) return@runCatching placed
                    Triple(normalized, placed as Collate.PlacementResult.Success, state.collateLayout)
                }.getOrNull()
            }
            when (outcome) {
                null ->
                    _uiState.update { it.copy(collateBusy = false, collateError = "Could not load the selected pages") }
                is Collate.PlacementResult.Failure ->
                    _uiState.update { it.copy(collateBusy = false, collateError = outcome.reason) }
                is Triple<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val bitmaps = outcome.first as List<Bitmap>
                    val placed = outcome.second as Collate.PlacementResult.Success
                    val layout = outcome.third as CollateLayout
                    collateSources = bitmaps
                    collatePlacements = placed.placements
                    collateAxis = placed.axis
                    val preview = withContext(Dispatchers.Default) { Collate.compose(bitmaps, placed.placements) }
                    _uiState.update {
                        it.copy(
                            collateBusy = false,
                            collatePreview = preview,
                            collateLayout = layout,
                            collateAxis = placed.axis,
                            collateJoints = jointPositions(placed.placements),
                        )
                    }
                }
            }
        }
    }

    /** Returns to the freshly-aligned state: clears seam drags, re-aligns, resets zoom. */
    fun resetCollate() {
        collatePlacements = emptyList()
        _uiState.update { it.copy(collateResetToken = it.collateResetToken + 1) }
        buildCollatePreview()
    }

    /**
     * Manual correction: dragging the joint before source [jointIndex] + 1 shifts
     * every source after that joint along the stitch axis and re-composites.
     */
    fun nudgeCollateJoint(jointIndex: Int, delta: Int) {
        if (collateSources.size < 2 || collatePlacements.isEmpty()) return
        if (jointIndex !in 0 until collateSources.size - 1) return
        collatePlacements = Collate.shiftTail(collatePlacements, collateAxis, jointIndex + 1, delta)
        recomposeCollatePreview()
    }

    private fun recomposeCollatePreview() {
        if (collateSources.isEmpty() || collatePlacements.isEmpty()) return
        val placements = collatePlacements
        viewModelScope.launch {
            val preview = withContext(Dispatchers.Default) { Collate.compose(collateSources, placements) }
            _uiState.update { it.copy(collatePreview = preview, collateJoints = jointPositions(placements)) }
        }
    }

    private fun jointPositions(placements: List<CollatePlacement>): List<Int> =
        (1 until placements.size).map { index ->
            if (collateAxis == CollateAxis.VERTICAL) placements[index].y else placements[index].x
        }

    fun saveCollate() {
        val state = _uiState.value
        val preview = state.collatePreview ?: return
        val selected = state.collateOrder.filter { it in state.collateSelection }
        if (selected.size < 2) return
        _uiState.update { it.copy(collateBusy = true) }
        viewModelScope.launch {
            val uri = withContext(Dispatchers.Default) {
                runCatching {
                    val app = getApplication<Application>()
                    val dir = File(app.cacheDir, "collate").apply { mkdirs() }
                    val out = File(dir, "merged_${System.currentTimeMillis()}.jpg")
                    out.outputStream().use { preview.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    Uri.fromFile(out).toString()
                }.getOrNull()
            }
            if (uri == null) {
                _uiState.update { it.copy(collateBusy = false, collateError = "Could not save the merged image") }
                return@launch
            }
            val result = repository.collatePages(
                documentId = currentDocumentId,
                sourcePageNumbers = selected,
                mergedImageUri = uri,
                removeSources = state.collateRemoveSources,
            )
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        collateBusy = false,
                        showCollateDialog = false,
                        collatePreview = null,
                        collateSelection = emptySet(),
                        collateOrder = emptyList(),
                    )
                }
            } else {
                _uiState.update {
                    it.copy(collateBusy = false, collateError = result.exceptionOrNull()?.message ?: "Collate failed")
                }
            }
        }
    }
}
