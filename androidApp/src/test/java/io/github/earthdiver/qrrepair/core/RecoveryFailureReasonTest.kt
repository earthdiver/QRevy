package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryFailureReasonTest {
    @Test fun classifiesErasureCapacityShortfall() {
        assertEquals(
            RecoveryFailureReason.ERASURE_CAPACITY_EXCEEDED,
            classifyRsFailure("Too many erasures: 9 > 7", erasureShortfallBits = 3),
        )
    }

    @Test fun classifiesBoundedBitSearchLimit() {
        assertEquals(
            RecoveryFailureReason.BOUNDED_BIT_SEARCH_LIMIT,
            classifyRsFailure("Too many erasure bytes for bounded partial-bit solver", erasureShortfallBits = 5),
        )
    }

    @Test fun distinguishesKnownErrorsWhenErasureShortfallIsZero() {
        assertEquals(
            RecoveryFailureReason.ERROR_ERASURE_CAPACITY_EXCEEDED,
            classifyRsFailure("Too many unknown errors: 4 with 2 erasures for 7 ECC symbols", erasureShortfallBits = 0),
        )
    }

    @Test fun classifiesSearchWithoutConsistentAssignment() {
        assertEquals(
            RecoveryFailureReason.NO_RS_CONSISTENT_ASSIGNMENT,
            classifyRsFailure("Partial-bit enumeration found no RS-consistent assignment", erasureShortfallBits = 0),
        )
    }

    @Test fun treatsResidualSyndromeAsCorrectionFailure() {
        assertEquals(
            RecoveryFailureReason.RS_CORRECTION_FAILED,
            classifyRsFailure("Could not correct RS block", erasureShortfallBits = 0),
        )
    }

    @Test fun combinationSearchLimitHasASeparateFailureReason() {
        assertEquals(
            RecoveryFailureReason.QR_COMBINATION_SEARCH_LIMIT,
            RecoveryFailureReason.valueOf("QR_COMBINATION_SEARCH_LIMIT"),
        )
    }
}
