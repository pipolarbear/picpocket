package com.picpocket.app.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSizeTest {

    @Test
    fun `match image page takes the image dimensions with no letterbox`() {
        val (w, h) = pageDimensionsFor(PageSize.IMAGE, 1000, 4000)

        assertEquals(1000, w)
        assertEquals(4000, h)
    }

    @Test
    fun `match image caps the long side at the pdf limit`() {
        val (w, h) = pageDimensionsFor(PageSize.IMAGE, 8000, 20000)

        assertTrue(w <= 14400 && h <= 14400)
        assertEquals(14400, h)
        assertEquals(5760, w)
    }

    @Test
    fun `paper size keeps an upright image upright`() {
        val (w, h) = pageDimensionsFor(PageSize.A4, 1000, 2000)

        assertEquals(PageSize.A4.widthPt, w)
        assertEquals(PageSize.A4.heightPt, h)
    }

    @Test
    fun `paper size rotates a landscape image`() {
        val (w, h) = pageDimensionsFor(PageSize.A4, 2000, 1000)

        assertEquals(PageSize.A4.heightPt, w)
        assertEquals(PageSize.A4.widthPt, h)
    }
}
