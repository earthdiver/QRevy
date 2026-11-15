package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class RecoveryFormatProgressTest {
    private val first = ErrorCorrectionLevel.Q to 6
    private val second = ErrorCorrectionLevel.M to 0

    @Test fun completedCandidateSupersedesOnlyTheSameFormatsEarlierFailure() {
        val progress = RecoveryFormatProgress()
        for (key in listOf(first, second)) progress.finish(RecoveryFormatTrial(key, false).apply {
            unresolved = "ERASURE_CAPACITY_EXCEEDED"; completed = true
        })
        progress.finish(RecoveryFormatTrial(first, false).apply { candidate = true; completed = true })
        val states = progress.snapshot(linkedSetOf(first, second))
        assertEquals(listOf(RecoveryFormatState.CANDIDATE, RecoveryFormatState.UNRESOLVED), states.map { it.state })
        assertTrue(states.first().hasCandidate)
    }

    @Test fun proofOfExclusionResolvesAnEarlierFailureButNotAnUnvisitedFormat() {
        val progress = RecoveryFormatProgress()
        progress.finish(RecoveryFormatTrial(first, false).apply { unresolved = "DECODER_NO_RESULT"; completed = true })
        progress.finish(RecoveryFormatTrial(first, false).apply { exclude("NO_EXACT_SOLUTION") })
        val states = progress.snapshot(linkedSetOf(first, second))
        assertEquals(RecoveryFormatState.EXCLUDED, states.first().state)
        assertEquals(RecoveryFormatState.UNRESOLVED, states.last().state)
        assertEquals("NOT_ATTEMPTED", states.last().reason)
    }

    @Test fun candidateDoesNotResolveAnInterruptedPassOrAnotherInputVariant() {
        for (interrupted in listOf(false, true)) {
            val progress = RecoveryFormatProgress()
            progress.finish(RecoveryFormatTrial(first, false).apply {
                candidate = true
                completed = !interrupted
                if (!interrupted) unresolved = "BIT_SPACE_LIMIT"
            })
            val state = progress.snapshot(setOf(first)).single()
            assertEquals(RecoveryFormatState.UNRESOLVED, state.state)
            assertTrue(state.hasCandidate)
        }
    }

    @Test fun resolvingOnePriorityAssignmentCannotEraseAnotherUnresolvedAssignment() {
        val progress = RecoveryFormatProgress()
        progress.beginPriorityPass(setOf(first))
        progress.finish(RecoveryFormatTrial(first, true).apply { unresolved = "BIT_SPACE_LIMIT"; completed = true })
        progress.finishPriorityAssignment()
        progress.finish(RecoveryFormatTrial(first, true).apply { candidate = true; completed = true })
        progress.finishPriorityAssignment()
        progress.finishPriorityPass(completed = true)
        progress.finish(RecoveryFormatTrial(first, false).apply { candidate = true; completed = true })
        val state = progress.snapshot(setOf(first)).single()
        assertEquals(RecoveryFormatState.UNRESOLVED, state.state)
        assertTrue(state.hasCandidate)
    }

    @Test fun completedPriorityPassSupersedesEarlierFailuresOnlyAfterEveryAssignmentIsResolved() {
        val progress = RecoveryFormatProgress()
        progress.finish(RecoveryFormatTrial(first, false).apply { unresolved = "HEADER_UNKNOWN"; completed = true })
        progress.beginPriorityPass(setOf(first))
        progress.finish(RecoveryFormatTrial(first, true).apply { exclude("HEADER_CONFLICT") })
        progress.finishPriorityAssignment()
        progress.finish(RecoveryFormatTrial(first, true).apply { unresolved = "NO_CONSISTENT_ASSIGNMENT"; completed = true })
        progress.finishPriorityAssignment()
        progress.finishPriorityPass(completed = true)
        assertEquals(RecoveryFormatState.UNRESOLVED, progress.snapshot(setOf(first)).single().state)

        progress.beginPriorityPass(setOf(first))
        progress.finish(RecoveryFormatTrial(first, true).apply { exclude("HEADER_CONFLICT") })
        progress.finishPriorityAssignment()
        progress.finish(RecoveryFormatTrial(first, true).apply { candidate = true; completed = true })
        progress.finishPriorityAssignment()
        assertEquals(RecoveryFormatState.UNRESOLVED, progress.snapshot(setOf(first)).single().state)
        progress.finishPriorityPass(completed = true)
        assertEquals(RecoveryFormatState.CANDIDATE, progress.snapshot(setOf(first)).single().state)
    }

    @Test fun interruptedPriorityPassRetainsEarlierUncertaintyAndAnyCandidate() {
        val progress = RecoveryFormatProgress()
        progress.beginPriorityPass(setOf(first))
        progress.finish(RecoveryFormatTrial(first, true).apply { candidate = true; completed = true })
        // The worker stops before completing the assignment or enumerating the other value.
        progress.finishPriorityPass(completed = false)
        val state = progress.snapshot(setOf(first)).single()
        assertEquals(RecoveryFormatState.UNRESOLVED, state.state)
        assertTrue(state.hasCandidate)
    }

    @Test fun skippingAFormatInOneAssignmentKeepsThatFormatUnresolved() {
        val progress = RecoveryFormatProgress()
        progress.beginPriorityPass(setOf(first, second))
        for (key in listOf(first, second)) progress.finish(RecoveryFormatTrial(key, true).apply { exclude("HEADER_CONFLICT") })
        progress.finishPriorityAssignment()
        progress.finish(RecoveryFormatTrial(first, true).apply { candidate = true; completed = true })
        progress.finishPriorityAssignment()
        progress.finishPriorityPass(completed = true)
        assertEquals(listOf(RecoveryFormatState.CANDIDATE, RecoveryFormatState.UNRESOLVED),
            progress.snapshot(linkedSetOf(first, second)).map { it.state })
    }

    @Test fun excludingEveryPriorityAssignmentResolvesAnUnknownHeader() {
        val progress = RecoveryFormatProgress()
        progress.finish(RecoveryFormatTrial(first, false).apply { unresolved = "HEADER_UNKNOWN"; completed = true })
        progress.beginPriorityPass(setOf(first))
        repeat(2) {
            progress.finish(RecoveryFormatTrial(first, true).apply { exclude("HEADER_CONFLICT") })
            progress.finishPriorityAssignment()
        }
        progress.finishPriorityPass(completed = true)
        // A later unassigned fallback must not undo the completed enumeration.
        progress.finish(RecoveryFormatTrial(first, false).apply { unresolved = "HEADER_UNKNOWN"; completed = true })
        assertEquals(RecoveryFormatState.EXCLUDED, progress.snapshot(setOf(first)).single().state)
    }

    @Test fun limitsRemainIncompleteAfterTheFormatIsResolved() {
        for (reason in SearchIncompleteReason.entries) {
            val run = RecoveryRunState(QrMatrix(25), RecoveryRetentionBudget(), RecoveryCancellation.NONE, 0,
                ListDecodingBudget())
            run.expectedFormats = setOf(first)
            run.incompleteReasons += reason
            run.formatProgress.beginPriorityPass(setOf(first))
            repeat(2) {
                run.formatProgress.finish(RecoveryFormatTrial(first, true).apply { candidate = true; completed = true })
                run.formatProgress.finishPriorityAssignment()
            }
            run.formatProgress.finishPriorityPass(completed = true)
            val diagnostic = run.searchDiagnostic()
            assertFalse(diagnostic.unresolvedCalculations)
            assertTrue(diagnostic.unsearched)
            assertEquals(setOf(reason), diagnostic.incompleteReasons)
        }
    }

    @Test fun combiningDifferentInputsNeverClearsTheirUnresolvedOutcomes() {
        val unresolved = RecoverySearchDiagnostic(unresolvedCalculations = true,
            formatOutcomes = listOf(RecoveryFormatOutcome(first.first, first.second,
                RecoveryFormatState.UNRESOLVED, reason = "BIT_SPACE_LIMIT")))
        val solved = RecoverySearchDiagnostic(formatOutcomes = listOf(RecoveryFormatOutcome(first.first, first.second,
            RecoveryFormatState.CANDIDATE, hasCandidate = true, reason = "VALIDATED_CANDIDATE")))
        val combined = RecoverySearchDiagnostic.combine(listOf(unresolved, solved))!!
        assertTrue(combined.unresolvedCalculations)
        assertEquals(2, combined.formatOutcomes.size)
    }
}
