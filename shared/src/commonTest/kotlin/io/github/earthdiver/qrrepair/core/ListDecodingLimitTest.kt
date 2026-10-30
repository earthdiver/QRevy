package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class ListDecodingLimitTest {
    private val memory = 32L * 1024 * 1024

    @Test fun cachedSmallerRadiiDoNotHideWiderCandidatesOrChangeTheRequestedLimit() {
        val encoded = ReedSolomonErasures().encode(IntArray(9) { it * 13 }, 17)
        val block = RsBlockWork(9, encoded.copyOf().also { for (i in 9..19) it[i] = it[i] xor 1 }, IntArray(26))
        val cache = ListDecodingTrialCache(256 * 1024)
        for (extra in listOf(1, 2, 3, 1)) {
            val search = ListDecodingBlockSearch(block, block, emptyList(), 0, true, emptySet(), 11,
                cache = cache, additionalErrors = extra)
            while (search.hasNext) search.advance(memory - cache.workingBytes)
            assertEquals(extra == 3, search.candidates.any { it.corrected.contentEquals(encoded) })
            assertTrue(search.candidates.all { it.guessedBits == 0 })
            if (extra > 1) {
                assertEquals(extra - 1, search.reusedTrials)
                assertEquals(mapOf(8 + extra to 1), search.completedHardRadiusTrials)
            }
        }
    }

    @Test fun plannerChoosesWuOrGsAtWiderRadiiAndUsesTheJohnsonBoundary() {
        assertEquals(ListDecoderMethod.WU, assertNotNull(ListDecodingPlanner.hard(26, 13, 0, memory, 2) {}).method)
        assertEquals(ListDecoderMethod.GURUSWAMI_SUDAN, assertNotNull(ListDecodingPlanner.hard(26, 9, 0, memory, 3) {}).method)
        val wu = WuDecoder(26, 9)
        assertEquals(ListDecoderMethod.WU, assertNotNull(ListDecodingPlanner.hard(26, 9, 0, wu.workingBytes, 3) {}).method)
        assertNull(ListDecodingPlanner.hard(26, 13, 0, memory, 3) {})
        val block = RsBlockWork(19, IntArray(26) { if (it in 2..5) 1 else 0 }, IntArray(26))
        val search = ListDecodingBlockSearch(block, block, emptyList(), 0, true, emptySet(), 11, additionalErrors = 3)
        while (search.hasNext) search.advance(memory)
        assertEquals(mapOf(4 to 1), search.completedHardRadiusTrials)
        assertTrue(ListDecodingTrialSkip.NO_ADDITIONAL_RADIUS in search.skipReasons)
        assertTrue(search.candidates.any { it.corrected.all { value -> value == 0 } })
    }

    @Test fun interruptionRecordsTheWiderTrialAsUnfinishedAndDoesNotCacheIt() {
        val block = RsBlockWork(9, IntArray(26) { if (it in 9..19) 1 else 0 }, IntArray(26))
        val cache = ListDecodingTrialCache(256 * 1024)
        lateinit var search: ListDecodingBlockSearch
        search = ListDecodingBlockSearch(block, block, emptyList(), 0, true, emptySet(), 11,
            cache = cache, additionalErrors = 3, check = {
                if (11 in search.hardRadiusTrials) throw ListDecodingTimeLimitException()
            })
        assertFailsWith<ListDecodingTimeLimitException> { while (search.hasNext) search.advance(memory - cache.workingBytes) }
        assertEquals(setOf(9, 10, 11), search.hardRadiusTrials.keys)
        assertEquals(setOf(9, 10), search.completedHardRadiusTrials.keys)
        val retried = ListDecodingBlockSearch(block, block, emptyList(), 0, true, emptySet(), 11,
            cache = cache, additionalErrors = 3)
        while (retried.hasNext) retried.advance(memory - cache.workingBytes)
        assertEquals(2, retried.reusedTrials)
        assertEquals(mapOf(11 to 1), retried.completedHardRadiusTrials)
        assertTrue(retried.candidates.any { it.corrected.all { value -> value == 0 } })
    }

    @Test fun aWiderJohnsonBoundaryStillAllowsLaterUnknownBitAssignmentsAtSmallerRadii() {
        val encoded = ReedSolomonErasures().encode(IntArray(19) { it * 13 + 1 }, 7)
        val masks = IntArray(26) { when (it) { in 0..6 -> 1; 24, 25 -> 255; else -> 0 } }
        val received = encoded.copyOf().also { values ->
            for (i in values.indices) values[i] = values[i] and masks[i].inv()
            for (i in 10..12) values[i] = values[i] xor 1
        }
        val block = RsBlockWork(19, received, masks)
        for (extra in ListDecodingLimit.enabledExtensions) {
            val search = ListDecodingBlockSearch(block, block, emptyList(), 7, true, emptySet(), 3,
                additionalErrors = extra)
            while (search.hasNext) search.advance(memory)
            val restored = assertNotNull(search.candidates.firstOrNull { it.corrected.contentEquals(encoded) })
            assertEquals(7, restored.guessedBits)
            assertEquals(3, restored.listDecoding?.radius)
            assertTrue(assertNotNull(restored.listDecoding).assignment > 0)
            assertTrue(search.completedHardRadiusTrials.getValue(3) > 1)
        }
    }

    @Test fun unsupportedExtensionsAreRejected() {
        for (extra in listOf(-1, 0, 4, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { GuruswamiSudanDecoder(26, 9).parameters(0, memory, extra) }
        }
    }
}
