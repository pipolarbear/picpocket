package com.picpocket.app.domain.collate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollateEngineTest {

    private fun assertSuccess(result: CollateResult): CollateResult.Success {
        assertTrue("expected success, was $result", result is CollateResult.Success)
        return result as CollateResult.Success
    }

    @Test
    fun `auto merges a vertical overlap and the marker appears once`() {
        val source = CollateFixtures.verticalSource(width = 40, height = 200, markerY = 100)
        val a = CollateFixtures.cropVertical(source, fromY = 0, height = 120)
        val b = CollateFixtures.cropVertical(source, fromY = 80, height = 120)

        val result = assertSuccess(Collate.collate(listOf(a, b), CollateLayout.AUTO))

        assertEquals(CollateAxis.VERTICAL, result.axis)
        assertEquals(200, result.bitmap.height)
        assertEquals(40, result.bitmap.width)
        val marker = CollateFixtures.countColor(result.bitmap, CollateFixtures.MARKER_COLOR)
        assertTrue("marker should appear once (~120, was $marker)", marker in 100..132)
    }

    @Test
    fun `auto detects a horizontal overlap and merges along x`() {
        val source = CollateFixtures.horizontalSource(width = 200, height = 40, markerX = 100)
        val a = CollateFixtures.cropHorizontal(source, fromX = 0, width = 120)
        val b = CollateFixtures.cropHorizontal(source, fromX = 80, width = 120)

        val result = assertSuccess(Collate.collate(listOf(a, b), CollateLayout.AUTO))

        assertEquals(CollateAxis.HORIZONTAL, result.axis)
        assertEquals(200, result.bitmap.width)
        assertEquals(40, result.bitmap.height)
        val marker = CollateFixtures.countColor(result.bitmap, CollateFixtures.MARKER_COLOR)
        assertTrue("marker should appear once (~120, was $marker)", marker in 100..132)
    }

    @Test
    fun `auto chains three segments correctly`() {
        val source = CollateFixtures.verticalSource(width = 30, height = 300, markerY = 150)
        val a = CollateFixtures.cropVertical(source, fromY = 0, height = 140)
        val b = CollateFixtures.cropVertical(source, fromY = 100, height = 140)
        val c = CollateFixtures.cropVertical(source, fromY = 200, height = 100)

        val result = assertSuccess(Collate.collate(listOf(a, b, c), CollateLayout.AUTO))

        assertEquals(300, result.bitmap.height)
        val marker = CollateFixtures.countColor(result.bitmap, CollateFixtures.MARKER_COLOR)
        assertTrue("marker should appear once (~90, was $marker)", marker in 70..100)
    }

    @Test
    fun `auto aligns textured content whose row profile is flat`() {
        val source = CollateFixtures.patternSource(width = 48, height = 240)
        val a = CollateFixtures.cropVertical(source, fromY = 0, height = 140)
        val b = CollateFixtures.cropVertical(source, fromY = 100, height = 140)

        val result = assertSuccess(Collate.collate(listOf(a, b), CollateLayout.AUTO))

        assertEquals(CollateAxis.VERTICAL, result.axis)
        assertEquals(240, result.bitmap.height)
        assertEquals(48, result.bitmap.width)
    }

    @Test
    fun `unrelated images with no usable overlap fail in auto`() {
        val black = android.graphics.Bitmap.createBitmap(40, 200, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
        }
        val white = android.graphics.Bitmap.createBitmap(40, 200, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }

        val result = Collate.collate(listOf(black, white), CollateLayout.AUTO)

        assertTrue("expected failure, was $result", result is CollateResult.Failure)
    }

    @Test
    fun `horizontal places images next to each other without aligning`() {
        val a = CollateFixtures.verticalSource(width = 40, height = 80)
        val b = CollateFixtures.horizontalSource(width = 40, height = 80)

        val result = assertSuccess(Collate.collate(listOf(a, b), CollateLayout.HORIZONTAL))

        assertEquals(CollateAxis.HORIZONTAL, result.axis)
        assertEquals(80, result.bitmap.width)
        assertEquals(80, result.bitmap.height)
        assertEquals(
            listOf(CollatePlacement(0, 0), CollatePlacement(40, 0)),
            result.placements,
        )
    }

    @Test
    fun `vertical places images one above the other without aligning`() {
        val a = CollateFixtures.verticalSource(width = 40, height = 80)
        val b = CollateFixtures.verticalSource(width = 40, height = 80)

        val result = assertSuccess(Collate.collate(listOf(a, b), CollateLayout.VERTICAL))

        assertEquals(CollateAxis.VERTICAL, result.axis)
        assertEquals(40, result.bitmap.width)
        assertEquals(160, result.bitmap.height)
        assertEquals(
            listOf(CollatePlacement(0, 0), CollatePlacement(0, 80)),
            result.placements,
        )
    }

    @Test
    fun `horizontal does not remove overlap`() {
        val source = CollateFixtures.verticalSource(width = 40, height = 200, markerY = 100)
        val a = CollateFixtures.cropVertical(source, fromY = 0, height = 120)
        val b = CollateFixtures.cropVertical(source, fromY = 80, height = 120)

        val result = assertSuccess(Collate.collate(listOf(a, b), CollateLayout.HORIZONTAL))

        // Plain placement: widths add up and the shared band is kept twice.
        assertEquals(80, result.bitmap.width)
        assertEquals(120, result.bitmap.height)
    }

    @Test
    fun `fewer than two sources fail`() {
        val a = CollateFixtures.verticalSource()
        assertTrue(Collate.collate(listOf(a), CollateLayout.AUTO) is CollateResult.Failure)
    }

    @Test
    fun `dimension guard rejects oversized output`() {
        assertTrue(Collate.exceedsLimit(Collate.MAX_DIMENSION + 1, 100))
        assertTrue(Collate.exceedsLimit(100, Collate.MAX_DIMENSION + 1))
        assertTrue(!Collate.exceedsLimit(1000, 1000))
    }

    @Test
    fun `compose honours explicit (nudged) placements`() {
        val a = CollateFixtures.verticalSource(width = 40, height = 200)
        val b = CollateFixtures.horizontalSource(width = 40, height = 200)

        val merged = Collate.compose(listOf(a, b), listOf(CollatePlacement(0, 0), CollatePlacement(10, 0)))

        assertEquals(50, merged!!.width)
        assertEquals(200, merged.height)
    }

    @Test
    fun `normalize scales widths to the median for a vertical stack`() {
        val narrow = CollateFixtures.verticalSource(width = 30, height = 60)
        val mid = CollateFixtures.verticalSource(width = 40, height = 60)
        val wide = CollateFixtures.verticalSource(width = 50, height = 60)

        val out = Collate.normalize(listOf(narrow, mid, wide), CollateAxis.VERTICAL)

        assertTrue("widths should all be the median 40", out.all { it.width == 40 })
        assertEquals(80, out[0].height)
        assertEquals(60, out[1].height)
        assertEquals(48, out[2].height)
    }

    @Test
    fun `normalize scales heights to the median for a horizontal stack`() {
        val a = CollateFixtures.horizontalSource(width = 60, height = 30)
        val b = CollateFixtures.horizontalSource(width = 60, height = 50)
        val c = CollateFixtures.horizontalSource(width = 60, height = 40)

        val out = Collate.normalize(listOf(a, b, c), CollateAxis.HORIZONTAL)

        assertTrue("heights should all be the median 40", out.all { it.height == 40 })
        assertEquals(80, out[0].width)
    }

    @Test
    fun `normalize returns inputs unchanged when sizes already agree`() {
        val a = CollateFixtures.verticalSource(width = 40, height = 60)
        val b = CollateFixtures.verticalSource(width = 40, height = 60)

        assertEquals(listOf(a, b), Collate.normalize(listOf(a, b), CollateAxis.VERTICAL))
    }

    @Test
    fun `shift tail moves only the placements after the joint`() {
        val placements = listOf(CollatePlacement(0, 0), CollatePlacement(0, 100), CollatePlacement(0, 200))

        val shifted = Collate.shiftTail(placements, CollateAxis.VERTICAL, fromIndex = 2, delta = 10)

        assertEquals(
            listOf(CollatePlacement(0, 0), CollatePlacement(0, 100), CollatePlacement(0, 210)),
            shifted,
        )
        assertEquals(placements, Collate.shiftTail(placements, CollateAxis.VERTICAL, fromIndex = 0, delta = 10))
    }
}
