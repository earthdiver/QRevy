package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageQuadLogicalAssignmentTest {
    private val quad = ImageQuad(PointD(1.0, 2.0), PointD(9.0, 3.0), PointD(8.0, 11.0), PointD(0.0, 10.0))

    @Test fun finderRoleAssignsLogicalOriginWithoutMovingPhysicalCorners() {
        val assigned = quad.assignedFromFinder(quad.bottomLeft, FinderRole.TOP_LEFT, 1)
        assertEquals(quad.asList().toSet(), assigned.asList().toSet())
        assertEquals(quad.bottomLeft, assigned.topLeft)
        assertEquals(quad.topLeft, assigned.topRight)
    }

    @Test fun topRightRoleMapsObservedCornerToLogicalTopRight() {
        val assigned = quad.assignedFromFinder(quad.bottomLeft, FinderRole.TOP_RIGHT, 1)
        assertEquals(quad.bottomLeft, assigned.topRight)
    }

    @Test fun perspectiveFinderCentersAssignTheSameLogicalCornersAcrossOrientations() {
        val physical = ImageQuad(PointD(20.0, 20.0), PointD(620.0, 20.0), PointD(120.0, 620.0), PointD(20.0, 620.0))
        for (version in listOf(1, 7, 40)) for (mirrored in listOf(false, true)) for (turns in 0..3) {
            val points = physical.asList().let { if (mirrored) listOf(it[0], it[3], it[2], it[1]) else it }
            val ordered = List(4) { points[(it + turns) % 4] }
            val oriented = ImageQuad(ordered[0], ordered[1], ordered[2], ordered[3])
            val last = (16 + 4 * version).toDouble()
            val mapping = Homography.fromCorrespondences(
                listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last)), ordered)
            for ((role, module) in listOf(FinderRole.TOP_LEFT to PointD(3.0, 3.0),
                FinderRole.TOP_RIGHT to PointD(last - 3, 3.0), FinderRole.BOTTOM_LEFT to PointD(3.0, last - 3))) {
                assertEquals(oriented, oriented.assignedFromFinder(mapping.map(module), role, version))
            }
        }
    }
}
