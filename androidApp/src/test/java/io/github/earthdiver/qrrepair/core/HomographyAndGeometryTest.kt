package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomographyAndGeometryTest {
    @Test fun homographyRoundTripsFourCorners() {
        val src = listOf(PointD(0.0,0.0), PointD(10.0,0.0), PointD(10.0,10.0), PointD(0.0,10.0))
        val dst = listOf(PointD(2.0,3.0), PointD(14.0,2.0), PointD(13.0,15.0), PointD(1.0,12.0))
        val h = Homography.fromCorrespondences(src, dst)
        val inv = h.inverse()
        for (i in src.indices) {
            assertTrue(h.map(src[i]).distanceTo(dst[i]) < 1e-7)
            assertTrue(inv.map(dst[i]).distanceTo(src[i]) < 1e-7)
        }
    }

    @Test fun twoFindersDetermineSimilarityForKnownRoles() {
        val original = GridGeometry(5, FinderRole.TOP_LEFT, 120.0, 90.0, 7.5, 0.37)
        fun f(role: FinderRole): FinderCandidate {
            val m = original.finderModulePoint(role)
            val p = original.moduleToImage(m.x, m.y)
            return FinderCandidate(p.x, p.y, 7.5, 0.37, 1.0, 5)
        }
        val fitted = GridGeometry.fromTwoFinders(5, FinderRole.TOP_LEFT, f(FinderRole.TOP_LEFT), FinderRole.TOP_RIGHT, f(FinderRole.TOP_RIGHT))
        val probe = PointD(17.0, 23.0)
        assertTrue(fitted.moduleToImage(probe.x, probe.y).distanceTo(original.moduleToImage(probe.x, probe.y)) < 1e-6)
        assertEquals(GeometryModel.TWO_FINDER, fitted.model)
    }

    @Test fun threeFindersFitAffineGrid() {
        val affine = Affine2D(7.0, 0.8, 30.0, -0.4, 6.5, 55.0)
        val version = 7
        val d = 17 + 4 * version
        fun mp(role: FinderRole) = when(role) {
            FinderRole.TOP_LEFT -> PointD(3.0,3.0)
            FinderRole.TOP_RIGHT -> PointD((d-4).toDouble(),3.0)
            FinderRole.BOTTOM_LEFT -> PointD(3.0,(d-4).toDouble())
        }
        val map = FinderRole.entries.associateWith { role ->
            val p = affine.map(mp(role))
            FinderCandidate(p.x,p.y,6.8,0.0,1.0,4)
        }
        val g = GridGeometry.fromAffine(version, FinderRole.TOP_LEFT, map)
        val probe = PointD(25.0, 31.0)
        assertTrue(g.moduleToImage(probe.x, probe.y).distanceTo(affine.map(probe)) < 1e-6)
        assertEquals(GeometryModel.AFFINE, g.model)
    }
}
