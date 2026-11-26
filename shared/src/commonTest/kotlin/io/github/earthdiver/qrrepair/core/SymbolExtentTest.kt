package io.github.earthdiver.qrrepair.core

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Symbol content, not the crop margin around it, decides the estimation window and the initial crop. */
class SymbolExtentTest {
    private val gray = 189

    /**
     * A version 2 symbol at [unit] pixels per module whose modules in columns >= [unknownFrom] are a
     * uniform unknown gray, placed with [marginModules] of white around it (no quiet zone of its own).
     */
    private fun partlyUnknown(unit: Int, unknownFrom: Int, marginModules: Int, unknownRows: IntRange = 0..24): LumaImage {
        val n = 25
        val margin = marginModules * unit
        val size = n * unit + 2 * margin
        fun black(x: Int, y: Int): Boolean {
            for ((sx, sy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val dx = x - sx; val dy = y - sy
                if (dx in -1..7 && dy in -1..7) {
                    if (dx !in 0..6 || dy !in 0..6) return false
                    return dx == 0 || dy == 0 || dx == 6 || dy == 6 || (dx in 2..4 && dy in 2..4)
                }
            }
            if (x == 6 && y in 8..n - 9) return y % 2 == 0
            if (y == 6 && x in 8..n - 9) return x % 2 == 0
            return (x * 17 + y * 11 + x * y) % 7 < 3
        }
        return LumaImage(size, size, IntArray(size * size) { i ->
            val x = floor(((i % size) - margin).toDouble() / unit).toInt()
            val y = floor(((i / size) - margin).toDouble() / unit).toInt()
            when {
                x !in 0 until n || y !in 0 until n -> 255
                x >= unknownFrom && y in unknownRows -> gray
                black(x, y) -> 0
                else -> 255
            }
        })
    }

    @Test fun partlyUnknownSymbolWithoutQuietZoneIsFoundWithAnyCropMargin() {
        val unit = 15
        // Unknown right half (one Finder visible) and unknown bottom-right (two Finders visible).
        for ((unknownFrom, rows) in listOf(13 to 0..24, 14 to 8..24)) for (modules in listOf(0, 4, 12, 25, 50)) {
            val image = partlyUnknown(unit, unknownFrom, modules, rows)
            val label = "unknown from column $unknownFrom rows $rows, margin $modules"
            val estimate = assertNotNull(InitialGridEstimator.estimate(image, emptyList()), label)
            assertEquals(2, estimate.version, label)
            val a = modules * unit + unit / 2.0; val b = a + 24 * unit
            for ((actual, expected) in estimate.moduleCenterQuad.asList().zip(listOf(PointD(a, a), PointD(b, a), PointD(b, b), PointD(a, b))))
                assertTrue(actual.distanceTo(expected) < unit * .5, "$label: $actual vs $expected")
        }
    }

    @Test fun contentBoundsIncludeUnknownGrayModules() {
        val unit = 10
        val image = partlyUnknown(unit, unknownFrom = 13, marginModules = 6)
        val bounds = assertNotNull(SymbolExtent.bounds(image))
        val first = 6 * unit; val last = first + 25 * unit - 1
        assertTrue(bounds.zip(listOf(first, first, last, last)).all { (actual, expected) -> kotlin.math.abs(actual - expected) <= 2 },
            bounds.toList().toString())
    }

    @Test fun initialCropKeepsAQuietZoneAroundTheContent() {
        val unit = 10
        val image = partlyUnknown(unit, unknownFrom = 13, marginModules = 20)
        val crop = assertNotNull(SymbolExtent.initialCrop(image))
        val size = image.width.toDouble()
        val first = 20 * unit; val last = first + 25 * unit
        // At least four modules of a version 1 symbol of the same size (4 / 21 of its side) remain.
        val quiet = 25 * unit * 4.0 / 21
        assertTrue(crop[0] * size <= first - quiet + 1 && crop[1] * size <= first - quiet + 1, crop.toList().toString())
        assertTrue(crop[2] * size >= last + quiet - 1 && crop[3] * size >= last + quiet - 1, crop.toList().toString())
        assertTrue(crop[0] * size >= first - 25 * unit * .25 && crop[2] * size <= last + 25 * unit * .25, crop.toList().toString())
    }

    @Test fun invertedUnknownModulesKeepTheirExtentAndGridAcrossCropMargins() {
        val unit = 8
        for (modules in listOf(0, 4, 12, 25, 50)) {
            val light = partlyUnknown(unit, unknownFrom = 13, marginModules = modules)
            val dark = LumaImage(light.width, light.height, IntArray(light.pixels.size) { 255 - light.pixels[it] })
            assertEquals(SymbolExtent.bounds(light)?.toList(), SymbolExtent.bounds(dark)?.toList(), "margin $modules")
            assertEquals(SymbolExtent.initialCrop(light)?.toList(), SymbolExtent.initialCrop(dark)?.toList(), "margin $modules")
            val estimate = assertNotNull(InitialGridEstimator.estimate(dark, emptyList()), "margin $modules")
            assertEquals(2, estimate.version, "margin $modules")
            val first = modules * unit + unit / 2.0
            val last = first + 24 * unit
            for ((actual, expected) in estimate.moduleCenterQuad.asList().zip(listOf(
                PointD(first, first), PointD(last, first), PointD(last, last), PointD(first, last)))) {
                assertTrue(actual.distanceTo(expected) < unit * .5, "margin $modules: $actual vs $expected")
            }
        }
    }

    @Test fun initialCropIsTheWholeImageWhenTheContentFillsItOrTheImageIsUniform() {
        assertNull(SymbolExtent.initialCrop(partlyUnknown(10, unknownFrom = 13, marginModules = 1)))
        val light = partlyUnknown(10, unknownFrom = 13, marginModules = 20)
        val dark = LumaImage(light.width, light.height, IntArray(light.pixels.size) { 255 - light.pixels[it] })
        assertEquals(SymbolExtent.initialCrop(light)?.toList(), SymbolExtent.initialCrop(dark)?.toList())
        assertNull(SymbolExtent.initialCrop(LumaImage(50, 50, IntArray(2500) { 250 })))
        assertNull(SymbolExtent.initialCrop(LumaImage(50, 50, IntArray(2500) { 0 })))
    }

    @Test fun contentReachingTheImageEdgeKeepsTheWholeImage() {
        // Without a quiet zone the outermost pixels cross Finder, data and unknown gray modules alike,
        // so no part of the symbol may be cut away, whichever side the unknown modules lie on.
        for (unknownFrom in listOf(13, 0)) {
            val image = partlyUnknown(10, unknownFrom = unknownFrom, marginModules = 0, unknownRows = if (unknownFrom == 0) 8..24 else 0..24)
            assertNull(SymbolExtent.surroundTone(image), "unknown from $unknownFrom")
            assertEquals(listOf(0, 0, image.width - 1, image.height - 1), assertNotNull(SymbolExtent.bounds(image)).toList())
            assertNull(SymbolExtent.initialCrop(image), "unknown from $unknownFrom")
        }
    }

    @Test fun downscaledSymbolWithAQuietZoneKeepsItsGridAtAnyCropMargin() {
        // A symbol with its own four-module quiet zone and unknown regions reaching the original crop
        // edge, cropped with a growing white margin and reduced to a fixed working size. The window
        // follows the unknown regions too, so every margin presents the same content to the estimator.
        val unit = 24
        val n = 29
        fun black(x: Int, y: Int): Boolean {
            for ((sx, sy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val dx = x - sx; val dy = y - sy
                if (dx in -1..7 && dy in -1..7) {
                    if (dx !in 0..6 || dy !in 0..6) return false
                    return dx == 0 || dy == 0 || dx == 6 || dy == 6 || (dx in 2..4 && dy in 2..4)
                }
            }
            if (x == 6 && y in 8..n - 9) return y % 2 == 0
            if (y == 6 && x in 8..n - 9) return x % 2 == 0
            return (x * 13 + y * 7 + x * y) % 5 < 2
        }
        for (factor in listOf(0.0, 0.5, 2.0)) {
            val symbolSide = (n + 8) * unit
            val pad = (symbolSide * factor).roundToInt()
            val full = symbolSide + 2 * pad
            val working = minOf(full, 1800)
            val scale = working.toDouble() / full
            fun sourceTone(px: Double, py: Double): Double {
                val qx = floor((px - pad) / unit).toInt(); val qy = floor((py - pad) / unit).toInt()
                val x = qx - 4; val y = qy - 4
                val side = n + 8
                return when {
                    qx !in 0 until side || qy !in 0 until side -> 255.0
                    // Unknown regions that spread over the quiet zone up to the original crop edge.
                    qx + qy < 16 || (qx < 12 && qy >= side - 12) || (x in 12..20 && y in 12..20) -> 128.0
                    x !in 0 until n || y !in 0 until n -> 255.0
                    black(x, y) -> 0.0
                    else -> 255.0
                }
            }
            // Area-average reduction, as a canvas or bitmap scaler would blend source pixels.
            val samples = 3
            val image = LumaImage(working, working, IntArray(working * working) { i ->
                var sum = 0.0
                for (sy in 0 until samples) for (sx in 0 until samples)
                    sum += sourceTone(((i % working) + (sx + .5) / samples) / scale, ((i / working) + (sy + .5) / samples) / scale)
                (sum / (samples * samples)).roundToInt()
            })
            val label = "margin ${factor}x"
            val estimate = assertNotNull(InitialGridEstimator.estimate(image, emptyList()), label)
            assertEquals(3, estimate.version, label)
            val a = (pad + 4 * unit + unit / 2.0) * scale; val b = a + 28 * unit * scale
            for ((actual, expected) in estimate.moduleCenterQuad.asList().zip(listOf(PointD(a, a), PointD(b, a), PointD(b, b), PointD(a, b))))
                assertTrue(actual.distanceTo(expected) < unit * scale * .5, "$label: $actual vs $expected")
        }
    }
}
