package com.picpocket.app.domain.collate

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CollateLayout { AUTO, HORIZONTAL, VERTICAL }

enum class CollateAxis { VERTICAL, HORIZONTAL }

/** Where a source image sits on the merged canvas. */
data class CollatePlacement(val x: Int, val y: Int)

sealed interface CollateResult {
    data class Success(
        val bitmap: Bitmap,
        val placements: List<CollatePlacement>,
        val axis: CollateAxis,
    ) : CollateResult

    data class Failure(val reason: String) : CollateResult
}

/**
 * Merges several page images of one flat surface into a single continuous image.
 *
 * Translation-only, planar alignment: consecutive images are placed so their
 * overlapping bands match, then composited with a feathered seam. No non-planar
 * (curl/crease) correction is attempted. Intentionally dependency-free.
 */
object Collate {

    const val MAX_DIMENSION = 16000
    const val MIN_OVERLAP_FRACTION = 0.08
    const val MAX_MEAN_ABS_DIFF = 40.0
    private const val ALIGN_MAX_DIM = 240
    private const val MIN_REGION_STDDEV = 6.0

    fun exceedsLimit(width: Int, height: Int): Boolean =
        width > MAX_DIMENSION || height > MAX_DIMENSION

    /** Aligns the sources and composites them into one bitmap. */
    fun collate(sources: List<Bitmap>, layout: CollateLayout): CollateResult {
        if (sources.size < 2) return CollateResult.Failure("Select at least two pages")
        return when (val placed = place(sources, layout)) {
            is PlacementResult.Failure -> CollateResult.Failure(placed.reason)
            is PlacementResult.Success -> {
                val composed = compose(sources, placed.placements)
                    ?: return CollateResult.Failure("Merged image is too large")
                CollateResult.Success(composed, placed.placements, placed.axis)
            }
        }
    }

    sealed interface PlacementResult {
        data class Success(val placements: List<CollatePlacement>, val axis: CollateAxis) : PlacementResult
        data class Failure(val reason: String) : PlacementResult
    }

    /** Computes placements only, so the UI can nudge and re-compose without re-aligning. */
    fun place(sources: List<Bitmap>, layout: CollateLayout): PlacementResult {
        return when (layout) {
            CollateLayout.HORIZONTAL ->
                PlacementResult.Success(placeHorizontally(sources), CollateAxis.HORIZONTAL)
            CollateLayout.VERTICAL ->
                PlacementResult.Success(placeVertically(sources), CollateAxis.VERTICAL)
            CollateLayout.AUTO -> strip(sources)
        }
    }

    /**
     * Scales each source so its cross-axis extent (width for a vertical stack,
     * height for a horizontal stack) equals the median across [sources],
     * preserving aspect ratio. Returns the inputs unchanged when fewer than two
     * sources, a non-positive size, or the sizes already agree.
     */
    fun normalize(sources: List<Bitmap>, axis: CollateAxis): List<Bitmap> {
        if (sources.size < 2) return sources
        val cross = sources.map { crossExtent(it, axis) }
        val median = cross.sorted()[cross.size / 2]
        if (median <= 0 || cross.all { it == median }) return sources
        return sources.mapIndexed { index, bmp ->
            val extent = cross[index]
            if (extent == median) {
                bmp
            } else {
                val scale = median.toFloat() / extent
                val w = if (axis == CollateAxis.VERTICAL) median else max(1, (bmp.width * scale).roundToInt())
                val h = if (axis == CollateAxis.VERTICAL) max(1, (bmp.height * scale).roundToInt()) else median
                Bitmap.createScaledBitmap(bmp, w, h, true)
            }
        }
    }

    private fun crossExtent(bitmap: Bitmap, axis: CollateAxis): Int =
        if (axis == CollateAxis.VERTICAL) bitmap.width else bitmap.height

    /**
     * Returns [placements] with every entry from [fromIndex] on shifted along
     * [axis] by [delta]. Used by the seam editor: moving the joint before source
     * [fromIndex] shifts that source and everything after it.
     */
    fun shiftTail(
        placements: List<CollatePlacement>,
        axis: CollateAxis,
        fromIndex: Int,
        delta: Int,
    ): List<CollatePlacement> {
        if (delta == 0 || fromIndex !in 1 until placements.size) return placements
        return placements.mapIndexed { index, placement ->
            when {
                index < fromIndex -> placement
                axis == CollateAxis.VERTICAL -> placement.copy(y = placement.y + delta)
                else -> placement.copy(x = placement.x + delta)
            }
        }
    }

    private fun placeHorizontally(sources: List<Bitmap>): List<CollatePlacement> {
        var x = 0
        val placements = mutableListOf<CollatePlacement>()
        for (source in sources) {
            placements.add(CollatePlacement(x, 0))
            x += source.width
        }
        return placements
    }

    private fun placeVertically(sources: List<Bitmap>): List<CollatePlacement> {
        var y = 0
        val placements = mutableListOf<CollatePlacement>()
        for (source in sources) {
            placements.add(CollatePlacement(0, y))
            y += source.height
        }
        return placements
    }

    private fun strip(sources: List<Bitmap>): PlacementResult {
        val vertical = runCatching { chain(sources, CollateAxis.VERTICAL) }.getOrNull()
        val horizontal = runCatching { chain(sources, CollateAxis.HORIZONTAL) }.getOrNull()
        val best = listOfNotNull(vertical, horizontal).minByOrNull { it.score }
            ?: return PlacementResult.Failure("Could not align the selected pages")
        val (w, h) = boundingBox(sources, best.placements)
        if (exceedsLimit(w, h)) return PlacementResult.Failure("Merged image is too large")
        return PlacementResult.Success(best.placements, best.axis)
    }

