@file:Suppress("DEPRECATION")

package com.picpocket.app.domain.pdfimport

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.picpocket.app.data.store.PageNaming
import com.picpocket.app.domain.scan.PageEncoder
import com.picpocket.app.domain.scan.QualityTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class PdfPageImportResult(
    val pageNumber: Int,
    val filename: String,
    val fileSizeBytes: Long,
)

@Singleton
class PdfPageImporter @Inject constructor() {

    suspend fun import(
        contentResolver: ContentResolver,
        pdfUri: Uri,
        targetDir: File,
        qualityTier: QualityTier,
    ): Result<List<PdfPageImportResult>> = withContext(Dispatchers.IO) {
        try {
            val fd = contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return@withContext Result.failure(Exception("Cannot open PDF file"))
            val renderer = PdfRenderer(fd)
            val results = mutableListOf<PdfPageImportResult>()

            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                // PDFs with a transparent page background otherwise become black
                // side bars once compressed to JPEG.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val pageNumber = i + 1
                val tempFile = File(targetDir, "tmp_pdf_$i")
                tempFile.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                bitmap.recycle()

                val staged = File(targetDir, "staged_$i")
                PageEncoder.encodePage(tempFile, staged, qualityTier)
                tempFile.delete()

                val filename = PageNaming.filenameFor(staged)
                val destFile = File(targetDir, filename)
                staged.renameTo(destFile)

                results.add(PdfPageImportResult(pageNumber, filename, destFile.length()))
            }
            renderer.close()
            fd.close()
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
