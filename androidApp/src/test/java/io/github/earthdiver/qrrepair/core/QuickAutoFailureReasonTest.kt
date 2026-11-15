package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class QuickAutoFailureReasonTest {
    private fun result(count: Int = 0, diagnostic: RecoverySearchDiagnostic = RecoverySearchDiagnostic()) =
        AutoRecoveryResult(emptyList(), emptyList(), searchDiagnostic = diagnostic,
            discoveredCandidates = RecoveryDiscoveryCount.Exact(count))

    @Test fun deadlinesAreDistinguishedFromMissingGeometryAndUnresolvedWork() {
        assertEquals(QuickAutoFailureReason.NO_INITIAL_GRID, QuickAutoFailureReason.classify(result(), false, null))
        assertEquals(QuickAutoFailureReason.TIME_LIMIT, QuickAutoFailureReason.classify(result(), false, "TOTAL_TIME_LIMIT"))
        assertEquals(QuickAutoFailureReason.TIME_LIMIT, QuickAutoFailureReason.classify(result(), true, null, timeLimited = true))
        assertEquals(QuickAutoFailureReason.UNRESOLVED, QuickAutoFailureReason.classify(result(1), true, null))
        assertEquals(QuickAutoFailureReason.MULTIPLE_CANDIDATES, QuickAutoFailureReason.classify(result(2), true, null))
        assertEquals(QuickAutoFailureReason.NO_CANDIDATE, QuickAutoFailureReason.classify(result(), true, null))
    }

    @Test fun resourceCapsAndCancellationAreNotOfferedAsTimeExtensions() {
        for ((reason, expected) in listOf(SearchIncompleteReason.TIME_LIMIT to QuickAutoFailureReason.TIME_LIMIT,
            SearchIncompleteReason.LIST_DECODING_TIME_LIMIT to QuickAutoFailureReason.TIME_LIMIT,
            SearchIncompleteReason.WORKING_MEMORY_LIMIT to QuickAutoFailureReason.SEARCH_LIMIT,
            SearchIncompleteReason.INTERNAL_ERROR to QuickAutoFailureReason.INTERNAL_ERROR,
            SearchIncompleteReason.USER_INTERRUPTED to QuickAutoFailureReason.INTERRUPTED)) {
            assertEquals(expected, QuickAutoFailureReason.classify(result(diagnostic = RecoverySearchDiagnostic(
                incompleteReasons = setOf(reason))), true, null))
        }
        assertEquals(QuickAutoFailureReason.INTERRUPTED,
            QuickAutoFailureReason.classify(result(), true, "STOP_REQUESTED", timeLimited = true))
    }
}
