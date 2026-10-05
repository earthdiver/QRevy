package io.github.earthdiver.qrrepair

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GsDiagnosticPresentationTest {
    private fun inBothLanguages(check: (Boolean) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val previous = AppLanguageManager.current
        try {
            for (language in AppLanguage.entries) {
                AppLanguageManager.applyTo(app, language)
                check(language == AppLanguage.JAPANESE)
            }
        } finally { AppLanguageManager.applyTo(app, previous) }
    }

    private val localSearch = GsSearchSummary(trials = 1, interpolations = 1,
        elapsedMillis = 1_000, mismatchLimits = setOf(11))
    private val wholeSearch = RecoverySearchDiagnostic(
        gs = GsSearchSummary.combine(listOf(localSearch, GsSearchSummary(
            trials = 2, elapsedMillis = 2_000, mismatchLimits = setOf(11),
            skipReasons = setOf(GsTrialSkip.UNIQUE_NEARBY_WORD)))),
        completedSteps = 1, solverCompleteness = SearchCompleteness.COMPLETE,
    )

    private fun candidate(gs: Boolean = true) = RecoveryCandidate(
        version = 1, ecLevel = ErrorCorrectionLevel.L, dataMask = 0,
        text = "GS RECOVERY", dataBytes = byteArrayOf(), formatMatches = 30, formatMismatches = 0,
        originalErasedCodewords = 0, erasedCodewords = 0, correctedErrataSymbols = 4,
        knownBitMismatchesAfterCorrection = 4, knownBitsCompared = 208,
        correctedInterleaved = intArrayOf(), extraction = CodewordExtraction(emptyList()),
        gsMismatchLimit = if (gs) 11 else null,
        actualBlockMethods = listOf(RecoveryBlockMethod(0, false, if (gs) null else 1, 0,
            gs = if (gs) GsDerivation(4, 5, 6, 0, 4, -1) else null)),
        searchDiagnostic = if (gs) wholeSearch.copy(gs = localSearch) else null,
    )

    private fun context(diagnostic: RecoverySearchDiagnostic, candidates: Int = 1) = RecoveryDiagnosticContext(
        candidateCount = candidates, unsearched = diagnostic.completeness == SearchCompleteness.UNSEARCHED,
        candidatesOmitted = false, searchDiagnostic = diagnostic, selectedEntry = null,
    )

    @Test fun candidateSummaryKeepsMethodsAndWholeSearchCountsSeparateFromCalculationDetails() = inBothLanguages { ja ->
        val report = RecoveryDiagnosticPresenter(ja).candidateReport(candidate(), null, context(wholeSearch))
        val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertTrue(processing.summary.contains(if (ja) "この候補を得た方法" else "Methods used to obtain this candidate"))
        assertTrue(processing.summary.contains(if (ja) "ブロック1: Guruswami–Sudan（GS）法によるリスト復号" else "Block 1: Guruswami–Sudan (GS) list decoding"))
        assertTrue(processing.summary.contains(if (ja) "3試行、3秒" else "3 trials, 3s"))
        val limit = if (ja) "既知ビットの不一致上限:" else "Known-bit mismatch limit"
        assertEquals(1, processing.summary.split(limit).size - 1)
        assertTrue(processing.summary.contains(if (ja) "11ビット" else "11 bits"))
        for (detail in if (ja) listOf("補間", "訂正半径", "残存消失", "多重度", "Y次数")
            else listOf("Interpolations", "radius", "erasures", "multiplicity", "Y degree")) {
            assertFalse(processing.summary.contains(detail))
            assertTrue(processing.details.contains(detail))
        }
        assertTrue(processing.details.contains(if (ja) "補間: 1回" else "Interpolations: 1"))
        assertTrue(processing.details.contains(if (ja) "一部試行の補間を省略" else "Interpolation skipped for some trials"))
        assertFalse(report.fullText().contains(if (ja) "最後のGS試行" else "Last GS trial"))
        assertFalse(report.fullText().contains(if (ja) "時間予算" else "time budget"))
    }

    @Test fun failureKeepsSkippedCalculationsInDetailsAndReportsTimeoutInVerification() = inBothLanguages { ja ->
        val diagnostic = wholeSearch.copy(incompleteReasons = setOf(SearchIncompleteReason.GS_TIME_LIMIT))
        val failure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 30, 0, 30,
            RecoveryFailureStage.PAYLOAD_VALIDATION, searchDiagnostic = diagnostic.copy(gs = localSearch))
        val report = RecoveryDiagnosticPresenter(ja).failureReport(failure, null, context(diagnostic, 0))
        val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
        val verification = report.sections.single { it.stage == DiagnosticStage.VERIFICATION }
        assertTrue(processing.summary.contains(if (ja) "3試行、3秒" else "3 trials, 3s"))
        assertTrue(processing.summary.contains(if (ja) "11ビット" else "11 bits"))
        assertFalse(processing.summary.contains(if (ja) "補間" else "Interpolation"))
        assertTrue(processing.details.contains(if (ja) "一部試行の補間を省略" else "Interpolation skipped for some trials"))
        assertFalse(processing.summary.contains(if (ja) "時間切れ" else "time limit"))
        assertTrue(verification.summary.contains(if (ja) "GS法による追加探索は時間切れで終了しました" else "GS fallback ended at the time limit"))
        assertTrue(verification.summary.contains(if (ja) "未探索の組合せ" else "combinations remain unchecked"))
        assertFalse(report.fullText().contains(if (ja) "累計5分" else "five-minute"))
    }

    @Test fun wholeSearchGsWorkDoesNotClaimGsWasUsedForAConventionalCandidate() = inBothLanguages { ja ->
        val presenter = RecoveryDiagnosticPresenter(ja)
        val report = presenter.candidateReport(candidate(gs = false), null, context(wholeSearch))
        val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertTrue(processing.summary.contains(if (ja) "ブロック1: 通常RS" else "Block 1: Conventional RS"))
        assertFalse(processing.summary.contains("Guruswami–Sudan"))
        assertTrue(processing.summary.contains(if (ja) "GS法による追加探索（探索全体）" else "GS fallback (whole search)"))
        assertTrue(processing.summary.contains(if (ja) "11ビット" else "11 bits"))
        assertFalse(processing.details.contains(if (ja) "多重度" else "multiplicity"))
        val withoutGs = presenter.candidateReport(candidate(gs = false), null, context(wholeSearch.copy(gs = null)))
            .sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertFalse(withoutGs.summary.contains("GS"))
        assertFalse(withoutGs.details.contains("GS"))
    }
}
