package com.picpocket.app.domain.ocr

import android.graphics.Bitmap

/** A configurable layout engine for tests. */
class FakeLayoutOcrEngine(
    var layout: OcrLayoutResult = OcrLayoutResult(text = "", elements = emptyList()),
    var fail: Boolean = false,
) : OcrEngine {

    var layoutCalls = 0
        private set

    override suspend fun recognize(bitmap: Bitmap): OcrResult =
        OcrResult(text = layout.text, confidence = layout.confidence)

    override suspend fun recognizeLayout(bitmap: Bitmap): OcrLayoutResult {
        layoutCalls++
        if (fail) throw IllegalStateException("recognizeLayout failed")
        return layout
    }
}
