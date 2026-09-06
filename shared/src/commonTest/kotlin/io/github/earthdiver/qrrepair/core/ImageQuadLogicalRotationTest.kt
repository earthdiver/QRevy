package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageQuadLogicalAssignmentTest {
    private val quad = ImageQuad(PointD(1.0, 2.0), PointD(9.0, 3.0), PointD(8.0, 11.0), PointD(0.0, 10.0))

    @Test fun finderRoleAssignsLogicalOriginWithoutMovingPhysicalCorners() {
        val assigned = quad.assignedFromFinder(quad.bottomLeft, FinderRole.TOP_LEFT)
        assertEquals(quad.asList().toSet(), assigned.asList().toSet())
        assertEquals(quad.bottomLeft, assigned.topLeft)
        assertEquals(quad.topLeft, assigned.topRight)
    }

    @Test fun topRightRoleMapsObservedCornerToLogicalTopRight() {
        val assigned = quad.assignedFromFinder(quad.bottomLeft, FinderRole.TOP_RIGHT)
        assertEquals(quad.bottomLeft, assigned.topRight)
    }

}
