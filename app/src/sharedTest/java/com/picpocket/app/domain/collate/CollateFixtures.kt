package com.picpocket.app.domain.collate

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Deterministic fixtures for collate tests: a source image with a unique gray
 * value per row/column (so alignment has a single correct answer) and a colored
 * marker used to prove overlap content appears once.
 */
object CollateFixtures {

    const val MARKER_COLOR = 0xFF00FF00.toInt() // green

    /** A vertical-gradient source of [width] x [height] with a marker band at [markerY]. */
    fun verticalSource(width: Int = 40, height: Int = 200, markerY: Int = 100, markerHeight: Int = 3): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            val gray = y * 255 / height
            val color = if (y in markerY until markerY + markerHeight) MARKER_COLOR
            else Color.rgb(gray, gray, gray)
            for (x in 0 until width) bmp.setPixel(x, y, color)
        }
        return bmp
    }

    /** A horizontal-gradient source (unique gray per column) with a marker band at [markerX]. */
    fun horizontalSource(width: Int = 200, height: Int = 40, markerX: Int = 100, markerWidth: Int = 3): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            val gray = x * 255 / width
            val color = if (x in markerX until markerX + markerWidth) MARKER_COLOR
            else Color.rgb(gray, gray, gray)
            for (y in 0 until height) bmp.setPixel(x, y, color)
        }
        return bmp
    }

    fun cropVertical(source: Bitmap, fromY: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(source.width, height, Bitmap.Config.ARGB_8888)
        val px = IntArray(source.width * height)
        source.getPixels(px, 0, source.width, 0, fromY, source.width, height)
        out.setPixels(px, 0, source.width, 0, 0, source.width, height)
        return out
    }

    /** A deterministic pseudo-random texture whose row/column means are flat, so
     *  a 1-D profile matcher cannot align it but the 2-D content can. */
    fun patternSource(width: Int = 40, height: Int = 200, seed: Int = 12345): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var state = seed
        for (y in 0 until height) {
            for (x in 0 until width) {
                state = state * 1103515245 + 12345
                val v = (state ushr 16) and 0xFF
                bmp.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bmp
    }

    fun cropHorizontal(source: Bitmap, fromX: Int, width: Int): Bitmap {
        val out = Bitmap.createBitmap(width, source.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(width * source.height)
        source.getPixels(px, 0, width, fromX, 0, width, source.height)
        out.setPixels(px, 0, width, 0, 0, width, source.height)
        return out
    }

    /** Counts pixels matching [color] (with a small tolerance) in [bitmap]. */
    fun countColor(bitmap: Bitmap, color: Int, tolerance: Int = 8): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return pixels.count { p ->
            kotlin.math.abs(((p shr 16) and 0xFF) - r) <= tolerance &&
                kotlin.math.abs(((p shr 8) and 0xFF) - g) <= tolerance &&
                kotlin.math.abs((p and 0xFF) - b) <= tolerance
        }
    }
}
