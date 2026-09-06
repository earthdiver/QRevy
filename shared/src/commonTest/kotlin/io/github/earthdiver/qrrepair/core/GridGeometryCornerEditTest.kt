package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertTrue

class GridGeometryCornerEditTest {
    @Test
    fun movingOneCornerKeepsTheRequestedPointAndOtherCornersStable() {
        val original = GridGeometry(
            version = 2,
            role = FinderRole.TOP_LEFT,
            finderCenterX = 28.0,
            finderCenterY = 28.0,
            modulePitch = 8.0,
            angleRad = 0.0,
        ).asProjective(manual = true)
        val before = original.moduleCenterQuad().asList()
        val requested = PointD(203.25, 7.75)

        val edited = original.withCorner(1, requested)
        val after = edited.moduleCenterQuad().asList()

        assertTrue(after[1].distanceTo(requested) < 1e-9)
        for (index in listOf(0, 2, 3)) assertTrue(after[index].distanceTo(before[index]) < 1e-9)
    }
}
