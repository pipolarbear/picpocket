package com.picpocket.app.ui.screens.home

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Tag
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.di.SearchablePdf
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.export.PdfGenerator
import com.picpocket.app.ui.components.MatchMode
import com.picpocket.app.util.ZipUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class SortOrder(val label: String) {
    LAST_SEEN_DESC("Last seen"),
    MODIFIED_DESC("Last modified"),
    CREATED_DESC("Created"),
    SIZE_DESC("Size"),
    NAME_ASC("Name"),
}

private const val KEY_SORT_ORDER = "home_sort_order"
private const val KEY_SORT_REVERSED = "home_sort_reversed"

data class HomeUiState(
    val documents: List<Document> = emptyList(),
    val isLoading: Boolean = true,
    val showImportProgress: Boolean = false,
    val importErrorMessage: String? = null,
    val selectionMode: Boolean = false,
    val selectedDocumentIds: Set<DocumentId> = emptySet(),
    val showDeleteConfirmation: Boolean = false,
    val sortOrder: SortOrder = SortOrder.LAST_SEEN_DESC,
    val sortReversed: Boolean = false,
    val showRenameDialog: Boolean = false,
    val renameText: String = "",
    val searchQuery: String = "",
    val searchInContent: Boolean = false,
    val showTagsSheet: Boolean = false,
    val selectedTagIds: Set<Long> = emptySet(),
    val documentTags: Map<DocumentId, List<Tag>> = emptyMap(),
    val showTagFilterSheet: Boolean = false,
    val filterTagIds: Set<Long> = emptySet(),
    val filterMatchMode: MatchMode = MatchMode.MATCH_ANY,
    val showShareSheet: Boolean = false,
    val shareFilteredDocs: Boolean = false,
    val showExportDialog: Boolean = false,
    val exportPageSize: PageSize = PageSize.A4,
    val pendingExportDocIds: List<DocumentId> = emptyList(),
    val showRenameOverwriteDialog: Boolean = false,
    val renameOverwriteTargetName: String = "",
)

