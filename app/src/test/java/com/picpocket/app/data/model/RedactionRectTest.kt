package com.picpocket.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionRectTest {

    @Test
    fun `a drag from any corner produces a positive rectangle`() {
        val rect = RedactionRect.fromDrag(0.8f, 0.9f, 0.2f, 0.1f)
        assertEquals(0.2f, rect.x, 0.0001f)
        assertEquals(0.1f, rect.y, 0.0001f)
        assertEquals(0.6f, rect.w, 0.0001f)
        assertEquals(0.8f, rect.h, 0.0001f)
    }

    @Test
    fun `coordinates are clamped into the page`() {
        val rect = RedactionRect(-0.5f, -0.5f, 2f, 2f).normalized()
        assertEquals(0f, rect.x, 0.0001f)
        assertEquals(0f, rect.y, 0.0001f)
        assertEquals(1f, rect.w, 0.0001f)
        assertEquals(1f, rect.h, 0.0001f)
    }

    @Test
    fun `tiny areas are not usable`() {
        assertFalse(RedactionRect(0.1f, 0.1f, 0.001f, 0.001f).isUsable())
        assertTrue(RedactionRect(0.1f, 0.1f, 0.2f, 0.2f).isUsable())
    }
}
