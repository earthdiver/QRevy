package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedRecoverySolverTest {
    @Test fun preservesMoreThanSixteenRsBlockCombinationsForPayloadValidation() {
        val combinations = cartesianProductSequence(listOf((0 until 5).toList(), (0 until 4).toList())).toList()

        assertEquals(20, combinations.size)
        assertTrue(combinations.contains(listOf(4, 3)))
    }

    @Test fun streamsPastFormerQrLevelCombinationLimit() {
        val combinations = cartesianProductSequence(listOf((0 until 65).toList(), (0 until 64).toList()))

        assertEquals(4160, combinations.count())
        assertEquals(listOf(64, 63), combinations.last())
    }

    @Test fun oversizedProductStillExposesCandidatesForValidation() {
        val combinations = cartesianProductSequence(listOf((0 until 65).toList(), (0 until 64).toList()))
            .take(4096)

        assertEquals(4096, combinations.count())
        assertEquals(listOf(0, 0), combinations.first())
    }

    @Test fun boundedSequenceReportsWhetherCandidatesRemainUnvisited() {
        var truncated = false
        val bounded = (0 until 5).asSequence()
            .takeReportingTruncation(4) { truncated = true }
            .toList()

        assertEquals(listOf(0, 1, 2, 3), bounded)
        assertTrue(truncated)

        truncated = false
        assertEquals(
            listOf(0, 1, 2),
            (0 until 3).asSequence().takeReportingTruncation(4) { truncated = true }.toList(),
        )
        assertTrue(!truncated)
    }

    @Test fun expandingWindowReachesAResultBeyondTheFormerLimitThenStopsAtBoundary() {
        var found = false
        var truncated = false
        val visited = (0 until 20_000).asSequence()
            .takeInExpandingWindows(4096, 16_384, { !found }) { truncated = true }
            .onEach { if (it == 4096) found = true }
            .toList()

        assertTrue(found)
        assertEquals(8192, visited.size)
        assertEquals(8191, visited.last())
        assertTrue(truncated)
    }

    @Test fun expandingWindowReportsExhaustedMaximum() {
        var truncated = false
        val visited = (0 until 20_000).asSequence()
            .takeInExpandingWindows(4096, 8192, { true }) { truncated = true }
            .count()

        assertEquals(8192, visited)
        assertTrue(truncated)
    }

    @Test fun unparsedCandidateDoesNotStopSearchBeforeParsedPayload() {
        var candidateCount = 0
        var parsedCandidateCount = 0
        var truncated = false
        val visited = (0 until 20_000).asSequence()
            .takeInExpandingWindows(4096, 16_384, { parsedCandidateCount == 0 }) { truncated = true }
            .onEach { index ->
                if (index == 100) candidateCount++ // Strict bitstream validation only.
                if (index == 5000) {
                    candidateCount++
                    parsedCandidateCount++ // Final ZXing payload decode.
                }
            }
            .toList()

        assertEquals(2, candidateCount)
        assertEquals(1, parsedCandidateCount)
        assertEquals(8192, visited.size)
        assertTrue(truncated)
    }

    @Test fun unparsedCandidateStillReportsCombinationLimit() {
        var candidateCount = 0
        var parsedCandidateCount = 0
        var truncated = false
        val visited = (0 until 20_000).asSequence()
            .takeInExpandingWindows(4096, 8192, { parsedCandidateCount == 0 }) { truncated = true }
            .onEach { if (it == 100) candidateCount++ }
            .count()

        assertEquals(1, candidateCount)
        assertEquals(0, parsedCandidateCount)
        assertEquals(8192, visited)
        assertTrue(truncated)
    }

    @Test fun boundedBestFirstProductDoesNotExhaustOneEarlyBranch() {
        data class Ranked(val name: String, val penalty: Int)
        val first = listOf(Ranked("wrong", 10), Ranked("right", 11))
        val second = (0 until 5000).map { index -> Ranked("tail-$index", index) }

        val combinations = bestFirstCartesianProductSequence(listOf(first, second), { it.penalty })
            .take(4096)
            .toList()

        assertTrue(combinations.any { it[0].name == "right" && it[1].name == "tail-0" })
        assertEquals(10, combinations.first().sumOf { it.penalty })
        assertTrue(combinations.zipWithNext().all { (a, b) ->
            a.sumOf { it.penalty } <= b.sumOf { it.penalty }
        })
    }

    @Test fun prunesOnlyPrefixesRejectedByTheCaller() {
        val combinations = cartesianProductSequence(listOf(listOf(1, 2), listOf(3, 4))) { prefix ->
            prefix.first() != 2
        }.toList()

        assertEquals(listOf(listOf(1, 3), listOf(1, 4)), combinations)
    }

    @Test fun bestFirstLazySiblingsRetainEveryValidCombinationInRankOrder() {
        val groups = listOf(listOf(3, 0, 2), listOf(2, 0, 1), listOf(4, 0, 2))
        fun valid(prefix: List<Int>) = prefix[0] != 3 && (prefix.size < 2 || prefix[0] != prefix[1])
        val expected = cartesianProductSequence(groups, ::valid).toList()
        val actual = bestFirstCartesianProductSequence(groups, { it }, canExtend = ::valid).toList()
        assertEquals(expected.toSet(), actual.toSet())
        assertEquals(expected.size, actual.size)
        assertTrue(actual.zipWithNext().all { (a, b) -> a.sum() <= b.sum() })
    }

    @Test fun rejectedQrPrefixesAlsoConsumeTheSearchBudget() {
        var checked = 0
        var truncated = false
        val groups = List(6) { (0 until 4096).toList() }
        val result = bestFirstCartesianProductSequence(groups, { 0 }, maxExpandedNodes = 1000,
            onLimitReached = { truncated = true }) { prefix ->
            checked++
            prefix.size < 6
        }.toList()
        assertTrue(result.isEmpty())
        assertTrue(truncated)
        assertEquals(1000, checked)
    }

    @Test fun payloadConstraintsRecoverTerminatorAlignmentAndPadBytes() {
        val bits = mutableListOf<Int>()
        fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += (value shr i) and 1 }
        // Version 1 byte mode, one byte 'A'.
        put(0b0100, 4)
        put(1, 8)
        put(0x41, 8)
        put(0, 4)
        while (bits.size % 8 != 0) bits += 0
        var pad = 0
        while (bits.size < 80) put(if ((pad++ and 1) == 0) 0xEC else 0x11, 8)
        val bytes = IntArray(10)
        for (b in bytes.indices) for (i in 0..7) bytes[b] = (bytes[b] shl 1) or bits[b * 8 + i]
        val expected = bytes.copyOf()
        val masks = IntArray(bytes.size)
        // Keep the first pad byte as evidence that this is a single-segment symbol; erase the rest.
        for (i in 4 until bytes.size) { bytes[i] = 0; masks[i] = 0xFF }
        val block = RsBlockWork(bytes.size, bytes, masks)
        val de = DeinterleavedQr(listOf(block), emptyArray(), 0)
        val result = PayloadConstraintSolver.apply(de, version = 1)
        assertEquals(4, result.inferredMode)
        assertEquals(1, result.inferredCount)
        assertEquals(48, result.filledBits)
        assertEquals(result.filledBits, result.filled.size)
        assertTrue(!result.forcedByTrustedHeader)
        assertTrue(result.filled.any { it.kind == PayloadConstraintSolver.ConstraintKind.PAD })
        assertTrue(result.evidenceBits >= 6)
        assertArrayEquals(expected, block.codewords)
        assertTrue(block.unknownMasks.all { it == 0 })
    }

    @Test fun partialBitSearchCanExceedByteErasureLimit() {
        val rs = ReedSolomonErasures()
        val data = IntArray(22) { (it * 17 + 3) and 0xFF }
        val encoded = rs.encode(data, 22)
        val partial = encoded.copyOf()
        val unknown = IntArray(encoded.size)
        // 23 erased bytes is one beyond a 22-symbol erasure-only limit, but only one bit per byte is unknown.
        for (i in 0 until 23) {
            unknown[i] = 1
            partial[i] = partial[i] and 0xFE
        }
        val result = PartialCodewordSolver(rs).decode(RsBlockWork(22, partial, unknown), nsym = 22)
        assertArrayEquals(encoded, result.corrected)
        assertTrue(result.guessedBits in 1..12)
        assertTrue(result.guessedPositions.isNotEmpty())
        assertTrue(result.rsMargin >= 0)
    }

    @Test fun completeHeaderDetectionSkipsEciPrefix() {
        // ECI assignment 3, followed by a Version 1 byte-mode count of one.
        val data = intArrayOf(0x70, 0x34, 0x01)

        assertTrue(completePayloadHeaderAvailable(data, IntArray(data.size), version = 1))
    }

    @Test fun completeHeaderDetectionSupportsHanzi() {
        // Hanzi mode, GB2312 subset 1, Version 1 count of one.
        val data = intArrayOf(0xD1, 0x01)

        assertTrue(completePayloadHeaderAvailable(data, IntArray(data.size), version = 1))
        assertTrue(!completePayloadHeaderAvailable(data, intArrayOf(0, 1), version = 1))
    }
}
