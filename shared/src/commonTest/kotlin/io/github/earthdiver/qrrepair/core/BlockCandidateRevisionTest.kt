package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class BlockCandidateRevisionTest {
    @Test fun admissionOrderSurvivesSortingAndExistingRouteChangesInvalidateProducts() {
        fun result(value: Int, penalty: Int) = PartialCodewordSolver.Result(
            intArrayOf(value), intArrayOf(), 0, 0, intArrayOf(), 0, searchPenalty = penalty)
        val accumulator = BlockCandidateAccumulator()
        val first = result(1, 100)
        val second = result(2, 10)
        accumulator.add(first)
        accumulator.add(second)
        assertEquals(0L, accumulator.routeRevision)
        val sorted = accumulator.results()
        assertTrue(accumulator.admittedAfter(sorted[0], 1))
        assertFalse(accumulator.admittedAfter(sorted[1], 1))
        accumulator.add(first.copy(corrected = first.corrected.copyOf()))
        assertEquals(0L, accumulator.routeRevision)
        accumulator.add(first.copy(searchPenalty = 5))
        assertEquals(1L, accumulator.routeRevision)
        assertEquals(1, accumulator.results()[0].corrected.single())
        assertEquals(1, accumulator.results()[0].alternativeDerivations.size)
        assertFalse(accumulator.admittedAfter(accumulator.results()[0], 1))
    }
}
