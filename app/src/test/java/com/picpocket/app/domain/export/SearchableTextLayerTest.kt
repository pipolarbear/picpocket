package com.picpocket.app.domain.export

import com.picpocket.app.domain.ocr.OcrElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchableTextLayerTest {

    @Test
    fun `maps element pixels to page points through the image transform`() {
        val element = OcrElement(text = "hello", left = 10, top = 20, right = 30, bottom = 40)

        val placements = SearchableTextLayer.placements(
            elements = listOf(element),
            scale = 2f,
            offsetX = 5f,
            offsetY = 7f,
        )

        assertEquals(1, placements.size)
        val p = placements.single()
        assertEquals("hello", p.text)
        assertEquals(25f, p.left, 0.001f)
        assertEquals(47f, p.top, 0.001f)
        assertEquals(40f, p.textSize, 0.001f)
        assertEquals(40f, p.boxWidth, 0.001f)
        assertEquals(45f, p.centerX, 0.001f)
        assertEquals(67f, p.centerY, 0.001f)
    }

    @Test
    fun `carries rotation`() {
        val element = OcrElement(text = "tilted", left = 0, top = 0, right = 10, bottom = 10, rotationDegrees = 90)

        val p = SearchableTextLayer.placements(listOf(element), 1f, 0f, 0f).single()

        assertEquals(90, p.rotationDegrees)
    }

    @Test
    fun `skips blank text and empty boxes`() {
        val elements = listOf(
            OcrElement(text = "  ", left = 0, top = 0, right = 10, bottom = 10),
            OcrElement(text = "x", left = 5, top = 5, right = 5, bottom = 5),
            OcrElement(text = "ok", left = 0, top = 0, right = 4, bottom = 4),
        )

        val placements = SearchableTextLayer.placements(elements, 1f, 0f, 0f)

        assertEquals(1, placements.size)
        assertEquals("ok", placements.single().text)
    }

    @Test
    fun `returns nothing when there are no elements`() {
        assertTrue(SearchableTextLayer.placements(emptyList(), 1f, 0f, 0f).isEmpty())
    }
}
