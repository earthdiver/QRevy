package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class ListDecodingBlockSearchTest {
    private val memory = 32L * 1024 * 1024

    @Test fun selectsWuOrGsForTheSameRadiusAccordingToWorkAndMemory() {
        val wu = assertNotNull(ListDecodingPlanner.hard(26, 19, 0, memory) {})
        val gs = assertNotNull(ListDecodingPlanner.hard(44, 8, 0, memory) {})
        assertEquals(ListDecoderMethod.WU, wu.method)
        assertEquals(ListDecoderMethod.GURUSWAMI_SUDAN, gs.method)
        assertEquals(ListDecoderMethod.GURUSWAMI_SUDAN, assertNotNull(ListDecodingPlanner.hard(15, 5, 0, memory) {}).method)
        val fallback = assertNotNull(ListDecodingPlanner.hard(15, 5, 0, WuDecoder(15, 5).workingBytes) {})
        assertEquals(ListDecoderMethod.WU, fallback.method)
        assertFailsWith<ListDecodingWorkingMemoryException> { ListDecodingPlanner.hard(26, 19, 0, 0) {} }
        val encoded = ReedSolomonErasures().encode(IntArray(8) { (it * 7 + 5) and 255 }, 36)
        val received = encoded.copyOf().also { for (i in 0 until 19) it[i] = it[i] xor 1 }
        val block = RsBlockWork(8, received, IntArray(44))
        val search = ListDecodingBlockSearch(block, block, emptyList(), 12, true, emptySet(), 32)
        assertTrue(search.advance(memory))
        assertEquals(ListDecoderMethod.GURUSWAMI_SUDAN, search.candidates.single { it.corrected.contentEquals(encoded) }.listDecoding?.method)
        assertEquals(1, search.interpolations)
    }

    @Test fun kvCanRecoverBeforeAnyUnknownBitAssignmentsAndStillEnforcesOriginalConstraints() {
        val encoded = ReedSolomonErasures().encode(IntArray(9) { (it * 17 + 23) and 255 }, 17)
        val masks = IntArray(26) { if (it < 18) 1 else 0 }
        val received = encoded.copyOf().also { values ->
            for (i in 0 until 18) values[i] = values[i] and 254
            values[20] = values[20] xor 2
        }
        val block = RsBlockWork(9, received, masks)
        for (limit in listOf(0, 1)) {
            val search = ListDecodingBlockSearch(block, block, emptyList(), 0, true, emptySet(), limit)
            while (search.hasNext) search.advance(memory)
            val restored = search.candidates.firstOrNull { it.corrected.contentEquals(encoded) }
            if (limit == 0) { assertNull(restored); assertTrue(search.mismatchRejections > 0) }
            else {
                assertEquals(0, assertNotNull(restored).guessedBits)
                assertEquals(ListDecoderMethod.KOETTER_VARDY, restored.listDecoding?.method)
                assertEquals(1, restored.originalKnownMismatches)
                assertEquals(mapOf(ListDecoderMethod.KOETTER_VARDY to 1), search.methodTrials)
            }
        }
        val pinned = ListDecodingBlockSearch(block, block, emptyList(), 0, true, emptySet(), 1,
            accepts = { it[20] == received[20] })
        while (pinned.hasNext) pinned.advance(memory)
        assertTrue(pinned.candidates.none { it.corrected.contentEquals(encoded) })
    }

    @Test fun unknownAssignmentsMakeAnInitiallyOverErasedBlockEligible() {
        val original = RsBlockWork(19, IntArray(26), IntArray(26))
        for (i in 0..6) original.unknownMasks[i] = 1
        original.unknownMasks[24] = 255
        original.unknownMasks[25] = 255
        for (i in 10..12) original.codewords[i] = 1
        val before = original.copyDeep()
        val search = ListDecodingBlockSearch(original, original, emptyList(), 12, true, emptySet(), 11)
        assertFalse(search.advance(memory)) // Nine erased symbols, only seven parity symbols.
        assertTrue(search.advance(memory)) // Seven unknown bits assigned, two erasures remain.
        val restored = search.candidates.single { it.corrected.all { value -> value == 0 } }
        assertEquals(7, restored.guessedBits)
        assertEquals(3, restored.originalKnownMismatches)
        assertEquals(2, restored.listDecoding?.remainingErasures)
        assertEquals(3, restored.listDecoding?.radius)
        assertContentEquals(before.codewords, original.codewords)
        assertContentEquals(before.unknownMasks, original.unknownMasks)
    }

    @Test fun originalUnknownBitsAreExcludedButKnownBitsInPartialBytesAreCounted() {
        val original = RsBlockWork(19, IntArray(26), IntArray(26))
        for (i in 0..4) { original.unknownMasks[i] = 1; original.codewords[i] = 1 }
        for (i in listOf(0, 5, 6, 7)) original.codewords[i] = original.codewords[i] xor 2
        for (limit in listOf(3, 4)) {
            val search = ListDecodingBlockSearch(original, original, emptyList(), 12, true, emptySet(), limit)
            while (search.hasNext) search.advance(memory)
            val correct = search.candidates.firstOrNull { it.corrected.all { bit -> bit == 0 } }
            if (limit == 3) { assertNull(correct); assertTrue(search.mismatchRejections > 0) }
            else assertEquals(4, assertNotNull(correct).originalKnownMismatches)
        }
    }

    @Test fun aCloseOrdinaryResultProvesInterpolationCannotAddAnotherCandidate() {
        val block = RsBlockWork(19, IntArray(26), IntArray(26))
        val search = ListDecodingBlockSearch(block, block, emptyList(), 12, true, emptySet(), 11)
        assertTrue(search.advance(memory))
        assertEquals(0, search.interpolations)
        assertFalse(search.hasNext)
        assertNull(search.candidates.single().listDecoding)
    }

    @Test fun hardConstraintsAndResourceStopsRemainDistinctFromNoRoots() {
        val block = RsBlockWork(19, IntArray(26) { if (it in 2..5) 1 else 0 }, IntArray(26))
        val pinned = ListDecodingBlockSearch(block, block, emptyList(), 12, true, emptySet(), 11,
            accepts = { it[2] == 1 })
        pinned.advance(memory)
        assertTrue(pinned.candidates.none { it.allZero() })
        val limited = ListDecodingBlockSearch(block, block, emptyList(), 12, true, emptySet(), 11)
        limited.advance(0)
        assertTrue(limited.memoryLimited)
        assertTrue(limited.candidates.isEmpty())
    }

    @Test fun aSmallKnownBitCapCanRuleOutEveryAdditionalWordBeforeInterpolation() {
        val block = RsBlockWork(19, IntArray(26) { if (it in 2..5) 1 else 0 }, IntArray(26))
        val search = ListDecodingBlockSearch(block, block, emptyList(), 12, true, emptySet(), 3)
        search.advance(memory)
        assertEquals(0, search.interpolations)
        assertEquals(setOf(ListDecodingTrialSkip.MISMATCH_CAP), search.skipReasons)
        assertTrue(search.candidates.isEmpty())
    }

    private fun PartialCodewordSolver.Result.allZero() = corrected.all { it == 0 }
}
