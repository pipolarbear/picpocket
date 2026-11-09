package com.picpocket.app.ui.screens.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.RedactionRect
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.domain.render.PageRenderer
import com.picpocket.app.domain.render.RedactionBurner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import javax.inject.Inject

data class ViewerUiState(
    val pages: List<Page> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = true,
    val message: String? = null,
)

@HiltViewModel
class PageViewerViewModel @Inject constructor(
    application: Application,
    private val repository: DocumentRepository,
    private val pageRenderer: PageRenderer = PageRenderer(),
    private val redactionBurner: RedactionBurner = RedactionBurner(),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var documentId: DocumentId = ""

    fun loadPages(docId: DocumentId, initialPageIndex: Int = 0) {
        documentId = docId
        _uiState.update { it.copy(currentIndex = initialPageIndex) }
        viewModelScope.launch {
            repository.observePages(docId).collect { pages ->
                _uiState.update {
                    it.copy(pages = pages, isLoading = false)
                }
            }
        }
    }

    fun setPageIndex(index: Int) {
        _uiState.update { it.copy(currentIndex = index) }
    }

    /** Renders a page for the viewer (PDF pages are rendered on demand). */
    suspend fun renderPage(page: Page, targetWidth: Int): Bitmap? =
        pageRenderer.render(File(URI(page.imageUri)), page.kind, page.pdfPageIndex, targetWidth)

    /**
     * The page as the exported redacted PDF will show it: marked areas replaced
     * with a mosaic. Returns null when there is nothing marked or the page
     * cannot be loaded.
     */
    suspend fun redactedPreview(page: Page, targetWidth: Int): Bitmap? {
        if (page.redactions.isEmpty()) return null
        val original = pageRenderer.render(
            File(URI(page.imageUri)),
            page.kind,
            page.pdfPageIndex,
            targetWidth,
        ) ?: return null
        val burnt = redactionBurner.burn(original, page.redactions)
        if (burnt !== original) original.recycle()
        return burnt
    }

    fun addRedaction(page: Page, rect: RedactionRect) {
        if (!rect.isUsable()) return
        saveRedactions(page, page.redactions + rect.normalized())
    }

    fun removeRedaction(page: Page, index: Int) {
        if (index !in page.redactions.indices) return
        saveRedactions(page, page.redactions.filterIndexed { i, _ -> i != index })
    }

    fun clearRedactions(page: Page) {
        if (page.redactions.isEmpty()) return
        saveRedactions(page, emptyList())
    }

    fun clearAllRedactions() {
        _uiState.value.pages
            .filter { it.redactions.isNotEmpty() }
            .forEach { saveRedactions(it, emptyList()) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun saveRedactions(page: Page, rects: List<RedactionRect>) {
        if (documentId.isEmpty()) return
        viewModelScope.launch {
            repository.setPageRedactions(documentId, page.pageNumber, rects)
                .onFailure { error ->
                    _uiState.update {
                        it.copy(message = error.message ?: "Could not save the marked areas")
                    }
                }
        }
    }
}
