package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectivePatternRefinerTest {
    @Test fun onlyFindersAndAlignmentFootprintsContributeIncludingAlignmentsOnTimingAxes() {
        for ((version, centers) in listOf(1 to intArrayOf(), 7 to intArrayOf(6, 22, 38))) {
            val seed = GridGeometry(version, FinderRole.TOP_LEFT, 34.0, 34.0, 6.0, 0.0).asProjective()
            val n = seed.dimension
            val size = (n + 7) * 6
            val image = LumaImage(size, size, IntArray(size * size) { i ->
                if ((((i % size + 5) / 6 + (i / size + 5) / 6) and 1) == 0) 20 else 235
            })
            val crop = ProjectivePatternImage.crop(image, seed.moduleCenterQuad(), 6.0, false) { false }!!
            val cells = ProjectivePatternTemplate(n, centers).observations(seed, crop, .035).map {
                Coord(((it.x + 1) * (n - 1) / 2).roundToInt(), ((it.y + 1) * (n - 1) / 2).roundToInt())
            }.toSet()
            assertTrue(Coord(3, 3) in cells && Coord(n - 4, 3) in cells && Coord(3, n - 4) in cells)
            val alignments = centers.flatMap { x -> centers.map { y -> Coord(x, y) } }.filterNot {
                (it.x == 6 && it.y in listOf(6, n - 7)) || (it.x == n - 7 && it.y == 6)
            }
            assertTrue(alignments.all { it in cells })
            assertTrue(cells.all { (x, y) ->
                (y < 8 && (x < 8 || x >= n - 8)) || (x < 8 && y >= n - 8) ||
                    alignments.any { abs(it.x - x) <= 2 && abs(it.y - y) <= 2 }
            })
        }
    }

    @Test fun timingStripesAloneCannotMoveTheGrid() {
        val seed = GridGeometry(1, FinderRole.TOP_LEFT, 34.0, 34.0, 6.0, 0.0).asProjective()
        val image = LumaImage(160, 160, IntArray(160 * 160) { i ->
            val x = ((i % 160 - 16) / 6.0).roundToInt()
            val y = ((i / 160 - 16) / 6.0).roundToInt()
            when {
                y == 6 && x in 8..12 -> 255 * (x and 1)
                x == 6 && y in 8..12 -> 255 * (y and 1)
                else -> 128
            }
        })
        assertNull(ProjectivePatternRefiner.refine(image, seed, emptyList(), intArrayOf(), false))
    }
}
