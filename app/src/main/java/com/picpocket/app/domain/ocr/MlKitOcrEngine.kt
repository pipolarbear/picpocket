package com.picpocket.app.domain.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToInt

@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { continuation ->
                try {
                    recognizer.process(image)
                        .addOnSuccessListener { result ->
                            continuation.resume(
                                OcrResult(
                                    text = result.text,
                                    confidence = 0.85f,
                                )
                            )
                        }
                        .addOnFailureListener { _ ->
                            continuation.resume(
                                OcrResult(text = "", confidence = 0f)
                            )
                        }
                } catch (e: Exception) {
                    continuation.resume(OcrResult(text = "", confidence = 0f))
                }
            }
        } ?: OcrResult(text = "", confidence = 0f)
    }

    override suspend fun recognizeLayout(bitmap: Bitmap): OcrLayoutResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { continuation ->
                try {
                    recognizer.process(image)
                        .addOnSuccessListener { result ->
                            val elements = mutableListOf<OcrElement>()
                            for (block in result.textBlocks) {
                                for (line in block.lines) {
                                    val angle = line.angle.roundToInt()
                                    val lineBox = line.boundingBox
                                    if (line.elements.isEmpty()) {
                                        if (lineBox != null) {
                                            elements.add(
                                                OcrElement(line.text, lineBox.left, lineBox.top, lineBox.right, lineBox.bottom, angle)
                                            )
                                        }
                                    } else {
                                        for (element in line.elements) {
                                            val box = element.boundingBox ?: lineBox
                                            if (box != null) {
                                                elements.add(
                                                    OcrElement(element.text, box.left, box.top, box.right, box.bottom, angle)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            continuation.resume(
                                OcrLayoutResult(text = result.text, elements = elements, confidence = 0.85f)
                            )
                        }
                        .addOnFailureListener { _ ->
                            continuation.resume(OcrLayoutResult(text = "", elements = emptyList()))
                        }
                } catch (e: Exception) {
                    continuation.resume(OcrLayoutResult(text = "", elements = emptyList()))
                }
            }
        } ?: OcrLayoutResult(text = "", elements = emptyList())
    }
}
