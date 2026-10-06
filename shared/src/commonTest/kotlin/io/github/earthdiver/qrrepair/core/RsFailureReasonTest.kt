package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RsFailureReasonTest {
    private val rs = ReedSolomonErasures()
    private val partial = PartialCodewordSolver(rs)

    @Test fun erasureOverflowAndBoundedSearchExhaustionHaveDifferentReasons() {
        val encoded = rs.encode(IntArray(19) { it * 11 }, 7)
        val erased = IntArray(8) { it }
        val decode = assertFailsWith<ReedSolomonErasures.DecodeException> { rs.decode(encoded, 7, erased) }
        assertEquals(RsFailureReason.ERASURE_CAPACITY_EXCEEDED, decode.reason)
        val block = RsBlockWork(19, encoded, IntArray(26) { if (it < 8) 255 else 0 })
        val ordinary = assertFailsWith<ReedSolomonErasures.DecodeException> {
            partial.decodeCandidates(block, 7, allowBitEnumeration = false)
        }
        assertEquals(RsFailureReason.ERASURE_CAPACITY_EXCEEDED, ordinary.reason)
        val bounded = assertFailsWith<ReedSolomonErasures.DecodeException> { partial.decodeCandidates(block, 7) }
        assertEquals(RsFailureReason.BOUNDED_BIT_SEARCH_LIMIT, bounded.reason)
    }

    @Test fun inconsistentKnownBitsAreDifferentFromAnUnsearchedExactSpace() {
        val received = rs.encode(intArrayOf(3, 7, 11, 15), 6).also { it[8] = it[8] xor 1 }
        val inconsistent = RsBlockWork(4, received, IntArray(10))
        val conflict = assertFailsWith<ReedSolomonErasures.DecodeException> {
            partial.decodeWithBitConstraints(inconsistent, 6,
                RsBitConstraintSolver(rs).analyze(inconsistent, 6), exactOnly = true)
        }
        assertEquals(RsFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT, conflict.reason)

        val unknown = RsBlockWork(19, IntArray(26), IntArray(26) { 255 })
        val space = assertNotNull(RsBitConstraintSolver(rs).analyze(unknown, 7))
        val limited = assertFailsWith<ReedSolomonErasures.DecodeException> {
            partial.decodeWithBitConstraints(unknown, 7, space, exactOnly = true)
        }
        assertEquals(RsFailureReason.BIT_SPACE_LIMIT, limited.reason)
    }

    @Test fun invalidErasurePositionRemainsAGenericCorrectionFailure() {
        val error = assertFailsWith<ReedSolomonErasures.DecodeException> {
            rs.decode(rs.encode(intArrayOf(3, 7, 11, 15), 6), 6, intArrayOf(10))
        }
        assertEquals(RsFailureReason.CORRECTION_FAILED, error.reason)
    }
}
