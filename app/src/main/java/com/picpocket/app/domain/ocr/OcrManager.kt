package com.picpocket.app.domain.ocr

import android.graphics.BitmapFactory
import com.picpocket.app.data.model.PageKind
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.debug.Category
import com.picpocket.app.debug.Tracing
import com.picpocket.app.domain.render.PageRenderer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrManager @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val store: DocumentStore,
    private val pageRenderer: PageRenderer = PageRenderer(),
) {

    private val _metadataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val metadataChanged = _metadataChanged.asSharedFlow()

    open suspend fun runOcr(documentId: String) {
        val doc = store.readMetadata(documentId).getOrNull() ?: return
        if (doc.ocrComplete) return
        val pending = doc.pages.filter { it.ocrText == null }
        if (pending.isEmpty()) return
        for (page in pending) {
            val pageFile = store.pageFile(documentId, page.filename)
            val bitmap = if (page.kind == PageKind.PDF) {
                pageRenderer.render(pageFile, PageKind.PDF, page.pdfPageIndex, 2400)
            } else {
                BitmapFactory.decodeFile(pageFile.absolutePath)
            }
            if (bitmap == null) {
                Tracing.w(Category.OCR, "OcrManager", "decode failed for page ${page.pageNumber} of $documentId")
                store.updatePageOcrText(documentId, page.pageNumber, "")
                _metadataChanged.emit(Unit)
                continue
            }
            val text = try {
                ocrEngine.recognize(bitmap).text
            } catch (e: Exception) {
                Tracing.w(Category.OCR, "OcrManager", "OCR failed for page ${page.pageNumber} of $documentId: $e")
                ""
            }
            store.updatePageOcrText(documentId, page.pageNumber, text)
            _metadataChanged.emit(Unit)
        }
        val updated = store.readMetadata(documentId).getOrNull() ?: return
        val allDone = updated.pages.all { it.ocrText != null }
        if (allDone) {
            store.writeMetadata(documentId, updated.copy(ocrComplete = true))
            _metadataChanged.emit(Unit)
        }
    }
}