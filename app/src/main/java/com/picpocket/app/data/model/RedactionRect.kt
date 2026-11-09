package com.picpocket.app.data.model

import kotlinx.serialization.Serializable

/**
 * An area to hide when a document is exported or shared "redacted".
 *
 * The four values are fractions of the page width and height (0..1), measured
 * from the top-left of the page as it is displayed and stored. Fractions keep
 * the area valid at any render resolution, for scanned pages and pages of an
 * imported PDF alike.
 */
@Serializable
data class RedactionRect(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h

    /** True when the rectangle is big enough to be worth hiding. */
    fun isUsable(): Boolean = w > MIN_SIDE && h > MIN_SIDE

    /**
     * Clamps the rectangle into the page and makes the width/height positive, so
     * a drag that started in a corner always stores a usable area.
     */
    fun normalized(): RedactionRect {
        val left = minOf(x, right).coerceIn(0f, 1f)
        val top = minOf(y, bottom).coerceIn(0f, 1f)
        val rightEdge = maxOf(x, right).coerceIn(0f, 1f)
        val bottomEdge = maxOf(y, bottom).coerceIn(0f, 1f)
        return RedactionRect(left, top, rightEdge - left, bottomEdge - top)
    }

    companion object {
        const val MIN_SIDE = 0.004f

        fun fromDrag(startX: Float, startY: Float, endX: Float, endY: Float): RedactionRect =
            RedactionRect(startX, startY, endX - startX, endY - startY).normalized()
    }
}
