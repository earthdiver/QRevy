package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test

class SingleSegmentRetryPolicyTest {
    private val evidence = RecoverySearchDiagnostic(singleSegmentRetryAvailable = true)

    @Test fun promptNeedsEvidenceAndNoDecodedResultAndIsOfferedOncePerInputAndSettings() {
        val policy = SingleSegmentRetryPolicy()
        val input = Any()
        val settings = PreprocessSettings()
        assertFalse(policy.claim(input, settings, null, false))
        assertFalse(policy.claim(input, settings, evidence.copy(singleSegmentRetryAvailable = false), false))
        assertFalse(policy.claim(input, settings, evidence.copy(decodedCandidate = true), false))
        assertFalse(policy.claim(input, settings.copy(trustKnownPayloadCount = true), evidence, false))
        assertFalse(policy.claim(input, settings, evidence, true))
        assertFalse(policy.claim(input, settings, evidence.copy(incompleteReasons = setOf(SearchIncompleteReason.USER_INTERRUPTED)), false))
        assertFalse(policy.claim(input, settings, evidence.copy(coverageComplete = false), false))
        // Repaired but undecoded matrices do not suppress the prompt.
        assertTrue(policy.claim(input, settings, evidence.copy(acceptedCandidate = true), false))
        assertFalse(policy.claim(input, settings, evidence, false))
        assertTrue(policy.claim(input, settings.copy(dataEccMismatchLimit = 3), evidence, false))
        assertFalse(policy.claim(input, settings, evidence, false))
        assertTrue(policy.claim(Any(), settings, evidence, false))
    }

    @Test fun combiningGeometriesKeepsDecodedEvidenceEvenWhenCandidatesAreOmitted() {
        val combined = RecoverySearchDiagnostic.combine(listOf(evidence,
            RecoverySearchDiagnostic(decodedCandidate = true, candidatesOmitted = true)))!!
        assertTrue(combined.singleSegmentRetryAvailable)
        assertTrue(combined.decodedCandidate)
        assertFalse(SingleSegmentRetryPolicy().claim(Any(), PreprocessSettings(), combined, false))
    }

    @Test fun clearingResultsClearsTheRuntimeOverride() {
        val state = RecoveryUiState()
        state.oneTimeSingleSegment = true
        state.clearAttempt()
        assertTrue(state.oneTimeSingleSegment)
        state.clear()
        assertFalse(state.oneTimeSingleSegment)
    }
}
