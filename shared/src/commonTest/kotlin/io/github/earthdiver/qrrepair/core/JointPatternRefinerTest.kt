package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JointPatternRefinerTest {
    private val seed = GridGeometry(1, FinderRole.TOP_LEFT, 35.2, 34.6, 6.0, 0.0).asProjective()
    private val size = 170
    private fun image(): LumaImage {
        val pixels = IntArray(size * size) { i ->
        val x = ((i % size - 16) / 6.0).roundToInt()
        val y = ((i / size - 16) / 6.0).roundToInt()
        val finder = listOf(3 to 3, 17 to 3, 3 to 17).firstOrNull { (cx, cy) -> max(abs(x - cx), abs(y - cy)) <= 4 }
        val radius = finder?.let { (cx, cy) -> max(abs(x - cx), abs(y - cy)) }
        if (radius != null && radius != 2 && radius != 4) 20 else 235
        }
        return LumaImage(size, size, IntArray(pixels.size) { i ->
            var sum = 0
            for (dy in -1..1) for (dx in -1..1)
                sum += pixels[(i / size + dy).coerceIn(0, size - 1) * size + (i % size + dx).coerceIn(0, size - 1)]
            sum / 9
        })
    }

    @Test fun acceptanceReportsUnweightedResidualsOnIdenticalFineScaleProbes() {
        val image = image()
        val result = assertNotNull(JointPatternRefiner.refine(image, seed, intArrayOf(), false))
        val pitch = seed.localModulePitch(10.0, 10.0)
        val crop = assertNotNull(ProjectivePatternImage.crop(image, seed.moduleCenterQuad(), pitch, false) { false })
        val layer = assertNotNull(crop.blurred(.035 * pitch) { false })
        val probes = ProjectivePatternTemplate(21, intArrayOf()).observations(seed, crop, .035)
        fun residuals(geometry: GridGeometry): Double = probes.sumOf { p ->
            val point = geometry.moduleToImage((p.x + 1) * 10, (p.y + 1) * 10)
            val value = DoubleArray(3)
            assertTrue(layer.sample(point.x, point.y, value))
            (value[0] - p.target) * (value[0] - p.target)
        }
        assertEquals(residuals(seed), result.initialResidualSumSquares, 1e-8)
        assertEquals(residuals(result.geometry), result.residualSumSquares, 1e-8)
        assertTrue(result.residualSumSquares < result.initialResidualSumSquares)
    }

    @Test fun missingEvidenceAndCancellationLeaveTheOriginalMappingAvailable() {
        assertNull(JointPatternRefiner.refine(LumaImage(size, size, IntArray(size * size) { 128 }), seed, intArrayOf(), false))
        assertNull(JointPatternRefiner.refine(image(), seed, intArrayOf(), false, stopped = { true }))
        assertNull(JointPatternRefiner.refine(image(), seed, intArrayOf(), false, maxIterationsPerScale = 1))
    }
}
