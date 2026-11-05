package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class KnownBitSearchContinuationTest {
    @Test fun knownBitSearchRecoversTheSourceAlongsideOtherImageErasureCandidates() {
        val qr = Encoder.encode("ab0000cdefghijk", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 0)).matrix
        val matrix = QrMatrix(qr.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) {
                set(x, y, if (qr[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
            }
        }
        val words = CodewordExtractor.extract(matrix, 1, 0).codewords
        val expected = words.map { it.value }.toIntArray()
        for (raw in listOf(5, 10, 14, 24, 25)) for (cell in words[raw].coordinates) {
            matrix.set(cell.x, cell.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        for ((raw, bit) in listOf(1 to 4, 21 to 1)) {
            val cell = words[raw].coordinates[bit]
            val flipped = if (matrix[cell.x, cell.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
            matrix.set(cell.x, cell.y, flipped, 1f, ModuleSource.IMAGE)
        }
        // The low-margin words are correct; both actual errors have strong image readings.
        val weakMargins = mapOf(9 to 1, 18 to 2, 22 to 3, 15 to 4, 13 to 5)
        for ((raw, word) in words.withIndex()) for (cell in word.coordinates) {
            if (matrix[cell.x, cell.y].value != ModuleValue.ERASED) {
                matrix.setImageMargin(cell.x, cell.y, weakMargins[raw] ?: 90)
            }
        }
        fun recover(limit: Int) = RecoveryEngine().recoverWithDiagnostics(matrix.copy(),
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0),
            maxKnownDataEccMismatches = 21, listDecodingAdditionalErrors = 0, knownBitErrorLimit = limit)

        val withoutKnownSearch = recover(0)
        val earlier = withoutKnownSearch.candidates.filter { it.isSelectableRecovery }
        assertTrue(earlier.isNotEmpty())
        assertFalse(earlier.any { it.correctedInterleaved.contentEquals(expected) })
        assertTrue(earlier.any { candidate -> candidate.actualBlockMethods.any { it.imageErasures.isNotEmpty() } })
        assertFalse(withoutKnownSearch.searchDiagnostic.knownBitSearchAttempted)

        val outcome = recover(2)
        assertTrue(outcome.searchDiagnostic.knownBitSearchAttempted)
        assertTrue(outcome.searchDiagnostic.knownBitTrials > 0)
        val recovered = outcome.candidates.single { it.correctedInterleaved.contentEquals(expected) }
        assertTrue(recovered.isSelectableRecovery)
        assertTrue(recovered.actualBlockMethods.any { it.knownBitErrors?.size == 2 })
        assertEquals(2, recovered.knownBitMismatchesAfterCorrection)
        assertTrue(earlier.all { previous -> outcome.candidates.any {
            it.correctedInterleaved.contentEquals(previous.correctedInterleaved)
        } })
        assertTrue(outcome.searchDiagnostic.incompleteReasons.isEmpty())
    }

    @Test fun earlierDecodedCandidatesDoNotSuppressEnabledKnownBitSearch() {
        val earlier = decoded("A")
        val additional = decoded("B")
        for (phase in listOf(RecoveryPhase.IMAGE_ERASURES, RecoveryPhase.EARLY_LIST,
            RecoveryPhase.BIT_CONSTRAINTS, RecoveryPhase.PARTIAL_RS)) {
            val run = state(2)
            val found = RecoveryPhaseRunner(run, 2, RecoveryCancellation.NONE, { false }, searchPhase = {
                if (run.phase == phase) run.accept(earlier)
                if (run.phase == RecoveryPhase.KNOWN_BIT_ERRORS) {
                    run.knownBitErrorBudget.measure { run.knownBitErrorBudget.trial() }
                    run.accept(additional)
                }
            }, finish = { it }).execute()

            assertTrue("Known-bit search after $phase", run.knownBitSearchAttempted)
            assertEquals(1L, run.searchDiagnostic().knownBitTrials)
            assertEquals(setOf(earlier.correctedInterleaved.toList(), additional.correctedInterleaved.toList()),
                found.map { it.correctedInterleaved.toList() }.toSet())
            assertTrue(run.incompleteReasons.isEmpty())
        }
    }

    @Test fun disabledKnownBitSearchKeepsEarlierCandidatesWithoutUsingItsBudget() {
        val earlier = decoded("A")
        val run = state(0)
        val found = RecoveryPhaseRunner(run, 2, RecoveryCancellation.NONE, { false }, searchPhase = {
            if (run.phase == RecoveryPhase.IMAGE_ERASURES) run.accept(earlier)
            if (run.phase == RecoveryPhase.KNOWN_BIT_ERRORS) fail("Disabled known-bit search must not run")
        }, finish = { it }).execute()

        assertSame(earlier, found.single())
        assertFalse(run.knownBitSearchAttempted)
        assertEquals(0L, run.searchDiagnostic().knownBitTrials)
        assertTrue(run.incompleteReasons.isEmpty())
    }

    @Test fun knownBitBudgetStopsPreserveEarlierCandidatesAndReportIncompleteSearch() {
        val earlier = decoded("A")
        val limits = listOf(
            KnownBitErrorBudget(maxTrials = 0) to SearchIncompleteReason.KNOWN_BIT_SEARCH_LIMIT,
            KnownBitErrorBudget(maxMillis = 0) to SearchIncompleteReason.KNOWN_BIT_SEARCH_TIME_LIMIT,
        )
        for ((budget, reason) in limits) {
            val run = state(2, budget)
            var additionalListsVisited = false
            val found = RecoveryPhaseRunner(run, 2, RecoveryCancellation.NONE, { false }, searchPhase = {
                when (run.phase) {
                    RecoveryPhase.IMAGE_ERASURES -> run.accept(earlier)
                    RecoveryPhase.KNOWN_BIT_ERRORS -> budget.measure { budget.trial() }
                    RecoveryPhase.ADDITIONAL_LIST -> additionalListsVisited = true
                    else -> Unit
                }
            }, finish = { it }).execute()

            assertSame(earlier, found.single())
            assertTrue(run.knownBitSearchAttempted)
            assertTrue(additionalListsVisited)
            assertEquals(setOf(reason), run.searchDiagnostic().incompleteReasons)
            assertTrue(run.searchDiagnostic().unsearched)
        }
    }

    private fun decoded(text: String): RecoveryCandidate =
        recoverPayload(PayloadBits().apply { byteSegment(text) }.finish()).single()

    private fun state(limit: Int, budget: KnownBitErrorBudget = KnownBitErrorBudget()): RecoveryRunState {
        val matrix = QrMatrix(21).apply {
            set(10, 10, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            setImageMargin(10, 10, 1)
        }
        return RecoveryRunState(matrix, RecoveryRetentionBudget(), RecoveryCancellation.NONE,
            PartialBitSearchLimit.DEFAULT, ListDecodingBudget(clockMillis = { 0L }),
            knownBitErrorLimit = limit, knownBitErrorBudget = budget)
    }

    private fun RecoveryRunState.accept(candidate: RecoveryCandidate) {
        recordEvaluation(RecoveryCandidateEvaluation.Accepted(candidate, decoderFailed = false))
    }
}
