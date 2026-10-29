package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class RecoveryRunStateTest {
    private fun run(budget: RecoveryRetentionBudget = RecoveryRetentionBudget()) = RecoveryRunState(
        QrMatrix(21), budget, RecoveryCancellation.NONE, PartialBitSearchLimit.DEFAULT, ListDecodingBudget(),
    )

    private fun candidate(character: Char = 'A'): RecoveryCandidate = recoverPayload(PayloadBits().apply {
        put(4, 4); put(1, 8); put(character.code, 8)
    }.finish()).first { it.text == character.toString() }

    @Test fun listSearchBudgetIncludesTheLiveCoefficientCache() {
        val state = run()
        val limit = state.listSearchWorkingBytes
        val word = ReedSolomonErasures().encode(intArrayOf(1, 2, 3, 4, 5), 4)
        state.bitSolver.analyze(RsBlockWork(5, word, IntArray(9) { if (it < 2) 255 else 0 }), 4)
        assertTrue(state.bitSolver.cacheWorkingBytes > 0)
        assertEquals(limit - state.bitSolver.cacheWorkingBytes, state.listSearchWorkingBytes)
        assertEquals(state.listSearchWorkingBytes - state.listDecodingCache.workingBytes, state.availableWorkingBytes)
    }

    @Test fun omittedValidatedCandidatesStillCountAsParsedWithoutImplyingUnsearchedBranches() {
        val state = run(RecoveryRetentionBudget(0))
        val accepted = RecoveryCandidateEvaluation.Accepted(candidate(), decoderFailed = false)
        assertEquals(RecordedRecoveryCandidate.NEWLY_PARSED, state.recordEvaluation(accepted))
        assertTrue(state.candidates.results().isEmpty())
        state.finished = true
        val diagnostic = state.searchDiagnostic()
        assertTrue(diagnostic.acceptedCandidate)
        assertTrue(diagnostic.candidatesOmitted)
        assertFalse(diagnostic.unsearched)
        assertEquals(SearchCompleteness.COMPLETE, diagnostic.completeness)
        // A previously omitted result is still new relative to the retained set.
        assertEquals(RecordedRecoveryCandidate.NEWLY_PARSED, state.recordEvaluation(accepted))
    }

    @Test fun rediscoveredPayloadIsParsedButDoesNotSignalANewCandidate() {
        val state = run(RecoveryRetentionBudget(maxAlternativeDerivations = 0))
        val first = candidate()
        val accepted = RecoveryCandidateEvaluation.Accepted(first, decoderFailed = false)
        assertEquals(RecordedRecoveryCandidate.NEWLY_PARSED, state.recordEvaluation(accepted))
        val repeated = state.recordEvaluation(accepted.copy(candidate = first.copy()))
        assertTrue(repeated.parsed)
        assertEquals(RecordedRecoveryCandidate.PREVIOUSLY_PARSED, repeated)
        assertTrue(state.searchDiagnostic().derivationsOmitted)

        assertEquals(RecordedRecoveryCandidate.NEWLY_PARSED,
            state.recordEvaluation(accepted.copy(candidate = candidate('B'))))
        assertEquals(setOf("A", "B"), state.candidates.results().map { it.text }.toSet())
    }

    @Test fun rejectionsKeepTheirEvidenceWhenAGenericFailureOrAnUnresolvedTrialFollows() {
        val state = run()
        val failure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 30, 0, 30,
            RecoveryFailureStage.PAYLOAD_VALIDATION, reason = RecoveryFailureReason.MANUAL_PIN_CONFLICT,
            manualConflicts = listOf(ManualCellConflict(Coord(10, 10), ModuleValue.ZERO, ModuleValue.ONE)))
        val rejected = state.recordEvaluation(RecoveryCandidateEvaluation.Rejected(RecoveryCandidateRejection.FIXED_CELLS, failure))
        assertFalse(rejected.parsed)
        state.considerFailure(failure.copy(reason = null, manualConflicts = emptyList()))
        assertEquals(RecordedRecoveryCandidate.NO_PAYLOAD,
            state.recordEvaluation(RecoveryCandidateEvaluation.UnresolvedPriorityAssignment))
        assertSame(failure, state.failure)
        assertTrue(state.candidates.results().isEmpty())
        val diagnostic = state.searchDiagnostic()
        assertEquals(setOf(RecoveryCandidateRejection.FIXED_CELLS), diagnostic.candidateRejections)
        assertTrue(diagnostic.unresolvedCalculations)
        assertFalse(diagnostic.acceptedCandidate)
    }

    @Test fun decoderFailureRetainsStructuralEvidenceWithoutClaimingAParsedPayload() {
        val state = run()
        val structural = candidate().copy(text = null)
        val recorded = state.recordEvaluation(RecoveryCandidateEvaluation.Accepted(structural, decoderFailed = true))
        assertFalse(recorded.parsed)
        assertSame(structural, state.candidates.results().single())
        val diagnostic = state.searchDiagnostic()
        assertTrue(diagnostic.acceptedCandidate)
        assertEquals(setOf(SearchIncompleteReason.INTERNAL_ERROR), diagnostic.incompleteReasons)
        assertEquals(SearchCompleteness.UNSEARCHED, diagnostic.completeness)
    }
}
