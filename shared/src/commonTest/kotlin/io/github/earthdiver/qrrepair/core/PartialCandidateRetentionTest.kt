package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartialCandidateRetentionTest {
    @Test
    fun enumeratesPartialErasuresWhenCapacityFullDecodeChoosesWrongCodeword() {
        val rs = ReedSolomonErasures()
        val data = IntArray(18) { (it * 29 + 7) and 0xFF }
        val nsym = 10
        val encoded = rs.encode(data, nsym)
        val damaged = encoded.copyOf()
        val unknownMasks = IntArray(encoded.size)
        for (position in 0 until nsym) {
            unknownMasks[position] = 1
            damaged[position] = damaged[position] and 0xFE
        }
        damaged[nsym + 2] = damaged[nsym + 2] xor 0x5A

        val erasureOnly = rs.decode(damaged, nsym, (0 until nsym).toList().toIntArray())
        assertTrue(!erasureOnly.corrected.contentEquals(encoded))

        val candidates = PartialCodewordSolver(rs).decodeCandidates(
            RsBlockWork(data.size, damaged, unknownMasks),
            nsym,
        )

        assertTrue(candidates.any { it.corrected.contentEquals(encoded) })
        assertTrue(candidates.any { it.guessedPositions.size >= 2 })
    }

    @Test
    fun enumeratesEnoughPartialErasuresForMultipleUnknownErrors() {
        val rs = ReedSolomonErasures()
        val data = IntArray(18) { (it * 37 + 11) and 0xFF }
        val nsym = 10
        val encoded = rs.encode(data, nsym)
        val damaged = encoded.copyOf()
        val unknownMasks = IntArray(encoded.size)
        for (position in 0 until 6) {
            unknownMasks[position] = 1
            damaged[position] = damaged[position] and 0xFE
        }
        for ((position, delta) in listOf(8 to 0x53, 13 to 0xA7, 21 to 0x6C)) {
            damaged[position] = damaged[position] xor delta
        }

        val candidates = PartialCodewordSolver(rs).decodeCandidates(
            RsBlockWork(data.size, damaged, unknownMasks),
            nsym,
        )

        assertTrue(candidates.any { it.corrected.contentEquals(encoded) })
        assertTrue(candidates.any { candidate ->
            candidate.corrected.contentEquals(encoded) && candidate.guessedPositions.size >= 2
        })
    }

    @Test fun retainsMoreThanSixteenDistinctRsSolutions() {
        val candidates = BlockCandidateAccumulator()
        repeat(17) { value ->
            candidates.add(
                PartialCodewordSolver.Result(
                    corrected = intArrayOf(value),
                    errataPositions = intArrayOf(),
                    guessedBits = 1,
                    guessedBytes = 1,
                    guessedPositions = intArrayOf(0),
                    rsMargin = 0,
                ),
            )
        }

        assertEquals(17, candidates.results().size)
    }
}
