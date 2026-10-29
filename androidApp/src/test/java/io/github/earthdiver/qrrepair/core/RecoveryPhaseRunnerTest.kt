package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class RecoveryPhaseRunnerTest {
    private val allPhases = listOf(RecoveryPhase.ORIGINAL_RS, RecoveryPhase.EARLY_LIST,
        RecoveryPhase.BIT_CONSTRAINTS, RecoveryPhase.PARTIAL_RS, RecoveryPhase.ADDITIONAL_LIST)

    private fun candidate(): RecoveryCandidate = recoverPayload(PayloadBits().apply {
        put(4, 4); put(1, 8); put('A'.code, 8)
    }.finish()).single()

    private fun state(
        budget: ListDecodingBudget = ListDecodingBudget(clockMillis = { 0L }),
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        retention: RecoveryRetentionBudget = RecoveryRetentionBudget(),
    ) = RecoveryRunState(QrMatrix(21), retention, cancellation, PartialBitSearchLimit.DEFAULT, budget)

    private fun RecoveryRunState.accept(candidate: RecoveryCandidate) =
        recordEvaluation(RecoveryCandidateEvaluation.Accepted(candidate, decoderFailed = false))

    @Test fun onlyAParsedCandidateMatchingOriginalRsCanSuppressLaterPhases() {
        val parsed = candidate()
        val original = requireNotNull(parsed.conventionalRsDiagnostic)
        assertTrue(original.succeeded)
        val different = parsed.correctedInterleaved.copyOf().apply { this[0] = this[0] xor 1 }
        val scenarios = listOf(
            parsed to true,
            parsed.copy(text = null) to false,
            parsed.copy(conventionalRsDiagnostic = original.copy(correctedInterleaved = different)) to false,
            parsed.copy(conventionalRsDiagnostic = null) to false,
        )
        for ((retained, stopsEarly) in scenarios) {
            val run = state()
            val visited = mutableListOf<RecoveryPhase>()
            val found = RecoveryPhaseRunner(run, null, RecoveryCancellation.NONE, { false }, searchPhase = {
                visited += run.phase
                if (run.phase == RecoveryPhase.ORIGINAL_RS) run.accept(retained)
            }, finish = { it }).execute()
            assertSame(retained, found.single())
            assertEquals(if (stopsEarly) listOf(RecoveryPhase.ORIGINAL_RS) else allPhases, visited)
            assertTrue(run.finished)
            assertEquals(1, run.completedSteps)
            assertTrue(run.listDecodingStatuses.values.all {
                it == if (stopsEarly) ListDecodingStatus.ORIGINAL_RS_SUCCEEDED else ListDecodingStatus.INELIGIBLE
            })
        }
    }

    @Test fun zeroErrorLimitStillRunsExactConstraintsAfterOriginalRsFails() {
        val run = state()
        val parsed = candidate()
        val visited = mutableListOf<RecoveryPhase>()
        val found = RecoveryPhaseRunner(run, 0, RecoveryCancellation.NONE, { false }, searchPhase = {
            visited += run.phase
            if (run.phase == RecoveryPhase.BIT_CONSTRAINTS) run.accept(parsed)
        }, finish = { it }).execute()
        assertSame(parsed, found.single())
        assertEquals(listOf(RecoveryPhase.ORIGINAL_RS, RecoveryPhase.BIT_CONSTRAINTS), visited)
        assertTrue(run.listDecodingStatuses.values.all { it == ListDecodingStatus.ZERO_ERROR_LIMIT })
        assertNull(run.listDecodingSummary)
    }

    @Test fun omittedOriginalCandidatesDoNotSuppressTheRemainingSearch() {
        val run = state(retention = RecoveryRetentionBudget(0))
        val parsed = candidate()
        val visited = mutableListOf<RecoveryPhase>()
        val found = RecoveryPhaseRunner(run, null, RecoveryCancellation.NONE, { false }, searchPhase = {
            visited += run.phase
            if (run.phase == RecoveryPhase.ORIGINAL_RS) run.accept(parsed)
        }, finish = { it }).execute()
        assertTrue(found.isEmpty())
        assertEquals(allPhases, visited)
        assertTrue(run.searchDiagnostic().candidatesOmitted)
        assertTrue(run.searchDiagnostic().decodedCandidate)
    }

    @Test fun deferredListsShareTheirCumulativeBudgetAndDoNotChargeInterveningExactSearch() {
        var clock = 0L
        val budget = ListDecodingBudget(1_500, clockMillis = { clock })
        val run = state(budget)
        val parsed = candidate()
        val visited = mutableListOf<RecoveryPhase>()
        val found = RecoveryPhaseRunner(run, 3, RecoveryCancellation.NONE, { false }, searchPhase = {
            visited += run.phase
            when (run.phase) {
                RecoveryPhase.ORIGINAL_RS, RecoveryPhase.KNOWN_BIT_ERRORS -> Unit
                RecoveryPhase.EARLY_LIST, RecoveryPhase.ADDITIONAL_LIST -> {
                    val summary = requireNotNull(run.listDecodingSummary)
                    run.listDecodingSummary = summary.copy(trials = summary.trials + 1)
                    clock += if (run.phase == RecoveryPhase.EARLY_LIST) 1_100 else 500
                    run.cancellation.check()
                }
                RecoveryPhase.BIT_CONSTRAINTS -> {
                    assertEquals(1_100L, budget.elapsedMillis)
                    clock += 5_000
                    run.accept(parsed)
                }
                RecoveryPhase.PARTIAL_RS -> assertEquals(1_100L, budget.elapsedMillis)
            }
        }, finish = { it }).execute()
        assertSame(parsed, found.single())
        assertEquals(allPhases, visited)
        assertEquals(1_600L, budget.elapsedMillis)
        assertEquals(1_600L, run.listDecodingSummary!!.elapsedMillis)
        assertEquals(setOf(3), run.listDecodingSummary!!.mismatchLimits)
        assertEquals(setOf(ListDecodingLimit.DEFAULT), run.listDecodingSummary!!.extensionLimits)
        assertEquals(mapOf(ListDecodingStage.WITHOUT_ASSIGNMENTS to 1, ListDecodingStage.ADDITIONAL to 1),
            run.listDecodingNewTrials)
        assertEquals(ListDecodingStatus.DEFERRED, run.listDecodingStatuses[ListDecodingStage.WITHOUT_ASSIGNMENTS])
        assertEquals(ListDecodingStatus.TIME_LIMIT, run.listDecodingStatuses[ListDecodingStage.ADDITIONAL])
        assertEquals(setOf(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT), run.incompleteReasons)
    }

    @Test fun userStopRetainsCandidatesButSupersedingCancellationEscapes() {
        val parsed = candidate()
        for (superseded in listOf(false, true)) {
            var interrupted = false
            val cancellation = RecoveryCancellation { superseded && interrupted }
            val run = state(cancellation = RecoveryCancellation { interrupted })
            val runner = RecoveryPhaseRunner(run, null, cancellation, { interrupted }, searchPhase = {
                if (run.phase == RecoveryPhase.BIT_CONSTRAINTS) {
                    run.accept(parsed)
                    interrupted = true
                    run.cancellation.check()
                }
            }, finish = { error("Interrupted work must retain partial results without final pruning") })
            if (superseded) {
                assertThrows(RecoveryCancelledException::class.java) { runner.execute() }
                assertFalse(SearchIncompleteReason.USER_INTERRUPTED in run.incompleteReasons)
            } else {
                assertSame(parsed, runner.execute().single())
                assertEquals(setOf(SearchIncompleteReason.USER_INTERRUPTED), run.incompleteReasons)
                assertEquals(setOf(CombinationSearchStop.USER_INTERRUPTED), run.combinationSearch.stops)
                assertEquals(ListDecodingStatus.INELIGIBLE, run.listDecodingStatuses[ListDecodingStage.WITHOUT_ASSIGNMENTS])
                assertEquals(ListDecodingStatus.INTERRUPTED, run.listDecodingStatuses[ListDecodingStage.ADDITIONAL])
            }
            assertFalse(run.finished)
            assertEquals(0, run.completedSteps)
        }
    }

    @Test fun aLaterInternalErrorRetainsCandidatesAndEarlierListStatus() {
        val run = state()
        val parsed = candidate()
        val found = RecoveryPhaseRunner(run, null, RecoveryCancellation.NONE, { false }, searchPhase = {
            when (run.phase) {
                RecoveryPhase.BIT_CONSTRAINTS -> run.accept(parsed)
                RecoveryPhase.ADDITIONAL_LIST -> error("Synthetic phase failure")
                else -> Unit
            }
        }, finish = { error("Failed work must retain partial results without final pruning") }).execute()
        assertSame(parsed, found.single())
        assertEquals(setOf(SearchIncompleteReason.INTERNAL_ERROR), run.incompleteReasons)
        assertEquals(ListDecodingStatus.INELIGIBLE, run.listDecodingStatuses[ListDecodingStage.WITHOUT_ASSIGNMENTS])
        assertEquals(ListDecodingStatus.INTERNAL_ERROR, run.listDecodingStatuses[ListDecodingStage.ADDITIONAL])
        assertFalse(run.finished)
    }
}
