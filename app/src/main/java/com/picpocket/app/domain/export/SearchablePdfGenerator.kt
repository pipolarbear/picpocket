package com.picpocket.app.domain.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.picpocket.app.data.model.Page
import com.picpocket.app.domain.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchablePdfGenerator @Inject constructor(
    private val ocrEngine: OcrEngine,
) : PdfGenerator {

    override suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
        pageSize: PageSize,
    ): PdfResult = withContext(Dispatchers.IO) {
        try {
            val searchable = isSearchable(context)
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

                if (searchable) {
                    drawTextLayer(pdfPage.canvas, bitmap, scale, offsetX, offsetY)
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

    /**
     * Recognizes the page and draws each element as invisible text at its real
     * position, so search and selection line up with the visible words. A
     * recognition failure simply omits the text layer for that page.
     */
    private suspend fun drawTextLayer(
        canvas: Canvas,
        bitmap: android.graphics.Bitmap,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        val layout = runCatching { ocrEngine.recognizeLayout(bitmap) }.getOrNull() ?: return
        val placements = SearchableTextLayer.placements(layout.elements, scale, offsetX, offsetY)
        if (placements.isEmpty()) return

        val paint = Paint().apply {
            color = Color.TRANSPARENT
            isAntiAlias = true
        }
        for (placement in placements) {
            paint.textSize = placement.textSize
            val measured = paint.measureText(placement.text)
            if (measured > placement.boxWidth && measured > 0f) {
                paint.textSize = placement.textSize * (placement.boxWidth / measured)
            }
            canvas.save()
            if (placement.rotationDegrees != 0) {
                canvas.rotate(
                    placement.rotationDegrees.toFloat(),
                    placement.centerX,
                    placement.centerY,
                )
            }
            canvas.drawText(placement.text, placement.left, placement.top + paint.textSize, paint)
            canvas.restore()
        }
    }

    private fun isSearchable(context: Context): Boolean =
        context.getSharedPreferences("settings", 0).getBoolean("searchable_pdf", true)
}
