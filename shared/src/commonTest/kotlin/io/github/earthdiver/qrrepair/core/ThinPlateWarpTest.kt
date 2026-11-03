package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class ThinPlateWarpTest {
    private fun base() = GridGeometry(4, FinderRole.TOP_LEFT, 50.0, 50.0, 8.0, 0.0,
        ImageQuad(PointD(20.0, 20.0), PointD(276.0, 20.0), PointD(276.0, 276.0), PointD(20.0, 276.0)),
        GeometryModel.PROJECTIVE).reanchored()
    private fun curved() = base().copy(model = GeometryModel.THIN_PLATE_SPLINE, warp = ThinPlateWarp(listOf(
        WarpLandmark(PointD(0.0, 0.0), PointD(0.0, 0.0)),
        WarpLandmark(PointD(1.0, 0.0), PointD(0.0, 0.0)),
        WarpLandmark(PointD(0.0, 1.0), PointD(0.0, 0.0)),
        WarpLandmark(PointD(1.0, 1.0), PointD(0.0, 0.0)),
        WarpLandmark(PointD(.5, .5), PointD(7.0, -5.0)),
    )))

    @Test fun curvedSamplingAndHitTestingReferToTheSameSourceLocations() {
        val g = curved()
        assertTrue(g.hasUsableWarp())
        assertTrue(g.moduleToImage(16.0, 16.0).distanceTo(PointD(155.0, 143.0)) < .01)
        for (y in 0..32 step 2) for (x in 0..32 step 2) {
            val p = g.moduleToImage(x.toDouble(), y.toDouble())
            val q = g.imageToModule(p.x, p.y)
            assertTrue(q.distanceTo(PointD(x.toDouble(), y.toDouble())) < 1e-4)
            assertEquals(x to y, g.nearestModule(p.x, p.y))
        }
        val mirror = g.copy(imageQuad = base().imageQuad!!.transposed(), warp = ThinPlateWarp(g.warp!!.landmarks.map {
            it.copy(displacement = PointD(it.displacement.y, it.displacement.x))
        }))
        assertTrue(mirror.hasUsableWarp())
    }

    @Test fun movingTheGeometryMovesItsResidualWithoutChangingSourceEvidence() {
        val original = curved()
        val moved = original.adjusted(dxPixels = 12.0, dyPixels = -7.0)
        val p = original.moduleToImage(13.0, 19.0)
        assertTrue(moved.moduleToImage(13.0, 19.0).distanceTo(p + PointD(12.0, -7.0)) < 1e-6)
        val resized = original.adjusted(pitchScale = 1.3, angleDeltaRad = .2)
        assertTrue(resized.hasUsableWarp())
        assertNotNull(resized.warp)
        val corner = original.moduleCenterQuad().topLeft + PointD(1.0, 2.0)
        val edited = original.withCorner(0, corner)
        assertTrue(edited.moduleCenterQuad().topLeft.distanceTo(corner) < 1e-6)
        assertEquals(original.warp, edited.warp)
    }

    @Test fun collinearLandmarksAndFoldedOrExcessiveMapsAreRejected() {
        val marks = (0..2).map { WarpLandmark(PointD(it / 2.0, 0.0), PointD(0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { ThinPlateWarp(marks).offset(.5, .5) }
        val folded = curved().let { g -> g.copy(warp = ThinPlateWarp(g.warp!!.landmarks.map {
            if (it.position == PointD(.5, .5)) it.copy(displacement = PointD(400.0, 0.0)) else it
        })) }
        assertFalse(folded.hasUsableWarp())
        assertFailsWith<IllegalArgumentException> { ThinPlateWarp(listOf(marks[0], marks[0], marks[2])) }
    }
}
