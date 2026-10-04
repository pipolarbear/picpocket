package com.picpocket.app.domain.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.picpocket.app.data.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchablePdfGenerator @Inject constructor() : PdfGenerator {

    override suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
        pageSize: PageSize,
    ): PdfResult = withContext(Dispatchers.IO) {
        try {
            val document = PdfDocument()

            for (page in pages) {
                val bitmap = context.contentResolver.openInputStream(
                    Uri.parse(page.imageUri)
                )?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: continue

                val bitmapW = bitmap.width
                val bitmapH = bitmap.height
                val (pageW, pageH) = pageDimensionsFor(pageSize, bitmapW, bitmapH)

                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, page.pageNumber).create()
                val pdfPage = document.startPage(pageInfo)
                val scaleX = pdfPage.canvas.width.toFloat() / bitmapW
                val scaleY = pdfPage.canvas.height.toFloat() / bitmapH
                val scale = minOf(scaleX, scaleY)
                val scaledW = (bitmapW * scale).toInt()
                val scaledH = (bitmapH * scale).toInt()
                val offsetX = (pdfPage.canvas.width - scaledW) / 2f
                val offsetY = (pdfPage.canvas.height - scaledH) / 2f

                pdfPage.canvas.drawBitmap(bitmap, null, android.graphics.RectF(
                    offsetX, offsetY, offsetX + scaledW, offsetY + scaledH
                ), null)

                val ocrText = page.ocrText
                if (!ocrText.isNullOrBlank()) {
                    val textPaint = Paint().apply {
                        color = android.graphics.Color.TRANSPARENT
                        isAntiAlias = true
                        textSize = 12f
                    }
                    val textX = offsetX + 20f
                    val textY = offsetY + 30f
                    val lines = ocrText.split("\n")
                    for ((i, line) in lines.withIndex()) {
                        pdfPage.canvas.drawText(line, textX, textY + i * 14f, textPaint)
                    }
                }

                document.finishPage(pdfPage)
                bitmap.recycle()
            }

            context.contentResolver.openOutputStream(outputUri)?.use { out: OutputStream ->
                document.writeTo(out)
            } ?: return@withContext PdfResult.Error(Exception("Cannot open output stream"))

            document.close()
            PdfResult.Success(outputUri.toString())
        } catch (e: Exception) {
            PdfResult.Error(e)
        }
    }
}
