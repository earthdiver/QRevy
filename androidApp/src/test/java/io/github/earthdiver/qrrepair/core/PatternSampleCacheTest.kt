package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class PatternSampleCacheTest {
    @Test fun overlappingTimingSearchReusesObservationsWithoutChangingCoordinates() {
        val xs = DoubleArray(120) { 10.0 - 2.5 + (it / 3 + .5) / 8.0 }
        val ys = DoubleArray(120) { 6.0 + listOf(-.4, 0.0, .4)[it % 3] }
        val cache = PatternSampleCache.create(xs, ys, 6, 7, .125)!!
        var calls = 0
        val observe: (Double, Double) -> Double? = { x, y ->
            calls++
            if (x < 8.0) null else x * 13.0 + y * 7.0
        }
        for (iy in -7..7) for (ix in -6..6) for (i in xs.indices) {
            val x = xs[i] + ix * .125
            val y = ys[i] + iy * .125
            val actual = cache.value(i, ix + 6, iy + 7, observe)
            if (x < 8.0) assertTrue(actual.isNaN())
            else assertEquals(x * 13.0 + y * 7.0, actual, 0.0)
        }
        assertTrue("At least four fifths of repeated mappings should be avoided: $calls", calls < 120 * 13 * 15 / 5)
        val completedCalls = calls
        for (iy in 0..14) for (ix in 0..12) for (i in xs.indices) cache.value(i, ix, iy, observe)
        assertEquals("Unavailable observations are cached as well", completedCalls, calls)
    }

    @Test fun closeCoordinatesRemainDistinctAndCachesDoNotShareImageValues() {
        val xs = doubleArrayOf(.4 + .1, .6 - .1, .1 + .2, .3)
        val ys = DoubleArray(xs.size)
        val first = PatternSampleCache.create(xs, ys, 0, 0, .25)!!
        val second = PatternSampleCache.create(xs, ys, 0, 0, .25)!!
        var calls = 0
        for (i in xs.indices) {
            assertEquals(xs[i], first.value(i, 0, 0) { x, _ -> calls++; x }, 0.0)
            assertEquals(255.0 - xs[i], second.value(i, 0, 0) { x, _ -> 255.0 - x }, 0.0)
        }
        assertEquals(xs.toSet().size, calls)
        assertNotEquals(first.value(2, 0, 0) { _, _ -> error("Already cached") },
            first.value(3, 0, 0) { _, _ -> error("Already cached") }, 0.0)
    }

    @Test fun oversizedSearchFallsBackWithoutAllocatingAnUnboundedTable() {
        val separated = DoubleArray(300) { it * 1000.0 }
        assertNull(PatternSampleCache.create(separated, separated, 0, 0, .25))
        assertNull(PatternSampleCache.create(separated, separated, 100, 100, .25))
    }
}
