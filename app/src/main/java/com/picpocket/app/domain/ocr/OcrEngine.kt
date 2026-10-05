package com.picpocket.app.domain.ocr

import android.graphics.Bitmap

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult

    /**
     * Recognizes text together with per-element geometry. The default keeps
     * implementers that only produce text working by returning the text with no
     * elements; engines that can report boxes override it.
     */
    suspend fun recognizeLayout(bitmap: Bitmap): OcrLayoutResult {
        val result = recognize(bitmap)
        return OcrLayoutResult(text = result.text, elements = emptyList(), confidence = result.confidence)
    }
}
