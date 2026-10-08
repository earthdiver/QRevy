package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class ListDecodingReuseTest {
    private val memory = 32L * 1024 * 1024
    private fun damaged() = RsBlockWork(19, IntArray(26) { if (it in 2..5) 1 else 0 }, IntArray(26))
    private fun search(block: RsBlockWork, cache: ListDecodingTrialCache, limit: Int = 5,
        original: RsBlockWork = block, accepts: (IntArray) -> Boolean = { true },
        check: () -> Unit = {}) = ListDecodingBlockSearch(block, original, emptyList(), 0, true,
            emptySet(), limit, accepts = accepts, check = check, cache = cache)
    private fun finish(search: ListDecodingBlockSearch, cache: ListDecodingTrialCache) {
        while (search.hasNext) search.advance(memory - cache.workingBytes)
    }

    @Test fun completedRootsAreReusedWhileEachTrialRechecksPinsAndKnownBitLimits() {
        val block = damaged()
        val cache = ListDecodingTrialCache(256 * 1024)
        val pinned = search(block, cache, accepts = { it[2] == 1 })
        finish(pinned, cache)
        assertTrue(pinned.candidates.isEmpty())
        assertEquals(mapOf(ListDecoderMethod.WU to 1), pinned.methodTrials)

        val tooStrict = search(block, cache, limit = 3)
        finish(tooStrict, cache)
        assertTrue(tooStrict.candidates.isEmpty())
        assertTrue(tooStrict.mismatchRejections > 0)
        assertEquals(0, tooStrict.trials)
        assertEquals(1, tooStrict.reusedTrials)

        val allowed = search(block, cache)
        finish(allowed, cache)
        assertTrue(allowed.candidates.single().corrected.all { it == 0 })
        assertEquals(4, allowed.candidates.single().originalKnownMismatches)
        assertEquals(0, allowed.trials)
        assertEquals(1, allowed.reusedTrials)
        assertTrue(allowed.methodTrials.isEmpty())
        assertEquals(ListDecoderMethod.WU, allowed.candidates.single().listDecoding?.method)
        val repinned = search(block, cache, accepts = { it[2] == 1 })
        finish(repinned, cache)
        assertTrue(repinned.candidates.isEmpty())
        assertEquals(1, repinned.reusedTrials)

        val unknownOriginal = block.copyDeep().also { for (i in 2..5) it.unknownMasks[i] = 1 }
        val differentEvidence = search(block, cache, limit = 0, original = unknownOriginal)
        finish(differentEvidence, cache)
        assertEquals(0, differentEvidence.candidates.single().originalKnownMismatches)
        assertEquals(1, differentEvidence.reusedTrials)
    }

    @Test fun changedValuesAndErasureMasksRequireNewTrialsWithoutChangingStoredInputs() {
        val block = damaged()
        val cache = ListDecodingTrialCache(256 * 1024)
        finish(search(block, cache), cache)
        block.codewords[6] = 1
        val differentValue = search(block, cache)
        finish(differentValue, cache)
        assertEquals(0, differentValue.reusedTrials)
        assertEquals(1, differentValue.trials)
        block.codewords[6] = 0
        block.unknownMasks[2] = 255
        block.unknownMasks[3] = 255
        val differentMasks = search(block, cache)
        finish(differentMasks, cache)
        assertEquals(0, differentMasks.reusedTrials)
        assertEquals(1, differentMasks.trials)
        block.unknownMasks.fill(0)
        val unchanged = search(block, cache)
        finish(unchanged, cache)
        assertEquals(1, unchanged.reusedTrials)
        assertTrue(unchanged.candidates.single().corrected.all { it == 0 })
    }

    @Test fun interruptedOrTimedOutDecodingIsRetriedInsteadOfReusedAsACompleteList() {
        for (stop in listOf(ListDecodingSliceLimitException(), ListDecodingTimeLimitException(), RecoveryCancelledException())) {
            val block = damaged()
            val cache = ListDecodingTrialCache(256 * 1024)
            lateinit var interrupted: ListDecodingBlockSearch
            interrupted = search(block, cache, check = { if (interrupted.methodTrials.isNotEmpty()) throw stop })
            assertSame(stop, assertFails { finish(interrupted, cache) })
            assertEquals(0L, cache.workingBytes)
            val retried = search(block, cache)
            finish(retried, cache)
            assertEquals(1, retried.trials)
            assertEquals(0, retried.reusedTrials)
            assertTrue(retried.candidates.single().corrected.all { it == 0 })
        }
    }

    @Test fun insufficientMemoryNeverPublishesAnIncompleteListOrPreventsUncachedDecoding() {
        val block = damaged()
        val cache = ListDecodingTrialCache(256 * 1024)
        val limited = search(block, cache)
        limited.advance(0)
        assertTrue(limited.memoryLimited)
        assertEquals(0L, cache.workingBytes)
        val retried = search(block, cache)
        finish(retried, cache)
        assertEquals(1, retried.trials)
        assertTrue(retried.candidates.single().corrected.all { it == 0 })

        val tiny = ListDecodingTrialCache(1)
        repeat(2) {
            val uncached = search(block, tiny)
            finish(uncached, tiny)
            assertEquals(1, uncached.trials)
            assertEquals(0, uncached.reusedTrials)
            assertTrue(uncached.candidates.single().corrected.all { it == 0 })
            assertEquals(0L, tiny.workingBytes)
        }
    }

    @Test fun interruptionAfterOneRootDoesNotHideTheRestOfTheDecoderListOnRetry() {
        val other = ReedSolomonErasures().encode(IntArray(19).also { it[0] = 1 }, 7)
        val different = other.indices.filter { other[it] != 0 }
        assertEquals(8, different.size)
        val received = IntArray(26).also { values -> different.take(4).forEach { values[it] = other[it] } }
        val block = RsBlockWork(19, received, IntArray(26))
        val cache = ListDecodingTrialCache(256 * 1024)
        var rootsSeen = 0
        val interrupted = search(block, cache, limit = 100,
            accepts = { rootsSeen++; true }, check = { if (rootsSeen > 0) throw ListDecodingSliceLimitException() })
        assertFailsWith<ListDecodingSliceLimitException> { finish(interrupted, cache) }
        assertEquals(1, interrupted.candidates.size)
        assertEquals(0L, cache.workingBytes)
        val retried = search(block, cache, limit = 100)
        finish(retried, cache)
        assertEquals(0, retried.reusedTrials)
        assertEquals(1, retried.trials)
        assertTrue(retried.candidates.any { it.corrected.all { value -> value == 0 } })
        assertTrue(retried.candidates.any { it.corrected.contentEquals(other) })
    }

    @Test fun aCompletedEmptyListIsReusableWithoutInventingACandidate() {
        val block = damaged().also { it.codewords[6] = 1 }
        val cache = ListDecodingTrialCache(256 * 1024)
        val first = search(block, cache)
        finish(first, cache)
        assertTrue(first.candidates.isEmpty())
        assertEquals(1, first.trials)
        val second = search(block, cache)
        finish(second, cache)
        assertTrue(second.candidates.isEmpty())
        assertEquals(0, second.trials)
        assertEquals(1, second.reusedTrials)
    }

    @Test fun completedKvRootsAreRecheckedAgainstTheCurrentMismatchLimit() {
        val encoded = ReedSolomonErasures().encode(IntArray(9) { (it * 17 + 23) and 255 }, 17)
        val masks = IntArray(26) { if (it < 18) 1 else 0 }
        val received = encoded.copyOf().also { values ->
            for (i in 0 until 18) values[i] = values[i] and 254
            values[20] = values[20] xor 2
        }
        val block = RsBlockWork(9, received, masks)
        val cache = ListDecodingTrialCache(256 * 1024)
        val rejected = search(block, cache, limit = 0)
        finish(rejected, cache)
        assertTrue(rejected.candidates.isEmpty())
        assertEquals(mapOf(ListDecoderMethod.KOETTER_VARDY to 1), rejected.methodTrials)
        val allowed = search(block, cache, limit = 1)
        finish(allowed, cache)
        assertContentEquals(encoded, allowed.candidates.single().corrected)
        assertEquals(0, allowed.trials)
        assertEquals(0, allowed.interpolations)
        assertEquals(1, allowed.reusedTrials)
        assertEquals(ListDecoderMethod.KOETTER_VARDY, allowed.candidates.single().listDecoding?.method)
    }
}
