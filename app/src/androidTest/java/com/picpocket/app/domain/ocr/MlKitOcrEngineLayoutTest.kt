package com.picpocket.app.domain.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitOcrEngineLayoutTest {

    private fun textBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 64f
            isAntiAlias = true
        }
        canvas.drawText("HELLO 123", 30f, 120f, paint)
        return bitmap
    }

    @Test
    fun recognizeLayoutReturnsTextWithBoxesInsideTheImage() = runBlocking {
        val bitmap = textBitmap()

        val result = MlKitOcrEngine().recognizeLayout(bitmap)

        assertTrue("expected recognized text", result.text.isNotBlank())
        assertTrue("expected at least one element", result.elements.isNotEmpty())
        result.elements.forEach { element ->
            assertTrue("left in bounds", element.left >= 0 && element.left <= bitmap.width)
            assertTrue("top in bounds", element.top >= 0 && element.top <= bitmap.height)
            assertTrue("right in bounds", element.right >= 0 && element.right <= bitmap.width)
            assertTrue("bottom in bounds", element.bottom >= 0 && element.bottom <= bitmap.height)
            assertTrue("non-empty box", element.width > 0 && element.height > 0)
        }
    }
}
