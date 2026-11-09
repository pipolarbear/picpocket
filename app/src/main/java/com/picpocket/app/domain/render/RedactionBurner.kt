package com.picpocket.app.domain.render

import android.graphics.Bitmap
import com.picpocket.app.data.model.RedactionRect
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Replaces the marked areas of a page with a mosaic of large colour blocks.
 *
 * The original pixels are overwritten in a copy of the bitmap, so nothing
 * underneath survives into the generated PDF - unlike drawing a box on top,
 * which would leave the original image data in the file.
 */
@Singleton
class RedactionBurner @Inject constructor() {

    /**
     * Returns [source] itself when there is nothing to hide, otherwise a new
     * bitmap with every usable rectangle pixelated. The caller owns (and may
     * recycle) the returned bitmap; the input is never modified.
     */
    fun burn(source: Bitmap, rects: List<RedactionRect>): Bitmap {
        val usable = rects.filter { it.isUsable() }.map { it.normalized() }
        if (usable.isEmpty() || source.width <= 0 || source.height <= 0) return source

        val target = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        val width = target.width
        val height = target.height
        for (rect in usable) {
            val left = (rect.x * width).toInt().coerceIn(0, width - 1)
            val top = (rect.y * height).toInt().coerceIn(0, height - 1)
            val right = (rect.right * width).toInt().coerceIn(left + 1, width)
            val bottom = (rect.bottom * height).toInt().coerceIn(top + 1, height)
            pixelate(source, target, left, top, right, bottom)
        }
        return target
    }

    /**
     * Fills [left]..[right] x [top]..[bottom] with square blocks whose colour is
     * sampled from the middle of the block. The source is read, never written,
     * so overlapping areas do not feed on each other.
     */
    private fun pixelate(source: Bitmap, target: Bitmap, left: Int, top: Int, right: Int, bottom: Int) {
        val areaWidth = right - left
        val areaHeight = bottom - top
        if (areaWidth <= 0 || areaHeight <= 0) return

        val block = max(
            MIN_BLOCK_PX,
            (min(areaWidth, areaHeight) / BLOCKS_PER_SIDE).toInt(),
        ).coerceAtLeast(1)

        var y = top
        while (y < bottom) {
            val blockHeight = min(block, bottom - y)
            val row = IntArray(areaWidth)
            var x = 0
            var offset = 0
            while (x < areaWidth) {
                val blockWidth = min(block, areaWidth - x)
                val sampleX = left + x + blockWidth / 2
                val sampleY = min(y + blockHeight / 2, bottom - 1)
                val color = source.getPixel(sampleX.coerceAtMost(source.width - 1), sampleY)
                java.util.Arrays.fill(row, offset, offset + blockWidth, color)
                offset += blockWidth
                x += blockWidth
            }
            for (rowIndex in y until y + blockHeight) {
                target.setPixels(row, 0, areaWidth, left, rowIndex, areaWidth, 1)
            }
            y += blockHeight
        }
    }

    private companion object {
        /** How many blocks span the shorter side of an area. */
        const val BLOCKS_PER_SIDE = 12

        /** Never use blocks smaller than this, so text cannot be read back. */
        const val MIN_BLOCK_PX = 6
    }
}
