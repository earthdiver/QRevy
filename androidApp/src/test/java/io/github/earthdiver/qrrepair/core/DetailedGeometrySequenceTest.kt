package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class DetailedGeometrySequenceTest {
    private fun grid(id: Int) = GridGeometry(1, FinderRole.TOP_LEFT, id.toDouble(), 10.0, 4.0, 0.0)
    private fun hypothesis(id: Int) = GeometryHypothesis(emptyList(), grid(id), id.toDouble())

    @Test fun selectedAndOrdinaryGridsAreAvailableBeforeIndependentCurveGeneration() {
        val generated = mutableListOf<Int>()
        val iterator = detailedGeometrySequence(hypothesis(1), listOf(hypothesis(2)), listOf(hypothesis(2)),
            needsJointFit = { false }, curves = {
                sequence { for (id in 3..14) { generated += id; yield(grid(id)) } }
            }, score = { it.finderCenterX }, refine = { fail("No joint fit requested"); null }, check = {}).iterator()
        assertEquals(grid(1), iterator.next().geometry)
        assertEquals(grid(2), iterator.next().geometry)
        assertTrue(generated.isEmpty())
        assertEquals(grid(3), iterator.next().geometry)
        assertEquals(listOf(3), generated)
        val remaining = iterator.asSequence().map { it.geometry }.toList()
        assertEquals((4..14).map(::grid), remaining)
        assertEquals((3..14).toList(), generated)
    }

    @Test fun jointReplacementsAndDuplicateSlotsKeepTheDetailedSearchOrder() {
        val selected = hypothesis(1)
        val seeds = listOf(hypothesis(2), hypothesis(3), hypothesis(4))
        val generated = mutableListOf<Int>()
        val candidates = mapOf(grid(2) to listOf(grid(7), grid(6)), grid(3) to emptyList(),
            grid(4) to listOf(grid(6), grid(8)))
        val iterator = detailedGeometrySequence(selected,
            listOf(selected, hypothesis(3), hypothesis(2)), seeds,
            needsJointFit = { it.geometry != grid(4) }, curves = { base -> sequence {
                generated += base.finderCenterX.toInt(); yieldAll(candidates.getValue(base))
            } }, score = { it.finderCenterX }, refine = { if (it == grid(6)) grid(9) else grid(10) }, check = {}).iterator()
        assertEquals(grid(1), iterator.next().geometry)
        assertTrue(generated.isEmpty())
        val rest = iterator.asSequence().map { it.geometry }.toList()
        // Empty curved list replaces the ordinary base; duplicate curved 6 maps to 9 only once.
        assertEquals(listOf(10, 2, 7, 9, 8).map(::grid), rest)
        assertEquals(listOf(2, 3, 4), generated)
    }

    @Test fun selectedGridIsNeverJointRefittedAndCancellationStopsLazyGeneration() {
        var cancel = false
        var generated = 0
        val iterator = detailedGeometrySequence(hypothesis(1), emptyList(), listOf(hypothesis(2)),
            needsJointFit = { true }, curves = { sequence { generated++; yield(grid(1)) } },
            score = { 0.0 }, refine = { fail("Selected grid must not be replaced"); null },
            check = { if (cancel) throw RecoveryCancelledException() }).iterator()
        assertEquals(grid(1), iterator.next().geometry)
        cancel = true
        assertThrows(RecoveryCancelledException::class.java) { iterator.hasNext() }
        assertEquals(0, generated)
    }
}
