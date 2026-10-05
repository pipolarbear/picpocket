package com.picpocket.app.domain.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.PageKind
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports documents that contain native PDF pages without rasterizing them:
 * an unedited single-source document is copied byte-for-byte, and an edited or
 * mixed document is assembled with PdfBox (native pages copied, image pages
 * added as images).
 */
@Singleton
class NativePdfExporter @Inject constructor() {

    fun hasNativePages(pages: List<Page>): Boolean = pages.any { it.kind == PageKind.PDF }

    fun isUnchangedSingleSource(pages: List<Page>): Boolean {
        if (pages.isEmpty() || pages.any { it.kind != PageKind.PDF }) return false
        val filename = pages.first().filename
        if (pages.any { it.filename != filename }) return false
        return pages.withIndex().all { (index, page) -> page.pdfPageIndex == index }
    }

    suspend fun export(context: Context, pages: List<Page>, outputUri: Uri, copyWhenUnchanged: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (pages.isEmpty()) return@withContext false
            if (copyWhenUnchanged && isUnchangedSingleSource(pages)) {
                copySource(context, pages.first(), outputUri)
            } else {
                merge(context, pages, outputUri)
            }
        }

    private fun copySource(context: Context, page: Page, outputUri: Uri): Boolean = try {
        val source = sourceFile(page)
        context.contentResolver.openOutputStream(outputUri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } != null
    } catch (e: Exception) {
        false
    }

    private fun merge(context: Context, pages: List<Page>, outputUri: Uri): Boolean {
        val outDoc = PDDocument()
        return try {
            var index = 0
            while (index < pages.size) {
                val page = pages[index]
                if (page.kind == PageKind.PDF) {
                    // Copy a run of consecutive pages from the same source file.
                    val filename = page.filename
                    val source = sourceFile(page)
                    PDDocument.load(source).use { srcDoc ->
                        while (index < pages.size && pages[index].kind == PageKind.PDF && pages[index].filename == filename) {
                            val pageIndex = pages[index].pdfPageIndex
                            if (pageIndex in 0 until srcDoc.numberOfPages) {
                                outDoc.importPage(srcDoc.getPage(pageIndex))
                            }
                            index++
                        }
                    }
                } else {
                    appendImagePage(context, outDoc, page)
                    index++
                }
            }
            context.contentResolver.openOutputStream(outputUri)?.use { out -> outDoc.save(out) }
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { outDoc.close() }
        }
    }

    private fun appendImagePage(context: Context, outDoc: PDDocument, page: Page) {
        val bitmap = context.contentResolver.openInputStream(Uri.parse(page.imageUri))?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        } ?: return
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            bitmap.recycle()
            out.toByteArray()
        }
        val image = JPEGFactory.createFromStream(outDoc, ByteArrayInputStream(bytes))
        val width = image.width.toFloat()
        val height = image.height.toFloat()
        val pdfPage = PDPage(PDRectangle(width, height))
        outDoc.addPage(pdfPage)
        PDPageContentStream(outDoc, pdfPage).use { content ->
            content.drawImage(image, 0f, 0f, width, height)
        }
    }

    private fun sourceFile(page: Page): File = File(URI(page.imageUri))
}
