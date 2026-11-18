package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class FinderCornerAlignmentTest {
    @Test fun reassignmentKeepsEveryOtherFinderAndUnassignedCornerAnchored() {
        for (version in listOf(1, 7, 40)) for (mirrored in listOf(false, true)) {
            val physical = ImageQuad(PointD(20.0, 25.0), PointD(220.0, 15.0),
                PointD(215.0, 225.0), PointD(25.0, 210.0))
            val quad = if (mirrored) physical.transposed() else physical
            val last = (16 + 4 * version).toDouble()
            val modules = listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last))
            val finderModules = mapOf(FinderRole.TOP_LEFT to PointD(3.0, 3.0),
                FinderRole.TOP_RIGHT to PointD(last - 3.0, 3.0), FinderRole.BOTTOM_LEFT to PointD(3.0, last - 3.0))
            val original = Homography.fromCorrespondences(modules, quad.asList())
            for (mask in 1..7) {
                val selected = FinderRole.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                val finders = selected.associateWith { original.map(finderModules.getValue(it)) }
                for (role in selected) {
                    val targets = finders + (role to (finders.getValue(role) + PointD(20.0, -15.0)))
                    val aligned = alignQuadToFinders(quad, version, targets)
                    val actual = Homography.fromCorrespondences(modules, aligned.asList())
                    targets.forEach { (r, point) -> assertTrue(actual.map(finderModules.getValue(r)).distanceTo(point) < 1e-7) }
                    listOf(FinderRole.TOP_LEFT, FinderRole.TOP_RIGHT, null, FinderRole.BOTTOM_LEFT)
                        .forEachIndexed { index, r -> if (r !in targets) assertEquals(quad.asList()[index], aligned.asList()[index]) }
                }
            }
        }
    }

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
