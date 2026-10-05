package com.picpocket.app.domain.ocr

/** A recognized word/element and its location in the source bitmap's pixels. */
data class OcrElement(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val rotationDegrees: Int = 0,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** OCR text plus the per-element geometry needed to place a text layer. */
data class OcrLayoutResult(
    val text: String,
    val elements: List<OcrElement>,
    val confidence: Float = 0f,
)