@HiltViewModel
@OptIn(FlowPreview::class)
class HomeViewModel @Inject constructor(
    application: Application,
    private val repository: DocumentRepository,
    @SearchablePdf private val pdfGenerator: PdfGenerator,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val _sortOrder = MutableStateFlow(readSortOrder())
    private val _sortReversed = MutableStateFlow(readSortReversed())
    private val _searchQuery = MutableStateFlow("")
    private val _searchInContent = MutableStateFlow(false)
    private val _ocrMatchIds = MutableStateFlow<Set<DocumentId>>(emptySet())
    private val _debouncedQuery = _searchQuery.debounce(300)
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _filterTagIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _filterMatchMode = MutableStateFlow(MatchMode.MATCH_ANY)

    private val _importEvents = MutableSharedFlow<ImportEvent>()
    val importEvents = _importEvents.asSharedFlow()

    sealed interface ImportEvent {
        data class NavigateToDocument(val documentId: DocumentId) : ImportEvent
        data class ShowError(val message: String) : ImportEvent
    }

    private val _allTags = repository.observeAllTags()
    val allTags: StateFlow<List<Tag>> = _allTags.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    private val _documentTagMap = repository.observeDocumentTagMap().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyMap(),
    )

    init {
        viewModelScope.launch {
            _documentTagMap.collect { map ->
                _uiState.update { it.copy(documentTags = map) }
            }
        }

        viewModelScope.launch {
            combine(_searchInContent, _debouncedQuery) { a, b -> a to b }
                .collectLatest { (inContent, query) ->
                    if (inContent && query.isNotBlank()) {
                        _ocrMatchIds.value = repository.searchDocumentsByOcrText(query).getOrNull() ?: emptySet()
                    } else {
                        _ocrMatchIds.value = emptySet()
                    }
                }
        }

        viewModelScope.launch {
            combine(
                repository.observeDocuments(),
                combine(_sortOrder, _sortReversed) { order, reversed -> order to reversed },
                _debouncedQuery,
                _searchInContent,
                _ocrMatchIds,
            ) { docs, sort, query, inContent, ocrIds ->
                listOf(docs, sort, query, inContent, ocrIds)
            }
            .combine(
                combine(_filterTagIds, _filterMatchMode, _documentTagMap) { a, b, c -> listOf(a, b, c) }
            ) { main, filter ->
                val docs = main[0] as List<Document>
                @Suppress("UNCHECKED_CAST")
                val sort = main[1] as Pair<SortOrder, Boolean>
                val sortOrder = sort.first
                val reversed = sort.second
                val query = main[2] as String
                val inContent = main[3] as Boolean
                val ocrIds = main[4] as Set<*>
                val filterIds = filter[0] as Set<*>
                val matchMode = filter[1] as MatchMode
                val tagMap = filter[2] as Map<*, *>

                val filtered = if (query.isBlank()) docs
                else {
                    val nameMatch = docs.filter {
                        try {
                            Regex(query, RegexOption.IGNORE_CASE).containsMatchIn(it.name)
                        } catch (_: Exception) { false }
                    }
                    if (inContent) (nameMatch + docs.filter { it.id in ocrIds }).distinctBy { it.id }
                    else nameMatch
                }
                val tagIds = filterIds as Set<Long>
                val tagFiltered = if (tagIds.isEmpty()) filtered
                else {
                    @Suppress("UNCHECKED_CAST")
                    val dtm = tagMap as Map<DocumentId, List<Tag>>
                    filtered.filter { doc ->
                        val docTagIds = dtm[doc.id].orEmpty().map { it.id }.toSet()
                        if (matchMode == MatchMode.MATCH_ALL) tagIds.all { it in docTagIds }
                        else tagIds.any { it in docTagIds }
                    }
                }
                val sorted = when (sortOrder) {
                    SortOrder.LAST_SEEN_DESC -> tagFiltered.sortedByDescending { it.lastAccessedAt }
                    SortOrder.MODIFIED_DESC -> tagFiltered.sortedByDescending { it.updatedAt }
                    SortOrder.CREATED_DESC -> tagFiltered.sortedByDescending { it.createdAt }
                    SortOrder.SIZE_DESC -> tagFiltered.sortedByDescending { it.totalFileSize }
                    SortOrder.NAME_ASC -> tagFiltered.sortedBy { it.name.lowercase() }
                }
                val ordered = if (reversed) sorted.reversed() else sorted
                Triple(ordered, sortOrder, reversed)
            }.collect { (sorted, sortOrder, reversed) ->
                _uiState.update { state ->
                    state.copy(
                        documents = sorted,
                        sortOrder = sortOrder,
                        sortReversed = reversed,
                        isLoading = false,
                        selectedDocumentIds = if (state.selectionMode) {
                            state.selectedDocumentIds.filter { id ->
                                sorted.any { it.id == id }
                            }.toSet()
                        } else emptySet(),
                    )
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleSearchInContent() {
        val new = !_searchInContent.value
        _searchInContent.value = new
        _uiState.update { it.copy(searchInContent = new) }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        _uiState.update { it.copy(sortOrder = order) }
        getApplication<Application>().getSharedPreferences("settings", 0)
            .edit().putString(KEY_SORT_ORDER, order.name).apply()
    }

    fun toggleSortReversed() {
        val reversed = !_sortReversed.value
        _sortReversed.value = reversed
        _uiState.update { it.copy(sortReversed = reversed) }
        getApplication<Application>().getSharedPreferences("settings", 0)
            .edit().putBoolean(KEY_SORT_REVERSED, reversed).apply()
    }

    private fun readSortOrder(): SortOrder {
        val stored = getApplication<Application>()
            .getSharedPreferences("settings", 0)
            .getString(KEY_SORT_ORDER, null)
        return stored?.let { name -> SortOrder.entries.find { it.name == name } }
            ?: SortOrder.LAST_SEEN_DESC
    }

    private fun readSortReversed(): Boolean =
        getApplication<Application>()
            .getSharedPreferences("settings", 0)
            .getBoolean(KEY_SORT_REVERSED, false)

    fun onDocumentLongPress(documentId: DocumentId) {
        _uiState.update {
            it.copy(
                selectionMode = true,
                selectedDocumentIds = setOf(documentId),
            )
        }
    }

    fun onDocumentTap(documentId: DocumentId): Boolean {
        val state = _uiState.value
        return if (state.selectionMode) {
            toggleSelection(documentId)
            false
        } else {
            true
        }
    }

    fun toggleSelection(documentId: DocumentId) {
        _uiState.update { state ->
            val newSelection = if (documentId in state.selectedDocumentIds) {
                state.selectedDocumentIds - documentId
            } else {
                state.selectedDocumentIds + documentId
            }
            state.copy(
                selectedDocumentIds = newSelection,
                selectionMode = newSelection.isNotEmpty(),
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(
                selectedDocumentIds = state.documents.map { it.id }.toSet(),
            )
        }
    }

    fun toggleSelectAll() {
        val state = _uiState.value
        val allIds = state.documents.map { it.id }.toSet()
        if (state.selectedDocumentIds.size == allIds.size) {
            _uiState.update { it.copy(selectedDocumentIds = emptySet()) }
        } else {
            _uiState.update { it.copy(selectedDocumentIds = allIds) }
        }
    }

    fun exitSelectionMode() {
        _uiState.update {
            it.copy(
                selectionMode = false,
                selectedDocumentIds = emptySet(),
                showDeleteConfirmation = false,
            )
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedDocumentIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteDocuments(ids)
            exitSelectionMode()
        }
    }

    fun showRenameDialog() {
        val doc = _uiState.value.selectedDocumentIds.firstOrNull()?.let { id ->
            _uiState.value.documents.find { it.id == id }
        }
        _uiState.update { it.copy(showRenameDialog = true, renameText = doc?.name.orEmpty()) }
    }

    fun hideRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = false) }
    }

    fun updateRenameText(text: String) {
        _uiState.update { it.copy(renameText = text) }
    }

    fun renameSelected() {
        val docId = _uiState.value.selectedDocumentIds.firstOrNull() ?: return
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
        val docId = _uiState.value.selectedDocumentIds.firstOrNull() ?: return
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

    fun showTagsSheet() {
        _uiState.update { it.copy(showTagsSheet = true, selectedTagIds = emptySet()) }
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

    fun applyTagsToSelected() {
        val docIds = _uiState.value.selectedDocumentIds.toList()
        val tagIds = _uiState.value.selectedTagIds.toList()
        if (docIds.isEmpty()) return
        viewModelScope.launch {
            for (docId in docIds) {
                repository.setDocumentTags(docId, tagIds)
            }
            _uiState.update { it.copy(showTagsSheet = false) }
        }
    }

    fun showTagFilterSheet() {
        _uiState.update { it.copy(showTagFilterSheet = true) }
    }

    fun hideTagFilterSheet() {
        _uiState.update { it.copy(showTagFilterSheet = false) }
    }

    fun toggleFilterTag(tagId: Long) {
        val newSet = _uiState.value.filterTagIds.let { ids ->
            if (tagId in ids) ids - tagId else ids + tagId
        }
        _filterTagIds.value = newSet
        _uiState.update { it.copy(filterTagIds = newSet) }
    }

    fun setFilterMatchMode(mode: MatchMode) {
        _filterMatchMode.value = mode
        _uiState.update { it.copy(filterMatchMode = mode) }
    }

    fun importPdf(uri: Uri) {
        _uiState.update { it.copy(showImportProgress = true, importErrorMessage = null) }
        viewModelScope.launch {
            repository.importPdf(uri)
                .onSuccess { docId ->
                    _uiState.update { it.copy(showImportProgress = false) }
                    _importEvents.emit(ImportEvent.NavigateToDocument(docId))
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(showImportProgress = false, importErrorMessage = error.message)
                    }
                }
        }
    }

    fun dismissImportError() {
        _uiState.update { it.copy(importErrorMessage = null) }
    }

    fun showShareSheet() {
        _uiState.update { it.copy(showShareSheet = true, shareFilteredDocs = false) }
    }

    fun showFilteredShareSheet() {
        _uiState.update { it.copy(showShareSheet = true, shareFilteredDocs = true) }
    }

    fun hideShareSheet() {
        _uiState.update { it.copy(showShareSheet = false, shareFilteredDocs = false) }
    }

    fun showExportDialog() {
        val ids = _uiState.value.selectedDocumentIds.toList()
        if (ids.isEmpty()) return
        _uiState.update {
            it.copy(
                showExportDialog = true,
                exportPageSize = readDefaultPageSize(),
                pendingExportDocIds = ids,
            )
        }
    }

    private fun readDefaultPageSize(): PageSize {
        val prefs = getApplication<Application>().getSharedPreferences("settings", 0)
        val saved = prefs.getString("page_size", PageSize.A4.name) ?: PageSize.A4.name
        return try { PageSize.valueOf(saved) } catch (_: Exception) { PageSize.A4 }
    }

    fun hideExportDialog() {
        _uiState.update { it.copy(showExportDialog = false) }
    }

    fun setExportPageSize(pageSize: PageSize) {
        _uiState.update { it.copy(exportPageSize = pageSize) }
    }

    fun exportPdf(context: Context, outputUri: Uri) {
        val state = _uiState.value
        val docId = state.pendingExportDocIds.firstOrNull() ?: return
        viewModelScope.launch {
            val doc = repository.getDocument(docId).getOrNull()
            if (doc != null) {
                val pages = repository.getPages(docId).getOrNull() ?: emptyList()
                if (pages.isNotEmpty()) {
                    val result = pdfGenerator.generate(context, pages, outputUri, state.exportPageSize)
                }
            }
            val remaining = state.pendingExportDocIds.drop(1)
            if (remaining.isNotEmpty()) {
                _uiState.update { it.copy(pendingExportDocIds = remaining) }
            } else {
                _uiState.update { it.copy(showExportDialog = false, pendingExportDocIds = emptyList()) }
                exitSelectionMode()
            }
        }
    }

    fun shareViaSystem(context: Context) {
        val docs = getDocsToShare()
        if (docs.isEmpty()) return
        viewModelScope.launch {
            if (docs.size == 1) {
                val pdfUri = generateTempPdf(context, docs.first()) ?: return@launch
                sharePdf(context, pdfUri)
            } else {
                shareMultiplePdfs(context, docs)
            }
        }
        if (!_uiState.value.shareFilteredDocs) exitSelectionMode()
    }

    fun saveToDrive(context: Context, folderUri: Uri) {
        val docs = getDocsToShare()
        if (docs.isEmpty()) return
        viewModelScope.launch {
            copyPdfsToDrive(context, folderUri, docs)
        }
        if (!_uiState.value.shareFilteredDocs) exitSelectionMode()
    }

    private fun getDocsToShare(): List<Document> {
        val state = _uiState.value
        val ids = if (state.shareFilteredDocs) {
            state.documents.map { it.id }
        } else {
            state.selectedDocumentIds.toList()
        }
        return ids.mapNotNull { id ->
            state.documents.find { it.id == id }
        }
    }

    private suspend fun generateTempPdf(context: Context, doc: Document): Uri? {
        val pages = repository.getPages(doc.id).getOrNull() ?: return null
        val tempDir = File(context.cacheDir, "exports")
        tempDir.mkdirs()
        val tempFile = File(tempDir, "${doc.id}.pdf")
        val prefs = context.getSharedPreferences("settings", 0)
        val docPageSize = doc.pageSize?.let { try { PageSize.valueOf(it) } catch (_: Exception) { null } }
        val pageSize = docPageSize ?: prefs.getString("page_size", PageSize.A4.name)?.let { try { PageSize.valueOf(it) } catch (_: Exception) { null } } ?: PageSize.A4
        val result = pdfGenerator.generate(context, pages, Uri.fromFile(tempFile), pageSize)
        return when (result) {
            is com.picpocket.app.domain.export.PdfResult.Success -> Uri.fromFile(tempFile)
            is com.picpocket.app.domain.export.PdfResult.Error -> null
        }
    }

    private fun sharePdf(context: Context, pdfUri: Uri) {
        val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(pdfUri.path!!))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    private suspend fun shareMultiplePdfs(context: Context, docs: List<Document>) {
        val baseName = zipBaseName()
        val uris = docs.mapNotNull { doc ->
            val pdfUri = generateTempPdf(context, doc) ?: return@mapNotNull null
            val name = doc.name.replace(" ", "_") + ".pdf"
            name to pdfUri
        }
        if (uris.isEmpty()) return
        val contentResolver = context.contentResolver
        val cacheDir = context.cacheDir
        val zipFile = ZipUtil.create(cacheDir, baseName, uris, contentResolver)
        val zipUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, zipUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ZIP"))
        zipFile.delete()
    }

    private suspend fun copyPdfsToDrive(context: Context, folderUri: Uri, docs: List<Document>) {
        try {
            if (docs.size == 1) {
                copySinglePdfToDrive(context, folderUri, docs.first())
            } else {
                copyMultiplePdfsToDrive(context, folderUri, docs)
            }
        } catch (_: Exception) { }
    }

    private suspend fun copySinglePdfToDrive(context: Context, folderUri: Uri, doc: Document) {
        val pdfUri = generateTempPdf(context, doc) ?: return
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return
        val safeName = doc.name.replace(" ", "_").replace("/", "_") + ".pdf"
        val existing = folder.findFile(safeName)
        if (existing != null) existing.delete()
        val newFile = folder.createFile("application/pdf", safeName) ?: return
        context.contentResolver.openInputStream(pdfUri)?.use { input ->
            context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun copyMultiplePdfsToDrive(context: Context, folderUri: Uri, docs: List<Document>) {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return
        val dirName = zipBaseName()
        val dir = parent.createDirectory(dirName) ?: return
        for (doc in docs) {
            val pdfUri = generateTempPdf(context, doc) ?: continue
            val safeName = doc.name.replace(" ", "_").replace("/", "_") + ".pdf"
            val newFile = dir.createFile("application/pdf", safeName) ?: continue
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun zipBaseName(): String {
        val filterIds = _uiState.value.filterTagIds
        if (filterIds.isNotEmpty()) {
            val names = filterIds.mapNotNull { id ->
                allTags.value.find { it.id == id }?.name?.replace(" ", "_")
            }
            if (names.isNotEmpty()) {
                val delimiter = if (_uiState.value.filterMatchMode == MatchMode.MATCH_ANY) "+" else "-"
                return names.joinToString(delimiter)
            }
        }
        val now = LocalDateTime.now()
        return now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    }
}
