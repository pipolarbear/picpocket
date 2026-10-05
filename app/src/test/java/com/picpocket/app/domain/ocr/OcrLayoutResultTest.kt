package com.picpocket.app.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrLayoutResultTest {

    @Test
    fun `element reports width and height from its box`() {
        val element = OcrElement(text = "hi", left = 10, top = 20, right = 40, bottom = 55)

        assertEquals(30, element.width)
        assertEquals(35, element.height)
    }

    @Test
    fun `element defaults to no rotation`() {
        assertEquals(0, OcrElement("x", 0, 0, 1, 1).rotationDegrees)
    }
}
