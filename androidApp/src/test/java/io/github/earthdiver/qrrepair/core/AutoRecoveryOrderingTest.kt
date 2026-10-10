package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecoveryOrderingTest {
    private val quad = ImageQuad(PointD(10.0, 10.0), PointD(210.0, 10.0), PointD(210.0, 210.0), PointD(10.0, 210.0))

    @Test fun selectedVersionAndGridAreAlwaysUsed() {
        assertEquals(listOf(7), manualGeometrySeeds(PreprocessSettings(version = 7, manualQuad = quad)).map { it.first })
        assertEquals(19, manualGeometrySeeds(PreprocessSettings(version = 7, manualQuad = quad, searchQuadNeighborhood = true)).size)
        assertEquals(
            21,
            manualGeometrySeeds(
                PreprocessSettings(
                    version = 7,
                    manualQuad = quad,
                    manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(25.0, 25.0)),
                    searchQuadNeighborhood = true,
                ),
            ).size,
        )
        assertEquals(
            1,
            manualGeometrySeeds(
                PreprocessSettings(
                    version = 7,
                    manualQuad = quad,
                    manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(25.0, 25.0)),
                    searchQuadNeighborhood = false,
                ),
            ).size,
        )
        assertEquals(
            19,
            manualGeometrySeeds(
                PreprocessSettings(
                    version = 7,
                    manualQuad = quad,
                    manualFinders = mapOf(
                        FinderRole.TOP_LEFT to PointD(25.0, 25.0),
                        FinderRole.TOP_RIGHT to PointD(195.0, 25.0),
                    ),
                    searchQuadNeighborhood = true,
                ),
            ).size,
        )
        assertEquals(listOf(7), manualGeometrySeeds(PreprocessSettings(version = 7, manualQuad = quad, lockVersion = false)).map { it.first })
    }

    @Test fun manualQuadExploresBoundedTouchErrorNeighborhood() {
        val original = ImageQuad(PointD(10.0, 20.0), PointD(110.0, 20.0), PointD(110.0, 120.0), PointD(10.0, 120.0))
        val variants = manualQuadNeighborhood(original, version = 1)

        assertEquals(original, variants.first())
        assertEquals(19, variants.size)
        assertTrue(variants.any { it.topLeft.x < original.topLeft.x && it.topRight.x < original.topRight.x })
        assertTrue(variants.any { it.topLeft != original.topLeft && it.topRight == original.topRight })
    }

    @Test fun translationsScalesAndCornerMovesUseComparableDisplacementsAcrossWidths() {
        for (version in listOf(1, 3, 7)) for (tenths in 3..8) {
            val width = tenths / 10.0
            val expected = width * 200.0 / (17 + 4 * version - 1)
            val variants = manualQuadNeighborhood(quad, version, width)
            val context = "V$version/width=$width"
            assertEquals(context, 19, variants.size)
            assertEquals(context, quad, variants.first())
            variants.subList(1, 11).forEach { transformed ->
                val distances = quad.asList().zip(transformed.asList()).map { (before, after) -> before.distanceTo(after) }
                assertEquals(context, expected, distances.average(), 1e-6)
            }
            variants.drop(11).forEach { transformed ->
                val distances = quad.asList().zip(transformed.asList()).map { (before, after) -> before.distanceTo(after) }
                assertEquals(context, 1, distances.count { it > 1e-6 })
                assertEquals(context, expected, distances.max(), 1e-6)
            }
        }
    }

    @Test fun oneFinderRotationKeepsItsCornerFixedAndUsesTheRequestedOuterDisplacement() {
        for (version in listOf(1, 3, 7)) for (tenths in 3..8) for (role in FinderRole.entries) {
            val width = tenths / 10.0
            val expected = width * 200.0 / (17 + 4 * version - 1)
            val variants = manualQuadNeighborhood(quad, version, width, role)
            val context = "V$version/width=$width/pivot=$role"
            val pivotIndex = when (role) {
                FinderRole.TOP_LEFT -> 0
                FinderRole.TOP_RIGHT -> 1
                FinderRole.BOTTOM_LEFT -> 3
            }
            val pivot = quad.asList()[pivotIndex]
            val farthestIndex = (pivotIndex + 2) % 4
            assertEquals(context, 21, variants.size)
            assertEquals(context, quad, variants.first())
            variants.takeLast(2).forEachIndexed { index, rotated ->
                assertEquals(context, pivot, rotated.asList()[pivotIndex])
                val distances = quad.asList().zip(rotated.asList()).map { (before, after) -> before.distanceTo(after) }
                assertEquals(context, expected, distances.max(), 1e-6)
                quad.asList().indices.forEach { corner ->
                    assertEquals(context, quad.asList()[corner].distanceTo(pivot),
                        rotated.asList()[corner].distanceTo(pivot), 1e-6)
                }
                val before = quad.asList()[farthestIndex] - pivot
                val after = rotated.asList()[farthestIndex] - pivot
                val cross = before.x * after.y - before.y * after.x
                assertTrue(context, if (index == 0) cross < 0.0 else cross > 0.0)
            }
        }
    }
}
