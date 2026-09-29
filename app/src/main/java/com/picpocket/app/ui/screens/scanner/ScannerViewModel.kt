package com.picpocket.app.ui.screens.scanner

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Tag
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.domain.filter.FilterPipeline
import com.picpocket.app.domain.filter.FilterType
import com.picpocket.app.domain.ocr.OcrManager
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.scan.QualityTier
import com.picpocket.app.domain.scanner.ScannerManager
import com.picpocket.app.domain.scanner.ScannerResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class CapturedPage(
    val imageUri: Uri,
    val ocrText: String? = null,
    val filterTypes: List<FilterType> = emptyList(),
)

data class ScannerUiState(
    val capturedPages: List<CapturedPage> = emptyList(),
    val currentPageIndex: Int = -1,
    val isScanning: Boolean = false,
    val isSaving: Boolean = false,
    val documentName: String = "",
    val showNameDialog: Boolean = false,
    val showFilterSheet: Boolean = false,
    val scanError: String? = null,
    val saveError: String? = null,
    val savedDocumentId: DocumentId? = null,
    val pendingIntentSender: IntentSender? = null,
    val isAppendMode: Boolean = false,
    val appendPageCount: Int = 0,
    val showTagsDialog: Boolean = false,
    val showDiscardDialog: Boolean = false,
    val selectedTagIds: Set<Long> = emptySet(),
    val qualityTier: QualityTier = QualityTier.BEST,
    val exportPageSize: PageSize = PageSize.A4,
    val showOverwriteDialog: Boolean = false,
    val overwriteTargetName: String = "",
    val scannerCancelledTick: Int = 0,
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    application: Application,
    private val repository: DocumentRepository,
    private val scannerManager: ScannerManager,
    private val filterPipeline: FilterPipeline,
    private val ocrManager: OcrManager,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var existingDocumentId: DocumentId? = null
    private var pendingDocumentId: DocumentId? = null

    private val _allTags = repository.observeAllTags()
    val allTags: StateFlow<List<Tag>> = _allTags.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    init {
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
        val prefs = application.getSharedPreferences("settings", 0)
        val savedTierOrdinal = prefs.getInt("quality_tier", 0)
        val defaultTier = QualityTier.entries.getOrNull(savedTierOrdinal) ?: QualityTier.BEST
        val savedPageSize = prefs.getString("page_size", PageSize.A4.name) ?: PageSize.A4.name
        val defaultPageSize = try { PageSize.valueOf(savedPageSize) } catch (_: Exception) { PageSize.A4 }
        _uiState.update { it.copy(
            documentName = "Scan_${now.format(formatter)}",
            qualityTier = defaultTier,
            exportPageSize = defaultPageSize,
        ) }
    }

    fun setExistingDocumentId(id: DocumentId) {
        existingDocumentId = id
        _uiState.update { it.copy(isAppendMode = true) }
    }

    fun getScanIntentSender(activity: Activity) {
        Log.d("ScannerViewModel", "getScanIntentSender called, activity=$activity")
        viewModelScope.launch {
            try {
                Log.d("ScannerViewModel", "starting scan, isScanning=true")
                _uiState.update { it.copy(isScanning = true, scanError = null) }
                val intentSender = scannerManager.getStartScanIntentSender(activity)
                Log.d("ScannerViewModel", "got intentSender=$intentSender")
                _uiState.update { it.copy(pendingIntentSender = intentSender, isScanning = false) }
            } catch (e: Exception) {
                Log.e("ScannerViewModel", "getScanIntentSender failed", e)
                _uiState.update { it.copy(isScanning = false, scanError = e.message) }
            }
        }
    }

    fun clearPendingIntentSender() {
        Log.d("ScannerViewModel", "clearPendingIntentSender")
        _uiState.update { it.copy(pendingIntentSender = null) }
    }

    fun onScannerResult(result: ScannerResult) {
        Log.d("ScannerViewModel", "onScannerResult: $result")
        when (result) {
            is ScannerResult.PageCaptured -> {
                val page = CapturedPage(imageUri = result.imageUri)
                val docId = existingDocumentId
                _uiState.update { state ->
                    state.copy(
                        capturedPages = state.capturedPages + page,
                        currentPageIndex = state.capturedPages.size,
                    )
                }
                if (docId != null) {
                    viewModelScope.launch {
                        _uiState.update { it.copy(isSaving = true, saveError = null) }
                        try {
                            saveNewPage(docId, page)
                        } catch (e: Exception) {
                            _uiState.update { it.copy(isSaving = false, saveError = e.message) }
                        }
                        _uiState.update { it.copy(isSaving = false) }
                        launch { ocrManager.runOcr(docId) }
                    }
                }
            }
            is ScannerResult.MultiplePagesCaptured -> { }
            is ScannerResult.Cancelled -> {
                Log.d("ScannerViewModel", "scanner cancelled")
                if (_uiState.value.capturedPages.isEmpty()) {
                    _uiState.update { it.copy(scannerCancelledTick = it.scannerCancelledTick + 1) }
                }
            }
            is ScannerResult.Error -> {
                Log.e("ScannerViewModel", "scanner error", result.exception)
            }
        }
    }

    fun handleScannerIntent(data: Intent?) {
        Log.d("ScannerViewModel", "handleScannerIntent: data=$data")
        viewModelScope.launch {
            val result = scannerManager.handleResult(data)
            when (result) {
                is ScannerResult.MultiplePagesCaptured -> {
                    for (uri in result.imageUris) {
                        onScannerResult(ScannerResult.PageCaptured(uri))
                    }
                }
                else -> onScannerResult(result)
            }
        }
    }

    fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val app = getApplication<Application>()
            app.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun applyFilter(pageIndex: Int, filterType: FilterType) {
        _uiState.update { state ->
            val pages = state.capturedPages.toMutableList()
            val page = pages[pageIndex]
            val currentFilters = page.filterTypes.toMutableList()
            if (filterType in currentFilters) {
                currentFilters.remove(filterType)
            } else {
                currentFilters.add(filterType)
            }
            pages[pageIndex] = page.copy(filterTypes = currentFilters)
            state.copy(capturedPages = pages)
        }
    }

    fun showNameDialog() {
        _uiState.update { it.copy(showNameDialog = true) }
    }

    fun hideNameDialog() {
        _uiState.update { it.copy(showNameDialog = false) }
    }

    fun showDiscardDialog() {
        _uiState.update { it.copy(showDiscardDialog = true) }
    }

    fun hideDiscardDialog() {
        _uiState.update { it.copy(showDiscardDialog = false) }
    }

    fun updateDocumentName(name: String) {
        _uiState.update { it.copy(documentName = name) }
    }

    fun setQualityTier(tier: QualityTier) {
        _uiState.update { it.copy(qualityTier = tier) }
    }

    fun setExportPageSize(pageSize: PageSize) {
        _uiState.update { it.copy(exportPageSize = pageSize) }
    }

    fun showFilterSheet(pageIndex: Int) {
        _uiState.update { it.copy(showFilterSheet = true, currentPageIndex = pageIndex) }
    }

    fun hideFilterSheet() {
        _uiState.update { it.copy(showFilterSheet = false) }
    }

    fun removePage(index: Int) {
        _uiState.update { state ->
            val pages = state.capturedPages.toMutableList()
            pages.removeAt(index)
            state.copy(capturedPages = pages)
        }
    }

    private suspend fun saveNewPage(documentId: DocumentId, captured: CapturedPage) {
        val tier = _uiState.value.qualityTier
        repository.addPage(documentId, captured.imageUri.toString(), fileSizeBytes = 0, qualityTier = tier.ordinal)

        _uiState.update { it.copy(appendPageCount = it.appendPageCount + 1) }
    }

    fun showTagsDialog() {
        _uiState.update { it.copy(showTagsDialog = true) }
    }

    fun hideTagsDialog() {
        _uiState.update { it.copy(showTagsDialog = false) }
    }

    fun toggleTag(tagId: Long) {
        val docId = pendingDocumentId ?: return
        _uiState.update { state ->
            val newSet = if (tagId in state.selectedTagIds) {
                state.selectedTagIds - tagId
            } else {
                state.selectedTagIds + tagId
            }
            state.copy(selectedTagIds = newSet)
        }
        viewModelScope.launch {
            repository.setDocumentTags(docId, _uiState.value.selectedTagIds.toList())
        }
    }

    fun createTagAndSelect(name: String) {
        val docId = pendingDocumentId ?: return
        viewModelScope.launch {
            val tagId = repository.createTag(name)
            _uiState.update { it.copy(selectedTagIds = it.selectedTagIds + tagId) }
            repository.setDocumentTags(docId, (_uiState.value.selectedTagIds + tagId).toList())
        }
    }

    fun confirmNameAndSave() {
        val state = _uiState.value
        if (state.documentName.isBlank() || state.capturedPages.isEmpty()) return

        proceedWithSave()
    }

    private fun proceedWithSave() {
        val state = _uiState.value
        _uiState.update { it.copy(isSaving = true, saveError = null) }

        viewModelScope.launch {
            try {
                val existing = repository.getDocumentsByName(state.documentName).getOrNull() ?: emptyList()
                if (existing.isNotEmpty()) {
                    _uiState.update {
                        it.copy(isSaving = false, showOverwriteDialog = true, overwriteTargetName = state.documentName)
                    }
                    return@launch
                }

                val documentId = repository.createDocument(state.documentName, qualityTier = state.qualityTier.ordinal, pageSize = state.exportPageSize.name).getOrNull() ?: return@launch

                for (captured in state.capturedPages) {
                    repository.addPage(documentId, captured.imageUri.toString(), fileSizeBytes = 0, qualityTier = state.qualityTier.ordinal)
                }

                pendingDocumentId = documentId
                _uiState.update { it.copy(isSaving = false, showTagsDialog = true) }

                launch { ocrManager.runOcr(documentId) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, saveError = e.message) }
            }
        }
    }

    fun completeSave() {
        val docId = pendingDocumentId ?: return
        pendingDocumentId = null
        _uiState.update { it.copy(savedDocumentId = docId, showTagsDialog = false, capturedPages = emptyList()) }
    }

    fun confirmOverwrite() {
        val name = _uiState.value.overwriteTargetName
        if (name.isBlank()) return
        _uiState.update { it.copy(showOverwriteDialog = false, isSaving = true) }
        viewModelScope.launch {
            repository.deleteDocumentsByName(name)
            proceedWithSave()
        }
    }

    fun cancelOverwrite() {
        _uiState.update { it.copy(showOverwriteDialog = false, isSaving = false) }
    }
}
