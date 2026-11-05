package io.github.earthdiver.qrrepair.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThinPlateWarpInverseTest {
    private fun geometry(version: Int, landmarks: List<WarpLandmark>): GridGeometry {
        val far = 50.0 + (16 + 4 * version) * 8.0
        return GridGeometry(
            version, FinderRole.TOP_LEFT, 74.0, 74.0, 8.0, 0.0,
            ImageQuad(PointD(50.0, 50.0), PointD(far, 50.0), PointD(far, far), PointD(50.0, far)),
            GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp(landmarks),
        ).reanchored()
    }

    private fun compressedGrid(): GridGeometry = geometry(1, (0..4).flatMap { y ->
        (0..4).map { x ->
            WarpLandmark(
                PointD(x / 4.0, y / 4.0),
                PointD(8 * (2.4 + 1.6 * sin(5 * x * 2 * PI / 10 + 4.2)), 0.0),
                1e-5,
            )
        }
    })

    private fun patternLandmarkGrid(): GridGeometry {
        val last = 56.0
        val positions = buildList {
            for ((ox, oy) in listOf(0 to 0, 50 to 0, 0 to 50)) {
                for ((dx, dy) in listOf(0 to 0, 6 to 0, 6 to 6, 0 to 6)) {
                    add(PointD((ox + dx).toDouble(), (oy + dy).toDouble()))
                }
            }
            for (x in listOf(6, 28, 50)) for (y in listOf(6, 28, 50)) {
                if ((x == 6 && y == 6) || (x == 50 && y == 6) || (x == 6 && y == 50)) continue
                add(PointD(x.toDouble(), y.toDouble()))
            }
        }.toMutableList()
        val patternCount = positions.size
        // Independently generated observations at the supported Finder, alignment and timing positions.
        for (module in listOf(10, 12, 14, 16, 18, 22, 24, 26, 28, 30, 34, 36, 38, 40, 42, 46)) {
            for (point in listOf(PointD(module.toDouble(), 6.0), PointD(6.0, module.toDouble()))) {
                if (point !in positions) positions += point
            }
        }
        return geometry(10, positions.mapIndexed { index, point ->
            WarpLandmark(
                point * (1.0 / last),
                PointD(8 * (2.0 + 1.6 * sin(point.x * 2 * PI / 8)), 0.0),
                if (index < patternCount) 1e-5 else 1e-3,
            )
        })
    }

    private fun assertRoundTrip(grid: GridGeometry, x: Double, y: Double) {
        val image = grid.moduleToImage(x, y)
        val module = grid.imageToModule(image.x, image.y)
        assertTrue(module.x.isFinite() && module.y.isFinite(), "No inverse at module ($x, $y): $image")
        assertEquals(x, module.x, 1e-4)
        assertEquals(y, module.y, 1e-4)
        assertTrue(grid.moduleToImage(module.x, module.y).distanceTo(image) <= 1e-5)
    }

    @Test fun compressedCurvedCellsCanBeSelectedAtTheirSampledCenters() {
        val grid = compressedGrid()
        assertTrue(grid.hasUsableWarp())
        for ((x, y) in listOf(14 to 2, 14 to 18)) {
            assertRoundTrip(grid, x.toDouble(), y.toDouble())
            val image = grid.moduleToImage(x.toDouble(), y.toDouble())
            assertEquals(x to y, grid.nearestModule(image.x, image.y))
        }
    }

    @Test fun curvedPatternLandmarksPreserveSamplingAndSelectionAgreement() {
        val grid = patternLandmarkGrid()
        assertTrue(grid.hasUsableWarp())
        for ((x, y) in listOf(9 to 6, 28 to 28, 50 to 50)) {
            assertRoundTrip(grid, x.toDouble(), y.toDouble())
            val image = grid.moduleToImage(x.toDouble(), y.toDouble())
            assertEquals(x to y, grid.nearestModule(image.x, image.y))
        }
    }

    @Test fun roundedTouchCoordinatesAndNearbyBrushSamplesSelectTheCurvedCell() {
        val grid = compressedGrid()
        val image = grid.moduleToImage(14.0, 2.0)
        val x = image.x.toFloat().toDouble()
        val y = image.y.toFloat().toDouble()
        for (offset in listOf(.1, .5)) for (dx in -1..1) for (dy in -1..1) {
            val px = x + dx * offset
            val py = y + dy * offset
            assertEquals(14 to 2, grid.nearestModule(px, py), "Touch offset (${dx * offset}, ${dy * offset})")
            val module = grid.imageToModule(px, py)
            assertTrue(grid.moduleToImage(module.x, module.y).distanceTo(PointD(px, py)) <= 1e-5)
        }
    }

    @Test fun curvedSelectionFollowsMirroringRotationAndScale() {
        val original = compressedGrid()
        val corners = original.imageQuad!!.asList().map { PointD(420.0 - it.x, it.y) }
        val mirrored = original.copy(
            imageQuad = ImageQuad(corners[0], corners[1], corners[2], corners[3]),
            warp = ThinPlateWarp(original.warp!!.landmarks.map {
                it.copy(displacement = PointD(-it.displacement.x, it.displacement.y))
            }),
        ).reanchored()
        for (grid in listOf(original, mirrored)) {
            for ((scale, angle) in listOf(.35 to -.6, 1.0 to 0.0, 2.5 to 1.1)) {
                val transformed = grid.adjusted(dxPixels = 23.0, dyPixels = -17.0,
                    pitchScale = scale, angleDeltaRad = angle)
                assertTrue(transformed.hasUsableWarp())
                for ((x, y) in listOf(14.0 to 2.0, 14.2 to 2.15, 3.0 to 3.0, 19.0 to 19.0)) {
                    assertRoundTrip(transformed, x, y)
                }
                val image = transformed.moduleToImage(14.0, 2.0)
                assertEquals(14 to 2, transformed.nearestModule(image.x.toFloat().toDouble(), image.y.toFloat().toDouble()))
            }
        }
    }

    @Test fun perspectiveCurvatureKeepsSubcellAccuracyWithUnequalImageScales() {
        val projection = Homography(8.0, .4, 50.0, .1, .8, 50.0, .004, .007)
        val corners = listOf(PointD(0.0, 0.0), PointD(20.0, 0.0), PointD(20.0, 20.0), PointD(0.0, 20.0))
            .map(projection::map)
        val landmarks = listOf(PointD(0.0, 0.0), PointD(1.0, 0.0), PointD(1.0, 1.0), PointD(0.0, 1.0))
            .map { WarpLandmark(it, PointD(0.0, 0.0)) } +
            WarpLandmark(PointD(.5, .5), PointD(1.5, .3))
        val grid = geometry(1, landmarks).copy(
            imageQuad = ImageQuad(corners[0], corners[1], corners[2], corners[3]),
        ).reanchored()
        assertTrue(grid.hasUsableWarp())
        for (y in listOf(0.0, 2.25, 10.0, 19.5)) for (x in listOf(0.0, 6.75, 14.0, 19.5)) {
            assertRoundTrip(grid, x, y)
        }
        val image = grid.moduleToImage(14.0, 2.0)
        assertEquals(14 to 2, grid.nearestModule(image.x.toFloat().toDouble(), image.y.toFloat().toDouble()))
    }

    @Test fun anImpossibleInverseDoesNotReturnAnApproximateCell() {
        val grid = geometry(1, listOf(
            WarpLandmark(PointD(0.0, 0.0), PointD(0.0, 0.0)),
            WarpLandmark(PointD(1.0, 0.0), PointD(-160.0, 0.0)),
            WarpLandmark(PointD(0.0, 1.0), PointD(0.0, 0.0)),
        ))
        assertTrue(!grid.hasUsableWarp())
        // The entire mapping has image x = 50, so this finite target has no preimage.
        val module = grid.imageToModule(51.0, 74.0)
        assertTrue(!module.x.isFinite() && !module.y.isFinite())
        assertNull(grid.nearestModule(51.0, 74.0))
    }

    @Test fun aCurvedCellRemainsSelectableAtTheUnderlyingProjectiveHorizon() {
        val projection = Homography(13.0, 0.0, 50.0, 5.0, 800.0, 50.0, .1, 0.0)
        val corners = listOf(PointD(0.0, 0.0), PointD(20.0, 0.0), PointD(20.0, 20.0), PointD(0.0, 20.0))
            .map(projection::map)
        val displacement = PointD(80.0 - 160.0 / 3.0, 0.0)
        val grid = geometry(1, listOf(PointD(0.0, 0.0), PointD(1.0, 0.0), PointD(0.0, 1.0))
            .map { WarpLandmark(it, displacement) }).copy(
            imageQuad = ImageQuad(corners[0], corners[1], corners[2], corners[3]),
        ).reanchored()
        assertTrue(grid.hasUsableWarp())
        val image = grid.moduleToImage(20.0, 10.0)
        // The base map approaches image x = 130 only at infinity; the curved map reaches it inside the symbol.
        assertEquals(130.0, image.x, 1e-10)
        assertRoundTrip(grid, 20.0, 10.0)
        assertEquals(20 to 10, grid.nearestModule(image.x, image.y))
    }

    @Test fun exteriorAndInvalidTouchesDoNotBecomeEdgeCellSelections() {
        val grid = compressedGrid()
        for ((x, y) in listOf(-.6 to 3.0, 20.6 to 3.0, 3.0 to -.6, 3.0 to 20.6)) {
            val image = grid.moduleToImage(x, y)
            assertNull(grid.nearestModule(image.x.toFloat().toDouble(), image.y.toFloat().toDouble()),
                "Exterior module coordinate ($x, $y) was clamped into the grid")
        }
        for (point in listOf(PointD(-10_000.0, -10_000.0), PointD(10_000.0, 10_000.0),
            PointD(Double.NaN, 66.0), PointD(188.0, Double.POSITIVE_INFINITY))) {
            assertNull(grid.nearestModule(point.x, point.y))
        }
    }
}