    private data class Chain(val placements: List<CollatePlacement>, val axis: CollateAxis, val score: Double)

    private fun chain(sources: List<Bitmap>, axis: CollateAxis): Chain {
        val placements = mutableListOf(CollatePlacement(0, 0))
        var score = 0.0
        for (i in 1 until sources.size) {
            val prev = sources[i - 1]
            val cur = sources[i]
            val overlap = bestOverlap(prev, cur, axis) ?: throw IllegalStateException("no overlap")
            score += overlap.difference
            val p = placements[i - 1]
            placements.add(
                if (axis == CollateAxis.VERTICAL) CollatePlacement(p.x, p.y + prev.height - overlap.pixels)
                else CollatePlacement(p.x + prev.width - overlap.pixels, p.y),
            )
        }
        return Chain(placements, axis, score)
    }

    private data class Overlap(val pixels: Int, val difference: Double)

    private class Gray(val px: IntArray, val w: Int, val h: Int)

    /**
     * Finds how many pixels the two images overlap along [axis] by comparing the
     * actual 2-D grayscale overlap band (brightness-normalized), not a 1-D
     * row/column profile. The band must carry structure (variance), so flat
     * regions (a solid black against a solid white) never match.
     */
    private fun bestOverlap(a: Bitmap, b: Bitmap, axis: CollateAxis): Overlap? {
        val extent = if (axis == CollateAxis.VERTICAL) min(a.height, b.height) else min(a.width, b.width)
        val minOverlap = max(1, (extent * MIN_OVERLAP_FRACTION).toInt())
        if (minOverlap >= extent) return null

        val scale = min(1f, ALIGN_MAX_DIM.toFloat() / max(max(a.width, a.height), max(b.width, b.height)))
        val ga = gray(a, scale)
        val gb = gray(b, scale)
        val axisLen = min(
            if (axis == CollateAxis.VERTICAL) ga.h else ga.w,
            if (axis == CollateAxis.VERTICAL) gb.h else gb.w,
        )
        val cross = min(
            if (axis == CollateAxis.VERTICAL) ga.w else ga.h,
            if (axis == CollateAxis.VERTICAL) gb.w else gb.h,
        )
        val minDs = max(1, (minOverlap * scale).toInt())
        if (minDs >= axisLen || cross <= 0) return null

        val step = max(1, (axisLen - minDs) / 200)
        var bestDs = -1
        var bestDiff = Double.MAX_VALUE
        var o = minDs
        while (o <= axisLen) {
            val diff = regionDifference(ga, gb, axis, o, cross)
            if (diff != null && diff < bestDiff) {
                bestDiff = diff
                bestDs = o
            }
            if (bestDs >= 0 && bestDiff < 0.5) break
            o += step
        }
        if (bestDs < 0 || bestDiff > MAX_MEAN_ABS_DIFF) return null
        val pixels = kotlin.math.ceil(bestDs / scale).toInt().coerceIn(minOverlap, extent)
        return Overlap(pixels, bestDiff)
    }

    /**
     * Mean absolute difference between the last [o] rows/columns of [a] and the
     * first [o] of [b]. Returns null when the band is too flat to align on, so
     * featureless regions (a solid black against a solid white) never match.
     */
    private fun regionDifference(a: Gray, b: Gray, axis: CollateAxis, o: Int, cross: Int): Double? {
        val aStart = if (axis == CollateAxis.VERTICAL) a.h - o else a.w - o
        var sumA = 0L
        var sumSqA = 0L
        var acc = 0L
        val count = o * cross
        for (i in 0 until o) {
            for (j in 0 until cross) {
                val va = value(a, axis, aStart + i, j)
                val vb = value(b, axis, i, j)
                sumA += va
                sumSqA += va.toLong() * va
                acc += abs(va - vb)
            }
        }
        val meanA = sumA.toDouble() / count
        val varianceA = sumSqA.toDouble() / count - meanA * meanA
        if (varianceA < MIN_REGION_STDDEV * MIN_REGION_STDDEV) return null
        return acc.toDouble() / count
    }

    private fun value(g: Gray, axis: CollateAxis, along: Int, across: Int): Int =
        if (axis == CollateAxis.VERTICAL) g.px[along * g.w + across] else g.px[across * g.w + along]

    private fun gray(bitmap: Bitmap, scale: Float): Gray {
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).toInt()),
                max(1, (bitmap.height * scale).toInt()),
                true,
            )
        } else {
            bitmap
        }
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        if (bmp !== bitmap) bmp.recycle()
        return Gray(out, w, h)
    }

    private fun boundingBox(sources: List<Bitmap>, placements: List<CollatePlacement>): Pair<Int, Int> {
        var w = 0
        var h = 0
        sources.forEachIndexed { index, bmp ->
            val p = placements[index]
            w = max(w, p.x + bmp.width)
            h = max(h, p.y + bmp.height)
        }
        return w to h
    }

    /**
     * Draws the sources at their placements in order, so any region covered by
     * more than one source appears once. (Seam feathering across exposure
     * differences is a planned refinement; v1 composites opaquely, which is
     * exact when the overlap content is identical.)
     */
    fun compose(sources: List<Bitmap>, placements: List<CollatePlacement>): Bitmap? {
        if (sources.size != placements.size) return null
        val (w, h) = boundingBox(sources, placements)
        if (w <= 0 || h <= 0 || exceedsLimit(w, h)) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        sources.forEachIndexed { index, bmp ->
            val p = placements[index]
            canvas.drawBitmap(bmp, p.x.toFloat(), p.y.toFloat(), null)
        }
        return out
    }
}
