package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertTrue
import org.junit.Test

class FinderCornerAlignmentTest {
    @Test fun confirmedFinderMovesItsNearbyCornerToTheRequestedCenter() {
        val original = ImageQuad(
            PointD(4.0, 4.0), PointD(196.0, 4.0),
            PointD(196.0, 196.0), PointD(4.0, 196.0),
        )
        val target = PointD(170.0, 30.0)
        val aligned = alignQuadCornerToFinder(original, 2, FinderRole.TOP_RIGHT, target)
        val geometry = GridGeometry(
            2, FinderRole.TOP_LEFT, aligned.topLeft.x, aligned.topLeft.y, 8.0,
            0.0, aligned, GeometryModel.MANUAL_PROJECTIVE,
        ).reanchored()
        val module = geometry.finderModulePoint(FinderRole.TOP_RIGHT)

        assertTrue(geometry.moduleToImage(module.x, module.y).distanceTo(target) < .01)
        assertTrue(aligned.topLeft.distanceTo(original.topLeft) < 1e-9)
        assertTrue(aligned.bottomLeft.distanceTo(original.bottomLeft) < 1e-9)
        assertTrue(aligned.bottomRight.distanceTo(original.bottomRight) < 1e-9)
    }
}
