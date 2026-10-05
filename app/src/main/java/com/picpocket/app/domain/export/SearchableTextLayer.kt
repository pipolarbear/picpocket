package com.picpocket.app.domain.export

import com.picpocket.app.domain.ocr.OcrElement

/** Where a recognized element's invisible text goes on a PDF page, in points. */
data class TextPlacement(
    val text: String,
    val left: Float,
    val top: Float,
    val textSize: Float,
    val boxWidth: Float,
    val rotationDegrees: Int,
    val centerX: Float,
    val centerY: Float,
)

/**
 * Maps recognized elements from source-image pixels to PDF-page points, using
 * the same scale/offset the page image was drawn with so the text lands on the
 * visible words.
 */
object SearchableTextLayer {

    fun placements(
        elements: List<OcrElement>,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): List<TextPlacement> = elements.mapNotNull { element ->
        if (element.text.isBlank()) return@mapNotNull null
        val boxWidth = element.width * scale
        val boxHeight = element.height * scale
        if (boxWidth <= 0f || boxHeight <= 0f) return@mapNotNull null
        val left = offsetX + element.left * scale
        val top = offsetY + element.top * scale
        TextPlacement(
            text = element.text,
            left = left,
            top = top,
            textSize = boxHeight,
            boxWidth = boxWidth,
            rotationDegrees = element.rotationDegrees,
            centerX = left + boxWidth / 2f,
            centerY = top + boxHeight / 2f,
        )
    }
}
