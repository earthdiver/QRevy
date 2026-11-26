package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinderAnchoredPeriodicityTest {
    private fun render(margin: Int = 12, rotation: Int = 0, inverted: Boolean = false,
        timing: Boolean = true, finder: Boolean = true, extraFinder: Boolean = false,
        verticalTiming: Boolean = true, corruptTiming: Boolean = false): LumaImage {
        val n = 29
        val pitch = 8
        val size = n * pitch + margin * 2
        fun value(x: Int, y: Int): Int {
            if (x !in 0 until n || y !in 0 until n) return 245
            // Independently generated module pattern with erased outer columns and two Finders.
            if (x < 2 || (x < 8 && (y < 8 || y >= n - 8))) return 120
            if (x >= n - 8 && y < 8) {
                if (!finder) return 120
                val dx = x - (n - 7)
                if (dx == -1 || y == 7) return 245
                return if (dx == 0 || dx == 6 || y == 0 || y == 6 ||
                    (dx in 2..4 && y in 2..4)) 12 else 245
            }
            if ((y == 6 && x in 8..n - 9) || (x == 6 && y in 8..n - 9)) {
                if (!timing || (!verticalTiming && x == 6)) return 120
                return if (((x + y) % 2 == 0) != corruptTiming) 12 else 245
            }
            if (extraFinder && x in 10..18 && y in 10..18) {
                val dx = x - 11; val dy = y - 11
                if (dx !in 0..6 || dy !in 0..6) return 245
                return if (dx == 0 || dx == 6 || dy == 0 || dy == 6 ||
                    (dx in 2..4 && dy in 2..4)) 12 else 245
            }
            return if ((x * 13 + y * 19 + x * y * 3) % 11 < 5) 12 else 245
        }
        return LumaImage(size, size, IntArray(size * size) { i ->
            var x = i % size; var y = i / size
            repeat(rotation) { val oldX = x; x = y; y = size - 1 - oldX }
            val v = if (x < margin || y < margin) 245 else value((x - margin) / pitch, (y - margin) / pitch)
            if (inverted) 255 - v else v
        })
    }

    @Test fun survivingFinderAnchorsTheErasedOuterColumns() {
        for (margin in listOf(0, 12)) for (rotation in 0..3) for (inverted in listOf(false, true)) {
            val image = render(margin, rotation, inverted)
            val grid = assertNotNull(InitialGridEstimator.estimate(image, emptyList()),
                "margin=$margin rotation=$rotation inverted=$inverted")
            assertEquals(3, grid.version)
            assertEquals(InitialGridSource.PERIODICITY, grid.source)
            assertTrue(grid.autoAssignedFinders.isEmpty())
            assertEquals(FinderPolarity.UNKNOWN, grid.confirmedPolarity)
            val a = margin + 3.5; val b = margin + 227.5
            val expected = listOf(PointD(a, a), PointD(b, a), PointD(b, b), PointD(a, b))
            for (point in grid.moduleCenterQuad.asList())
                assertTrue(expected.any { it.distanceTo(point) < 1.0 }, "Unexpected corner $point")
        }
    }

    @Test fun grayTimingProvidesNoEvidenceForExtendingTheGrid() {
        assertNull(GridPeriodicityEstimator.estimate(render(timing = false)))
    }

    @Test fun erasedFinderCannotAnchorAnExtension() {
        assertNull(GridPeriodicityEstimator.estimate(render(finder = false)))
    }

    @Test fun contradictoryTimingCannotAnchorAnExtension() {
        assertNull(GridPeriodicityEstimator.estimate(render(corruptTiming = true)))
    }

    @Test fun oneSurvivingTimingAxisCanSupportTheFinder() {
        assertEquals(3, assertNotNull(InitialGridEstimator.estimate(render(verticalTiming = false), emptyList())).version)
    }

    @Test fun additionalFinderLikeContentDoesNotDisableTheSurvivingFinder() {
        val image = render(extraFinder = true)
        assertTrue(FinderDetector(image).detect(6).count { it.evidence == FinderEvidence.COMPLETE_CROSS && it.score >= 1.0 } >= 2)
        assertEquals(3, assertNotNull(InitialGridEstimator.estimate(image, emptyList())).version)
    }

    @Test fun quietZoneAliasInTheOppositePolarityDoesNotBlockTheSymbolGrid() {
        val image = render(margin = 40)
        val inverse = LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] })
        val normal = assertNotNull(GridPeriodicityEstimator.estimate(image))
        val inverted = assertNotNull(GridPeriodicityEstimator.estimate(inverse))
        assertEquals(3, normal.version)
        assertTrue(normal.version != inverted.version)
        // The larger grid lies on the uniform quiet zone, so it is not a competing symbol in either polarity.
        for (input in listOf(image, inverse)) {
            val result = assertNotNull(InitialGridEstimator.estimate(input, emptyList()))
            assertEquals(3, result.version)
            for ((actual, expected) in result.moduleCenterQuad.asList().zip(normal.moduleCenterQuad.asList()))
                assertTrue(actual.distanceTo(expected) < 1.0)
        }
    }
}
