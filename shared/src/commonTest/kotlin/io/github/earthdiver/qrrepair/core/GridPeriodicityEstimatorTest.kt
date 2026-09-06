package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GridPeriodicityEstimatorTest {
    @Test fun estimatesVersionAndCornersWithoutFinderPatterns() {
        val version = 4
        val dimension = 17 + 4 * version
        val pitch = 7
        val quiet = 4
        val size = (dimension + quiet * 2) * pitch
        val pixels = IntArray(size * size) { 245 }
        for (my in 0 until dimension) for (mx in 0 until dimension) {
            // An ordinary-looking irregular payload grid, with all finder areas deliberately gone.
            val inFinder = (mx < 9 && my < 9) || (mx >= dimension - 9 && my < 9) || (mx < 9 && my >= dimension - 9)
            val black = !inFinder && ((mx * 17 + my * 11 + mx * my) % 7 < 3)
            if (black) for (dy in 0 until pitch) for (dx in 0 until pitch) {
                val x = (quiet + mx) * pitch + dx
                val y = (quiet + my) * pitch + dy
                pixels[y * size + x] = 12
            }
        }
        // Keep evidence at every outer edge even though no finder survives.
        for (m in 9 until dimension - 1 step 2) for (i in 0 until pitch) {
            pixels[((quiet + 0) * pitch + i) * size + (quiet + m) * pitch + i % pitch] = 12
            pixels[((quiet + dimension - 1) * pitch + i) * size + (quiet + m) * pitch + i % pitch] = 12
            pixels[((quiet + m) * pitch + i) * size + (quiet + 0) * pitch + i % pitch] = 12
            pixels[((quiet + m) * pitch + i) * size + (quiet + dimension - 1) * pitch + i % pitch] = 12
        }

        val estimate = assertNotNull(GridPeriodicityEstimator.estimate(LumaImage(size, size, pixels)))
        assertEquals(version, estimate.version)
        assertTrue(kotlin.math.abs(estimate.modulePitch - pitch) < .8)
        assertTrue(estimate.moduleCenterQuad.topLeft.x in 29.0..33.0)
        assertTrue(estimate.moduleCenterQuad.topLeft.y in 29.0..33.0)
    }

    @Test fun rejectsAFlatImage() {
        assertEquals(null, GridPeriodicityEstimator.estimate(LumaImage(200, 200, IntArray(40_000) { 220 })))
    }

    @Test fun choosesFundamentalPitchWithSpreadsheetMarginsAndLargeDamage() {
        val version = 4
        val dimension = 33
        val pitch = 8
        val size = (dimension + 1) * pitch
        val pixels = IntArray(size * size) { 238 }
        fun fillModule(mx: Int, my: Int, value: Int = 18) {
            for (dy in 0 until pitch) for (dx in 0 until pitch) {
                val x = (mx + 1) * pitch + dx
                val y = (my + 1) * pitch + dy
                pixels[y * size + x] = value
            }
        }
        for (my in 0 until dimension) for (mx in 0 until dimension) {
            if ((mx * 13 + my * 7 + mx * my) % 9 < 4) fillModule(mx, my)
        }
        fun finder(originX: Int, originY: Int) {
            for (y in 0..6) for (x in 0..6) {
                val black = x == 0 || x == 6 || y == 0 || y == 6 || (x in 2..4 && y in 2..4)
                fillModule(originX + x, originY + y, if (black) 18 else 238)
            }
        }
        finder(0, 0); finder(dimension - 7, 0); finder(0, dimension - 7)
        // Large colored/unknown-looking damage must not turn the half-pitch harmonic into V13.
        for (my in 16..28) for (mx in 13..27) fillModule(mx, my, 105)
        // Sparse row/column labels outside the QR body reproduce a spreadsheet screenshot.
        for (cell in 0 until dimension step 3) for (i in 2 until pitch - 2) {
            pixels[(pitch / 2) * size + cell * pitch + i] = 40
            pixels[(cell * pitch + i) * size + pitch / 2] = 40
        }

        val estimate = assertNotNull(GridPeriodicityEstimator.estimate(LumaImage(size, size, pixels)))
        assertEquals(version, estimate.version)
        assertTrue(kotlin.math.abs(estimate.modulePitch - pitch) < .8)
        assertTrue(estimate.moduleCenterQuad.topLeft.x in 2.0..14.0, estimate.toString())
        assertTrue(estimate.moduleCenterQuad.topLeft.y in 2.0..14.0, estimate.toString())
    }
}
