package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryFailureReasonTest {
    private val rs = ReedSolomonErasures()

    @Test fun classifiesDecoderErasureCapacityShortfall() {
        val error = assertThrows(ReedSolomonErasures.DecodeException::class.java) {
            rs.decode(rs.encode(IntArray(19), 7), 7, IntArray(9) { it })
        }
        assertEquals(RecoveryFailureReason.ERASURE_CAPACITY_EXCEEDED,
            classifyRsFailure(error.reason, erasureShortfallBits = 3))
    }

    @Test fun boundedSearchFailureTakesPrecedenceOverErasureShortfall() {
        val block = RsBlockWork(19, rs.encode(IntArray(19), 7), IntArray(26) { if (it < 8) 255 else 0 })
        val error = assertThrows(ReedSolomonErasures.DecodeException::class.java) {
            PartialCodewordSolver(rs).decodeCandidates(block, 7)
        }
        assertEquals(RecoveryFailureReason.BOUNDED_BIT_SEARCH_LIMIT,
            classifyRsFailure(error.reason, erasureShortfallBits = 8))
    }

    @Test fun diagnosticReasonDoesNotDependOnExceptionWording() {
        val reasons = mapOf(
            RsFailureReason.ERROR_ERASURE_CAPACITY_EXCEEDED to RecoveryFailureReason.ERROR_ERASURE_CAPACITY_EXCEEDED,
            RsFailureReason.NO_CONSISTENT_ASSIGNMENT to RecoveryFailureReason.NO_RS_CONSISTENT_ASSIGNMENT,
            RsFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT to RecoveryFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT,
            RsFailureReason.BIT_SPACE_LIMIT to RecoveryFailureReason.RS_BIT_SPACE_LIMIT,
        )
        for ((reason, diagnostic) in reasons) {
            for (message in listOf("", "Reworded decoder diagnostic", "Too many erasures: 9 > 7")) {
                val error = ReedSolomonErasures.DecodeException(message, reason)
                assertEquals(diagnostic, classifyRsFailure(error.reason, erasureShortfallBits = 8))
            }
        }
    }

    @Test fun unspecifiedFailureUsesObservedErasureShortfall() {
        val error = ReedSolomonErasures.DecodeException("Could not correct RS block")
        assertEquals(RecoveryFailureReason.RS_CORRECTION_FAILED,
            classifyRsFailure(error.reason, erasureShortfallBits = 0))
        assertEquals(RecoveryFailureReason.ERASURE_CAPACITY_EXCEEDED,
            classifyRsFailure(error.reason, erasureShortfallBits = 3))
        assertEquals(RecoveryFailureReason.RS_CORRECTION_FAILED,
            classifyRsFailure(null, erasureShortfallBits = 0))
    }

    @Test fun streamingSearchPreservesTheBlockFailureReason() {
        val block = RsBlockWork(19, rs.encode(IntArray(19), 7), IntArray(26) { if (it < 8) 255 else 0 })
        val outcome = stream(block, exactOnly = false)
        assertEquals(0, outcome.failedBlock)
        assertEquals(RsFailureReason.ERASURE_CAPACITY_EXCEEDED, outcome.failure)

        val exact = stream(block, exactOnly = true)
        assertEquals(RsFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT, exact.failure)
    }

    @Test fun streamingMemoryLimitRetainsBothItsFailureAndIncompleteSearchReason() {
        val block = RsBlockWork(19, rs.encode(IntArray(19), 7), IntArray(26))
        val stops = linkedSetOf<CombinationSearchStop>()
        val outcome = stream(block, exactOnly = true, workingLimit = 0, onStops = stops::addAll)
        assertEquals(RsFailureReason.BIT_SPACE_LIMIT, outcome.failure)
        assertEquals(setOf(CombinationSearchStop.WORKING_MEMORY_LIMIT), stops)
    }

    private fun stream(block: RsBlockWork, exactOnly: Boolean, workingLimit: Long = Long.MAX_VALUE,
        onStops: (Set<CombinationSearchStop>) -> Unit = {}): StreamingQrSearch.Outcome =
        StreamingQrSearch(rs).run(
            DeinterleavedQr(listOf(block), Array(block.codewords.size) { RawCodewordLocation(0, it) }, 7),
            spaces = listOf(null), version = 1, exactOnly = exactOnly,
            allowEnumeration = false, nonEnumerable = listOf(emptySet()), partialBitLimit = PartialBitSearchLimit.DEFAULT,
            workingLimitBytes = workingLimit, cancellation = RecoveryCancellation.NONE,
            onStops = onStops, onSearch = {}, evaluate = { error("Failed blocks must not reach payload validation") },
        )
}
