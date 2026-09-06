package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinderDetectorRegressionTest {
    @Test
    fun detectsFinderRotatedInCameraPlane() {
        val size = 180
        val center = 90.0
        val pitch = 10.0
        val angle = 27.0 * kotlin.math.PI / 180.0
        val c = cos(angle); val s = sin(angle)
        val pixels = IntArray(size * size) { index ->
            val x = index % size - center
            val y = index / size - center
            val mx = (c * x + s * y) / pitch + 3.5
            val my = (-s * x + c * y) / pitch + 3.5
            val ix = floor(mx).toInt(); val iy = floor(my).toInt()
            val black = ix in 0..6 && iy in 0..6 &&
                (ix == 0 || ix == 6 || iy == 0 || iy == 6 || (ix in 2..4 && iy in 2..4))
            if (black) 0 else 255
        }

        val finder = FinderDetector(LumaImage(size, size, pixels)).detect(4).firstOrNull()
        assertTrue(finder != null)
        assertTrue(PointD(finder!!.centerX, finder.centerY).distanceTo(PointD(center, center)) < pitch)
    }

    @Test
    fun nearbyCompleteCrossDoesNotPullFinderCenter() {
        assertTrue(shouldMergeFinderSeeds(100.0, 100.0, 10.0, 106.0, 103.0, 10.0))
        assertFalse(shouldMergeFinderSeeds(100.0, 100.0, 10.0, 112.0, 108.0, 10.0))
    }

    @Test
    fun binaryOtsuThresholdStillTreatsZeroAsBlack() {
        val image = pureFinderImage(scale = 10)
        assertEquals(0, image.threshold)
        assertTrue(image.isBlack(50, 50), "0-luma pixels must remain black when Otsu threshold is 0")
    }

    @Test
    fun finderRefinementPrefersRunPitchWhenTemplateScoresTie() {
        val image = pureFinderImage(scale = 10)
        val finder = FinderDetector(image).detect(maxCandidates = 2).firstOrNull()
        assertTrue(finder != null, "finder should be detected in a binary image")
        assertTrue(abs(finder!!.moduleSize - 10.0) < 0.75, "module pitch drifted to ${finder.moduleSize}")
        assertTrue(
            PointD(finder.centerX, finder.centerY).distanceTo(PointD(85.0, 85.0)) < .75,
            "complete finder center drifted to (${finder.centerX}, ${finder.centerY})",
        )
    }

    @Test
    fun nearbyPartialCoreCannotDisplaceCompleteFinderCandidate() {
        val base = pureFinderImage(scale = 10)
        val pixels = base.pixels.copyOf()
        // Add a separate 1:3:1 cross less than 2.5 finder modules away. It is deliberately a
        // plausible weak seed but must not be averaged into the complete finder's raw center.
        fun set(x: Int, y: Int, value: Int) { if (x in 0 until base.width && y in 0 until base.height) pixels[y * base.width + x] = value }
        val weakCenter = PointD(108.0, 108.0)
        for (dy in -20..20) for (dx in -20..20) {
            val horizontal = dy in -4..4 && (dx in -19..-15 || dx in -14..14 || dx in 15..19)
            val vertical = dx in -4..4 && (dy in -19..-15 || dy in -14..14 || dy in 15..19)
            if (horizontal || vertical) {
                val black = (horizontal && dx in -14..14) || (vertical && dy in -14..14)
                set(weakCenter.x.toInt() + dx, weakCenter.y.toInt() + dy, if (black) 0 else 255)
            }
        }

        val finder = FinderDetector(LumaImage(base.width, base.height, pixels)).detect(maxCandidates = 4)
            .minByOrNull { PointD(it.centerX, it.centerY).distanceTo(PointD(85.0, 85.0)) }
        assertTrue(finder != null)
        assertTrue(PointD(finder!!.centerX, finder.centerY).distanceTo(PointD(85.0, 85.0)) < 1.0)
    }

    @Test
    fun approximateTapIsRefinedToSubModuleFinderCenter() {
        val image = pureFinderImage(scale = 10)
        val expected = PointD(85.0, 85.0)
        val finder = FinderDetector(image).detectNear(PointD(108.0, 66.0), searchRadius = 45.0)

        assertTrue(finder != null, "finder should be recovered near a rough tap")
        assertTrue(PointD(finder!!.centerX, finder.centerY).distanceTo(expected) < 3.0,
            "rough tap was not refined: (${finder.centerX}, ${finder.centerY})")
        assertTrue(abs(finder.moduleSize - 10.0) < 0.75, "local pitch drifted to ${finder.moduleSize}")
    }

    @Test
    fun unknownGrayDoesNotMergeIntoBlackFinderRuns() {
        val image = pureFinderImage(scale = 10, background = 128)
        val finder = FinderDetector(image, blackThresholdOverride = 80, whiteThresholdOverride = 180)
            .detect(maxCandidates = 2).firstOrNull()

        assertTrue(finder != null, "a gray erasure surrounding a known finder must not be classified as black")
        assertTrue(PointD(finder!!.centerX, finder.centerY).distanceTo(PointD(85.0, 85.0)) < 3.0)
    }

    @Test
    fun finderWithMissingOuterRingIsStillDetectedFromCoreRatio() {
        val complete = pureFinderImage(scale = 10)
        val pixels = complete.pixels.copyOf()
        val size = complete.width
        val quiet = 5
        // Remove the complete outer black ring while preserving the 1:3:1 core.
        for (my in 0 until 7) for (mx in 0 until 7) {
            if (mx != 0 && mx != 6 && my != 0 && my != 6) continue
            val x0 = (mx + quiet) * 10
            val y0 = (my + quiet) * 10
            for (y in y0 until y0 + 10) for (x in x0 until x0 + 10) pixels[y * size + x] = 255
        }
        val finder = FinderDetector(LumaImage(size, size, pixels)).detect(maxCandidates = 2).firstOrNull()
        assertTrue(finder != null, "finder core should seed detection even when its outer ring is missing")
        assertTrue(PointD(finder!!.centerX, finder.centerY).distanceTo(PointD(85.0, 85.0)) < 3.0)
    }

    private fun pureFinderImage(scale: Int, background: Int = 255): LumaImage {
        val quiet = 5
        val modules = 7 + quiet * 2
        val size = modules * scale
        val pixels = IntArray(size * size) { background }
        for (my in 0 until 7) for (mx in 0 until 7) {
            val black = mx == 0 || mx == 6 || my == 0 || my == 6 || (mx in 2..4 && my in 2..4)
            val x0 = (mx + quiet) * scale
            val y0 = (my + quiet) * scale
            for (y in y0 until y0 + scale) for (x in x0 until x0 + scale) {
                pixels[y * size + x] = if (black) 0 else 255
            }
        }
        return LumaImage(size, size, pixels)
    }
}
