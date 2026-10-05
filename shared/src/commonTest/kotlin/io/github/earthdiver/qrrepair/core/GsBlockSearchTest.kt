package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class GsBlockSearchTest {
    private val memory = 32L * 1024 * 1024

    @Test fun unknownAssignmentsMakeAnInitiallyOverErasedBlockEligible() {
        val original = RsBlockWork(19, IntArray(26), IntArray(26))
        for (i in 0..6) original.unknownMasks[i] = 1
        original.unknownMasks[24] = 255
        original.unknownMasks[25] = 255
        for (i in 10..12) original.codewords[i] = 1
        val before = original.copyDeep()
        val search = GsBlockSearch(original, original, emptyList(), 12, true, emptySet(), 11)
        assertFalse(search.advance(memory)) // Nine erased symbols, only seven parity symbols.
        assertTrue(search.advance(memory)) // Seven unknown bits assigned, two erasures remain.
        val restored = search.candidates.single { it.corrected.all { value -> value == 0 } }
        assertEquals(7, restored.guessedBits)
        assertEquals(3, restored.originalKnownMismatches)
        assertEquals(2, restored.gs?.remainingErasures)
        assertEquals(3, restored.gs?.radius)
        assertContentEquals(before.codewords, original.codewords)
        assertContentEquals(before.unknownMasks, original.unknownMasks)
    }

    @Test fun originalUnknownBitsAreExcludedButKnownBitsInPartialBytesAreCounted() {
        val original = RsBlockWork(19, IntArray(26), IntArray(26))
        for (i in 0..4) { original.unknownMasks[i] = 1; original.codewords[i] = 1 }
        for (i in listOf(0, 5, 6, 7)) original.codewords[i] = original.codewords[i] xor 2
        for (limit in listOf(3, 4)) {
            val search = GsBlockSearch(original, original, emptyList(), 12, true, emptySet(), limit)
            search.advance(memory)
            search.advance(memory)
            val correct = search.candidates.firstOrNull { it.corrected.all { bit -> bit == 0 } }
            if (limit == 3) { assertNull(correct); assertTrue(search.mismatchRejections > 0) }
            else assertEquals(4, assertNotNull(correct).originalKnownMismatches)
        }
    }

    @Test fun aCloseOrdinaryResultProvesInterpolationCannotAddAnotherCandidate() {
        val block = RsBlockWork(19, IntArray(26), IntArray(26))
        val search = GsBlockSearch(block, block, emptyList(), 12, true, emptySet(), 11)
        assertTrue(search.advance(memory))
        assertEquals(0, search.interpolations)
        assertFalse(search.hasNext)
        assertNull(search.candidates.single().gs)
    }

    @Test fun hardConstraintsAndResourceStopsRemainDistinctFromNoRoots() {
        val block = RsBlockWork(19, IntArray(26) { if (it in 2..5) 1 else 0 }, IntArray(26))
        val pinned = GsBlockSearch(block, block, emptyList(), 12, true, emptySet(), 11,
            accepts = { it[2] == 1 })
        pinned.advance(memory)
        assertTrue(pinned.candidates.none { it.allZero() })
        val limited = GsBlockSearch(block, block, emptyList(), 12, true, emptySet(), 11)
        limited.advance(0)
        assertTrue(limited.memoryLimited)
        assertTrue(limited.candidates.isEmpty())
    }

    @Test fun aSmallKnownBitCapCanRuleOutEveryAdditionalWordBeforeInterpolation() {
        val block = RsBlockWork(19, IntArray(26) { if (it in 2..5) 1 else 0 }, IntArray(26))
        val search = GsBlockSearch(block, block, emptyList(), 12, true, emptySet(), 3)
        search.advance(memory)
        assertEquals(0, search.interpolations)
        assertEquals(setOf(GsTrialSkip.MISMATCH_CAP), search.skipReasons)
        assertTrue(search.candidates.isEmpty())
    }

    private fun PartialCodewordSolver.Result.allZero() = corrected.all { it == 0 }
}
