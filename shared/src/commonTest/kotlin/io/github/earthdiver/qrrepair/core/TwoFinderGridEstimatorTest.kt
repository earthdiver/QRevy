package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwoFinderGridEstimatorTest {
    private data class Fixture(val image: LumaImage, val quad: ImageQuad)

    /** Independently generated structure; no external image pixels or payload are used. */
    private fun fixture(version: Int, alignment: Boolean = true, rotate: Int = 0, largeOcclusion: Boolean = true,
        timing: String = "normal", ambiguous: Boolean = false): Fixture {
        val n = 17 + 4 * version
        val pitch = 6
        val left = 110; val top = 50
        val width = 620; val height = 460
        val pixels = IntArray(width * height) { 180 }
        for (y in top - 12 until top + n * pitch + 12) for (x in left - 12 until left + n * pitch + 12) pixels[y * width + x] = 250
        fun value(mx: Int, my: Int): Int {
            for ((fx, fy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val x = mx - fx; val y = my - fy
                if (x in -1..7 && y in -1..7) return if (x in 0..6 && y in 0..6 &&
                    (x == 0 || y == 0 || x == 6 || y == 6 || (x in 2..4 && y in 2..4))) 8 else 250
            }
            val ax = mx - (n - 7); val ay = my - (n - 7)
            if (abs(ax) <= 2 && abs(ay) <= 2) return if (alignment &&
                (abs(ax) == 2 || abs(ay) == 2 || (ax == 0 && ay == 0))) 8 else 250
            if (ambiguous && ((mx == n - 7 && my in 8..n - 9) || (my == n - 7 && mx in 8..n - 9))) {
                return if ((mx + my) % 2 == 0) 8 else 250
            }
            if (mx == 6 || my == 6) {
                if (timing == "unknown" || (timing == "one-axis" && mx == 6)) return 129
                if (timing == "partial" && maxOf(mx, my) in 10..13) return 129
                if (timing == "too-short" && maxOf(mx, my) < n - 15) return 129
                if (timing == "flat") return 8
                val even = (mx + my) % 2 == 0
                return if (even != (timing == "wrong-phase")) 8 else 250
            }
            return if ((mx * 19 + my * 13 + mx * my) % 11 < 5) 8 else 250
        }
        for (my in 0 until n) for (mx in 0 until n) for (dy in 0 until pitch) for (dx in 0 until pitch) {
            val v = value(mx, my)
            val timingCell = (mx == 6 && my in 8..n - 9) || (my == 6 && mx in 8..n - 9)
            pixels[(top + my * pitch + dy) * width + left + mx * pitch + dx] =
                if (timing == "unstable" && timingCell && dx == 4) 258 - v else v
        }
        // Large external occluder erases the upper-left Finder and invalidates whole-frame bounds.
        for (y in 0 until height) for (x in 0 until width) if (x + y < left + top + (if (largeOcclusion) n - 12 else 8) * pitch) pixels[y * width + x] = 8
        // Irregular dark marks outside the symbol, on a gray background.
        for (k in 0..9) for (y in 400..413) for (x in 370 + k * 23..374 + k * 23) pixels[y * width + x] = 8
        val a = left + (pitch - 1) / 2.0; val b = top + (pitch - 1) / 2.0
        val side = (n - 1) * pitch.toDouble()
        var q = ImageQuad(PointD(a, b), PointD(a + side, b), PointD(a + side, b + side), PointD(a, b + side))
        var image = LumaImage(width, height, pixels)
        repeat(rotate) {
            val rotated = IntArray(image.pixels.size)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                rotated[x * image.height + image.height - 1 - y] = image.pixels[y * image.width + x]
            }
            val corners = q.asList().map { PointD(image.height - 1 - it.y, it.x) }
            q = ImageQuad(corners[0], corners[1], corners[2], corners[3])
            image = LumaImage(image.height, image.width, rotated)
        }
        return Fixture(image, q)
    }

    @Test fun diagonalFindersRecoverCornersDespiteExternalOcclusionAndClutter() {
        for (version in listOf(2, 4, 7)) for (rotate in listOf(0, 1)) {
            val f = fixture(version, rotate = rotate)
            assertNull(GridPeriodicityEstimator.estimate(f.image), "Must reproduce whole-frame failure")
            val result = assertNotNull(InitialGridEstimator.estimate(f.image, emptyList()), "V$version rotated=$rotate")
            assertEquals(InitialGridSource.TWO_FINDER, result.source)
            assertEquals(version, result.version)
            for ((actual, expected) in result.moduleCenterQuad.asList().zip(f.quad.asList())) {
                assertTrue(actual.distanceTo(expected) < 1.5, "$actual vs $expected")
            }
            assertTrue(FinderRole.TOP_LEFT !in result.autoAssignedFinders, "A missing Finder must never be automatically confirmed")
        }
    }

    @Test fun invertedTwoFinderFallbackPreservesCornersAndOnlyConfirmsVerifiedRoles() {
        for (rotation in 0..3) for (timing in listOf("normal", "unknown")) {
            val f = fixture(4, rotate = rotation, largeOcclusion = false, timing = timing)
            val inverse = LumaImage(f.image.width, f.image.height,
                IntArray(f.image.pixels.size) { 255 - f.image.pixels[it] })
            val normal = assertNotNull(InitialGridEstimator.estimate(f.image, emptyList()))
            val inverted = assertNotNull(InitialGridEstimator.estimate(inverse, emptyList()), "rotation=$rotation timing=$timing")
            assertEquals(InitialGridSource.TWO_FINDER, inverted.source)
            assertEquals(normal.version, inverted.version)
            assertEquals(normal.moduleCenterQuad, inverted.moduleCenterQuad)
            assertEquals(normal.autoAssignedFinders, inverted.autoAssignedFinders)
            if (timing == "normal") {
                assertEquals(setOf(FinderRole.TOP_RIGHT, FinderRole.BOTTOM_LEFT), inverted.autoAssignedFinders.keys)
                assertEquals(FinderPolarity.NORMAL, normal.confirmedPolarity)
                assertEquals(FinderPolarity.INVERTED, inverted.confirmedPolarity)
            } else {
                assertTrue(inverted.autoAssignedFinders.isEmpty())
                assertEquals(FinderPolarity.UNKNOWN, inverted.confirmedPolarity)
            }
        }
    }

    @Test fun twoFindersWithoutAlignmentEvidenceAreNotEnough() {
        assertNull(TwoFinderGridEstimator.estimate(fixture(4, alignment = false).image))
    }

    @Test fun versionOneCannotUseTheAlignmentBasedFallback() {
        assertNull(TwoFinderGridEstimator.estimate(fixture(1, alignment = false).image))
    }

    @Test fun diagonalTimingAssignsOnlyObservedRolesWithoutAlignmentAcrossRotations() {
        for (version in listOf(2, 4, 7)) for (rotation in 0..3) {
            val f = fixture(version, alignment = false, rotate = rotation, largeOcclusion = false)
            val result = assertNotNull(InitialGridEstimator.estimate(f.image, emptyList()), "V$version rotation=$rotation")
            assertEquals(version, result.version)
            assertEquals(setOf(FinderRole.TOP_RIGHT, FinderRole.BOTTOM_LEFT), result.autoAssignedFinders.keys)
            val h = Homography.fromCorrespondences(
                listOf(PointD(0.0, 0.0), PointD((16 + 4 * version).toDouble(), 0.0),
                    PointD((16 + 4 * version).toDouble(), (16 + 4 * version).toDouble()), PointD(0.0, (16 + 4 * version).toDouble())),
                f.quad.asList())
            for ((role, actual) in result.autoAssignedFinders) {
                val n = 17 + 4 * version
                val expected = if (role == FinderRole.TOP_RIGHT) h.map((n - 4).toDouble(), 3.0) else h.map(3.0, (n - 4).toDouble())
                assertTrue(actual.distanceTo(expected) < 1.5, "$role $actual vs $expected")
            }
            for ((actual, expected) in result.moduleCenterQuad.asList().zip(f.quad.asList())) {
                assertTrue(actual.distanceTo(expected) < 1.5)
            }
        }
    }

    @Test fun unknownTimingCellsAreNotPositiveEvidenceButEnoughVisibleCellsCanAssign() {
        val partial = fixture(4, alignment = false, largeOcclusion = false, timing = "partial")
        assertEquals(2, assertNotNull(TwoFinderGridEstimator.estimate(partial.image)).autoAssignedFinders.size)
        for (damage in listOf("unknown", "one-axis", "too-short", "flat", "wrong-phase", "unstable")) {
            val f = fixture(4, alignment = false, largeOcclusion = false, timing = damage)
            assertTrue(TwoFinderGridEstimator.estimate(f.image)?.autoAssignedFinders.orEmpty().isEmpty(), damage)
        }
    }

    @Test fun equallyPlausibleOppositeTimingDoesNotAssignRoles() {
        val f = fixture(4, alignment = false, largeOcclusion = false, ambiguous = true)
        assertTrue(TwoFinderGridEstimator.estimate(f.image)?.autoAssignedFinders.orEmpty().isEmpty())
    }

    @Test fun alignmentCanStillProposeGridWhenTimingIsUnknown() {
        val f = fixture(4, largeOcclusion = false, timing = "unknown")
        val result = assertNotNull(TwoFinderGridEstimator.estimate(f.image))
        assertTrue(result.autoAssignedFinders.isEmpty())
    }

    @Test fun uniformImageIsRejected() {
        assertNull(TwoFinderGridEstimator.estimate(LumaImage(140, 140, IntArray(19600) { 190 })))
    }
}
