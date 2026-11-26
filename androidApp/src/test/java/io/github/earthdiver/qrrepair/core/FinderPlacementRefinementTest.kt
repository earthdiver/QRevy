package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.hypot

/** A tapped Finder is refined only to a center that the image supports better than the tap itself. */
class FinderPlacementRefinementTest {
    private val version = 3
    private val n = 17 + 4 * version
    private val unit = 9
    private val margin = 4 * unit
    private val size = n * unit + 2 * margin

    private fun render(dottedFinderCenters: Boolean): LumaImage {
        fun black(x: Int, y: Int): Boolean {
            for ((sx, sy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val dx = x - sx; val dy = y - sy
                if (dx in -1..7 && dy in -1..7) {
                    if (dx !in 0..6 || dy !in 0..6) return false
                    if (dx == 0 || dy == 0 || dx == 6 || dy == 6) return true
                    if (dx in 2..4 && dy in 2..4) return !dottedFinderCenters || (dx == 3 && dy == 3)
                    return false
                }
            }
            if (x == 6 && y in 8..n - 9) return y % 2 == 0
            if (y == 6 && x in 8..n - 9) return x % 2 == 0
            return (x * 17 + y * 11 + x * y) % 7 < 3
        }
        return LumaImage(size, size, IntArray(size * size) { i ->
            val x = floor(((i % size) - margin).toDouble() / unit).toInt()
            val y = floor(((i / size) - margin).toDouble() / unit).toInt()
            if (x in 0 until n && y in 0 until n && black(x, y)) 15 else 240
        })
    }

    private fun center(mx: Double, my: Double) = PointD(margin + (mx + .5) * unit, margin + (my + .5) * unit)
    private val trueQuad = ImageQuad(center(0.0, 0.0), center(n - 1.0, 0.0), center(n - 1.0, n - 1.0), center(0.0, n - 1.0))

    private fun settings(quad: ImageQuad, assigned: Boolean) = PreprocessSettings(version = version,
        automaticThresholds = false, blackMax = 96, whiteMin = 160, manualQuad = quad, logicalGridAssigned = assigned)

    @Test fun outsideTapRequiresConfirmationEvenWhenTheGridPitchIsTooLarge() {
        val width = 300
        val image = LumaImage(width, width, IntArray(width * width) { index ->
            val column = (index % width - 60)
            val row = (index / width - 60)
            if (column in 0..41 && row in 0..41 &&
                (column / 6 in listOf(0, 6) || row / 6 in listOf(0, 6) ||
                    (column / 6 in 2..4 && row / 6 in 2..4))) 0 else 255
        })
        for (pitch in listOf(6.0, 7.5, 8.0)) {
            val first = 80.5 - 3 * pitch
            val last = first + 20 * pitch
            val quad = ImageQuad(PointD(first, first), PointD(last, first), PointD(last, last), PointD(first, last))
            val profile = PreprocessSettings(version = 1, manualQuad = quad, logicalGridAssigned = true,
                automaticThresholds = false, blackMax = 96, whiteMin = 160)
            val result = refineFinderPlacement(image, profile, PointD(104.5, 80.5), FinderRole.TOP_LEFT)
            assertEquals("pitch $pitch", FinderRefinementOutcome.REFINED, result.outcome)
            assertTrue("pitch $pitch", result.needsConfirmation)
        }
    }

    @Test fun damagedFinderKeepsTheTappedCenterInsteadOfMovingToAnotherPattern() {
        val image = render(dottedFinderCenters = true)
        // A coarse default grid, as before any grid estimate, and the true grid after role assignment.
        val coarse = ImageQuad(PointD(size * .1, size * .1), PointD(size * .9, size * .1),
            PointD(size * .9, size * .9), PointD(size * .1, size * .9))
        for ((quad, assigned) in listOf(coarse to false, trueQuad to true)) {
            for ((mx, my) in listOf(3.0 to 3.0, n - 4.0 to 3.0, 3.0 to n - 4.0)) {
                val tap = center(mx, my)
                val result = refineFinderPlacement(image, settings(quad, assigned), tap)
                result.candidate?.let { moved ->
                    assertTrue("$result moved from $tap", hypot(moved.centerX - tap.x, moved.centerY - tap.y) <= unit * .5)
                }
            }
        }
    }

    @Test fun intactFinderIsRefinedFromAnOffCenterTap() {
        val image = render(dottedFinderCenters = false)
        val truth = center(3.0, 3.0)
        for (assigned in listOf(false, true)) {
            val near = refineFinderPlacement(image, settings(trueQuad, assigned), PointD(truth.x + unit * .3, truth.y - unit * .2))
            assertEquals(FinderRefinementOutcome.REFINED, near.outcome)
            val refined = requireNotNull(near.candidate)
            assertTrue(hypot(refined.centerX - truth.x, refined.centerY - truth.y) < unit * .25)
            assertFalse(near.needsConfirmation)

            // Any tap on the Finder's 7x7 modules, up to its outer ring and corners, moves without confirmation.
            for ((dx, dy) in listOf(2.6 to 2.4, 3.3 to 3.3, -3.3 to 0.0, 0.0 to 3.4)) {
                val onFinder = refineFinderPlacement(image, settings(trueQuad, assigned), PointD(truth.x + unit * dx, truth.y + unit * dy))
                assertEquals("($dx, $dy)", FinderRefinementOutcome.REFINED, onFinder.outcome)
                val fromRing = requireNotNull(onFinder.candidate)
                assertTrue("($dx, $dy)", hypot(fromRing.centerX - truth.x, fromRing.centerY - truth.y) < unit * .25)
                assertFalse("($dx, $dy)", onFinder.needsConfirmation)
            }
            // A tap just outside the Finder is not taken as on it; the nearby center is confirmed first.
            for ((dx, dy) in listOf(4.0 to 0.0, 0.0 to 4.2, 3.8 to 3.8)) {
                val outside = refineFinderPlacement(image, settings(trueQuad, assigned), PointD(truth.x + unit * dx, truth.y + unit * dy))
                if (outside.outcome != FinderRefinementOutcome.REFINED) continue
                assertTrue("($dx, $dy) ${outside.candidate} ${outside.displacementModules}", outside.needsConfirmation)
            }
        }
    }

    @Test fun assignedGridLimitsTheSearchToTheFinderNeighborhood() {
        val image = render(dottedFinderCenters = false)
        // Far from any Finder, inside the data region: no Finder may be pulled in from elsewhere.
        val tap = center(n / 2.0, n / 2.0)
        val result = refineFinderPlacement(image, settings(trueQuad, assigned = true), tap)
        result.candidate?.let { moved ->
            assertTrue("$result", hypot(moved.centerX - tap.x, moved.centerY - tap.y) <= unit * FINDER_SEARCH_RADIUS_MODULES)
        }
    }

    private val roleCenters = mapOf(FinderRole.TOP_LEFT to (3.0 to 3.0), FinderRole.TOP_RIGHT to (n - 4.0 to 3.0),
        FinderRole.BOTTOM_LEFT to (3.0 to n - 4.0))
    /** Every module of a Finder, including its rings. */
    private val denseTapOffsets = (-3..3).flatMap { dy -> (-3..3).map { dx -> dx.toDouble() to dy.toDouble() } }
    private val tapOffsets = listOf(0.0 to 0.0, 1.5 to 1.5, -2.5 to 0.0, 0.0 to 2.5, 3.0 to -3.0) +
        listOf(4.0, -4.0, 4.8).flatMap { listOf(it to 0.0, 0.0 to it) } + listOf(4.0 to 4.0, -4.0 to 4.0, 4.0 to -4.0, -4.0 to -4.0, 4.5 to 1.0)

    @Test fun finderWithASimplifiedCoreNeverMovesAwayFromItsCenter() {
        // Finders whose 3x3 core is a single module are not found as Finders. Neither a data pattern nor
        // the light ring around the dot, which matches an inverted core, may replace the Finder center.
        val image = render(dottedFinderCenters = true)
        for (assigned in listOf(true, false)) for ((role, module) in roleCenters) for ((dx, dy) in tapOffsets + denseTapOffsets) {
            val truth = center(module.first, module.second)
            val tap = PointD(truth.x + unit * dx, truth.y + unit * dy)
            val result = refineFinderPlacement(image, settings(trueQuad, assigned), tap, role,
                physicalGrid = trueQuad.takeIf { !assigned })
            val label = "assigned=$assigned $role tap ($dx, $dy): $result"
            result.candidate?.let { moved ->
                assertTrue(label, hypot(moved.centerX - truth.x, moved.centerY - truth.y) <= unit * .5)
                assertEquals(label, FinderPolarity.NORMAL, moved.polarity)
            }
        }
    }

    @Test fun estimatedGridWithoutOrientationRefinesTapsOnAndAroundIntactFinders() {
        val image = render(dottedFinderCenters = false)
        for ((role, module) in roleCenters) for ((dx, dy) in tapOffsets) {
            val truth = center(module.first, module.second)
            val tap = PointD(truth.x + unit * dx, truth.y + unit * dy)
            val result = refineFinderPlacement(image, settings(trueQuad, assigned = false), tap, role, physicalGrid = trueQuad)
            val label = "$role tap ($dx, $dy): $result"
            val moved = result.candidate ?: continue
            assertTrue(label, hypot(moved.centerX - truth.x, moved.centerY - truth.y) < unit * .25)
            // On the Finder the center is applied directly; from outside it is confirmed first.
            assertEquals(label, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) > 3.5, result.needsConfirmation)
        }
        // Taps on the Finder itself are always refined.
        for ((role, module) in roleCenters) for ((dx, dy) in listOf(0.0 to 0.0, 1.5 to 1.5, 3.0 to -3.0)) {
            val truth = center(module.first, module.second)
            val result = refineFinderPlacement(image, settings(trueQuad, assigned = false),
                PointD(truth.x + unit * dx, truth.y + unit * dy), role, physicalGrid = trueQuad)
            assertEquals("$role ($dx, $dy)", FinderRefinementOutcome.REFINED, result.outcome)
        }
    }
}
