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

    @Test fun oneFinderRotationKeepsItsCornerFixedAndUsesTheRequestedOuterDisplacement() {
        val variants = manualQuadNeighborhood(quad, version = 7, rotationPivotRole = FinderRole.TOP_LEFT)
        val pitch = (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (17 + 4 * 7 - 1))

        assertEquals(21, variants.size)
        variants.takeLast(2).forEach { rotated ->
            assertEquals(quad.topLeft, rotated.topLeft)
            val maximumDisplacement = quad.asList().zip(rotated.asList()).maxOf { (before, after) ->
                before.distanceTo(after)
            }
            assertEquals(.60 * pitch, maximumDisplacement, 1e-6)
        }
    }
}
