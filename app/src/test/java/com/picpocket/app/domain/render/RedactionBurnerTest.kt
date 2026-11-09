package com.picpocket.app.domain.render

import android.graphics.Bitmap
import android.graphics.Color
import com.picpocket.app.data.model.RedactionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedactionBurnerTest {

    private val burner = RedactionBurner()

    private fun gradient(width: Int = 100, height: Int = 100): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    setPixel(x, y, Color.rgb(x % 256, y % 256, 128))
                }
            }
        }

    @Test
    fun `no areas returns the same bitmap`() {
        val source = gradient()
        assertSame(source, burner.burn(source, emptyList()))
    }

    @Test
    fun `unusable areas are ignored`() {
        val source = gradient()
        assertSame(source, burner.burn(source, listOf(RedactionRect(0.5f, 0.5f, 0f, 0f))))
    }

    @Test
    fun `pixels inside the area are replaced and pixels outside are untouched`() {
        val source = gradient()
        val result = burner.burn(source, listOf(RedactionRect(0.2f, 0.2f, 0.4f, 0.4f)))

        assertNotSame(source, result)

        var changedInside = 0
        for (x in 24 until 56) {
            for (y in 24 until 56) {
                if (result.getPixel(x, y) != source.getPixel(x, y)) changedInside++
            }
        }
        assertTrue("expected the area to be rewritten", changedInside > 0)

        for (x in 0 until 15) {
            for (y in 0 until 15) {
                assertEquals(source.getPixel(x, y), result.getPixel(x, y))
            }
        }
    }

    @Test
    fun `the area is drawn as coarse blocks`() {
        val result = burner.burn(gradient(), listOf(RedactionRect(0f, 0f, 1f, 1f)))
        // Two neighbouring pixels inside the same block share one colour.
        assertEquals(result.getPixel(0, 0), result.getPixel(1, 1))
    }

    @Test
    fun `a whole-page area keeps the bitmap size`() {
        val source = gradient(width = 60, height = 90)
        val result = burner.burn(source, listOf(RedactionRect(0f, 0f, 1f, 1f)))
        assertEquals(60, result.width)
        assertEquals(90, result.height)
    }
}
