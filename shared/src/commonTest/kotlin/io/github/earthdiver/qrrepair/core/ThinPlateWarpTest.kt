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

    @Test fun localFoldsWithinLargeSymbolsAreRejected() {
        val last = 176.0
        val positions = listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last),
            PointD(90.0, 90.0), PointD(92.0, 90.0), PointD(91.0, 89.0), PointD(91.0, 91.0), PointD(91.0, 90.0))
        val quad = ImageQuad(positions[0], positions[1], positions[2], positions[3])
        for (mirrored in listOf(false, true)) for (shift in listOf(0.0, .25)) {
            val marks = positions.mapIndexed { index, point ->
                val local = if (index < 4) point else point + PointD(shift, shift)
                val displacement = if (index == positions.lastIndex) 1.25 else 0.0
                WarpLandmark(local * (1.0 / last),
                    if (mirrored) PointD(0.0, displacement) else PointD(displacement, 0.0), 1e-6)
            }
            val geometry = GridGeometry(40, FinderRole.TOP_LEFT, 3.0, 3.0, 1.0, 0.0,
                if (mirrored) quad.transposed() else quad, GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp(marks))
            assertFalse(geometry.hasUsableWarp(), "Local fold accepted: mirrored=$mirrored shift=$shift")
        }
    }

    @Test fun validationFollowsTheMappingWhenAnImmutableGeometryIsCopied() {
        val original = curved()
        assertTrue(original.hasUsableWarp())
        assertTrue(original.copy().hasUsableWarp())
        val compressed = original.copy(imageQuad = original.imageQuad!!.asList().map { it * .01 }.let {
            ImageQuad(it[0], it[1], it[2], it[3])
        })
        assertFalse(compressed.hasUsableWarp())
        assertTrue(original.hasUsableWarp())
    }

    @Test fun analyticWarpDerivativesMatchTheSamplingMap() {
        val warp = curved().warp!!
        val values = DoubleArray(6)
        val epsilon = 1e-6
        for (p in listOf(PointD(0.0, 0.0), PointD(.5, .5), PointD(.3, .7), PointD(1.0, 1.0))) {
            warp.offsetAndJacobian(p.x, p.y, values)
            val offset = warp.offset(p.x, p.y)
            assertEquals(offset.x, values[0], 1e-9)
            assertEquals(offset.y, values[1], 1e-9)
            val dx = (warp.offset(p.x + epsilon, p.y) - warp.offset(p.x - epsilon, p.y)) * (.5 / epsilon)
            val dy = (warp.offset(p.x, p.y + epsilon) - warp.offset(p.x, p.y - epsilon)) * (.5 / epsilon)
            assertEquals(dx.x, values[2], 1e-4)
            assertEquals(dy.x, values[3], 1e-4)
            assertEquals(dx.y, values[4], 1e-4)
            assertEquals(dy.y, values[5], 1e-4)
        }
    }

    private fun clusteredGeometry(span: Double, displacement: Double, mirrored: Boolean): GridGeometry {
        val last = 176.0
        val points = listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last),
            PointD(91.0 - span, 90.0), PointD(91.0 + span, 90.0),
            PointD(91.0, 90.0 - span), PointD(91.0, 90.0 + span), PointD(91.0, 90.0))
        val quad = ImageQuad(points[0], points[1], points[2], points[3])
        return GridGeometry(40, FinderRole.TOP_LEFT, 3.0, 3.0, 1.0, 0.0,
            if (mirrored) quad.transposed() else quad, GeometryModel.THIN_PLATE_SPLINE,
            ThinPlateWarp(points.mapIndexed { index, p ->
                val amount = if (index == points.lastIndex) displacement else 0.0
                WarpLandmark(p * (1.0 / last), if (mirrored) PointD(0.0, amount) else PointD(amount, 0.0), 1e-6)
            }))
    }

    @Test fun closelySpacedLandmarksDoNotHideFoldsInsideASamplingCell() {
        for (mirrored in listOf(false, true)) {
            val geometry = clusteredGeometry(.1, .40110301379474095, mirrored)
            assertFalse(geometry.hasUsableWarp(), "Fold between clustered landmarks: mirrored=$mirrored")
        }
    }

    @Test fun validCurvatureWithCloselySpacedLandmarksRemainsUsable() {
        for (span in listOf(.001, .01, .1)) for (mirrored in listOf(false, true)) {
            val geometry = clusteredGeometry(span, span * .2, mirrored)
            assertTrue(geometry.hasUsableWarp(), "Valid clustered landmarks: span=$span mirrored=$mirrored")
            for (y in 89..91) for (x in 90..92) {
                val image = geometry.moduleToImage(x.toDouble(), y.toDouble())
                assertEquals(x to y, geometry.nearestModule(image.x, image.y))
            }
        }
    }
}
