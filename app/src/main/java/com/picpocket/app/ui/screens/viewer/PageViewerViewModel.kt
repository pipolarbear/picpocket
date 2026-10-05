package com.picpocket.app.ui.screens.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.domain.render.PageRenderer
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
)

@HiltViewModel
class PageViewerViewModel @Inject constructor(
    application: Application,
    private val repository: DocumentRepository,
    private val pageRenderer: PageRenderer = PageRenderer(),
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
}
