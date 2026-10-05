package com.picpocket.app.domain.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.picpocket.app.data.model.PageKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces a bitmap for a page, whatever backs it: an image file, or a page of a
 * stored PDF (rendered via the platform PdfRenderer). Runs off the main thread;
 * PDF rendering is serialized because a PdfRenderer is not reentrant and PDFium
 * is single-threaded in-process.
 */
@Singleton
class PageRenderer @Inject constructor() {

    private val pdfRenderMutex = Mutex()

    suspend fun render(file: File, kind: PageKind, pdfPageIndex: Int, targetWidth: Int): Bitmap? {
        if (!file.exists()) return null
        return when (kind) {
            PageKind.IMAGE -> withContext(Dispatchers.IO) { decodeImage(file, targetWidth) }
            PageKind.PDF -> pdfRenderMutex.withLock {
                withContext(Dispatchers.IO) { renderPdfPage(file, pdfPageIndex, targetWidth) }
            }
        }
    }

    private fun decodeImage(file: File, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        if (targetWidth > 0) {
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun renderPdfPage(file: File, index: Int, targetWidth: Int): Bitmap? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (index !in 0 until renderer.pageCount) return null
                    renderer.openPage(index).use { page ->
                        val width = if (targetWidth > 0) targetWidth else page.width
                        val scale = width.toFloat() / page.width
                        val height = maxOf(1, (page.height * scale).toInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        val matrix = Matrix().apply { setScale(scale, scale) }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        bitmap
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
