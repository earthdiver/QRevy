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
class ListDecodingDiagnosticPresentationTest {
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

    private val localSearch = ListDecodingSummary(trials = 1, interpolations = 1,
        elapsedMillis = 1_000, mismatchLimits = setOf(11), methodTrials = mapOf(ListDecoderMethod.GURUSWAMI_SUDAN to 1))
    private val wholeSearch = RecoverySearchDiagnostic(
        listDecoding = ListDecodingSummary.combine(listOf(localSearch, ListDecodingSummary(
            trials = 2, elapsedMillis = 2_000, mismatchLimits = setOf(11), methodTrials = mapOf(ListDecoderMethod.WU to 1),
            skipReasons = setOf(ListDecodingTrialSkip.UNIQUE_NEARBY_WORD)))),
        completedSteps = 1, solverCompleteness = SearchCompleteness.COMPLETE,
    )

    private fun candidate(listDecoding: Boolean = true, method: ListDecoderMethod = ListDecoderMethod.GURUSWAMI_SUDAN) = RecoveryCandidate(
        version = 1, ecLevel = ErrorCorrectionLevel.L, dataMask = 0,
        text = "GS RECOVERY", dataBytes = byteArrayOf(), formatMatches = 30, formatMismatches = 0,
        originalErasedCodewords = 0, erasedCodewords = 0, correctedErrataSymbols = 4,
        knownBitMismatchesAfterCorrection = 4, knownBitsCompared = 208,
        correctedInterleaved = intArrayOf(), extraction = CodewordExtraction(emptyList()),
        listDecodingMismatchLimit = if (listDecoding) 11 else null,
        actualBlockMethods = listOf(RecoveryBlockMethod(0, false, if (listDecoding) null else 1, 0,
            listDecoding = if (listDecoding) ListDecodingDerivation(4, 5, 6, 0, 4, -1, method,
                score = if (method == ListDecoderMethod.KOETTER_VARDY) 30 else null,
                scoreThreshold = if (method == ListDecoderMethod.KOETTER_VARDY) 28 else null) else null)),
        searchDiagnostic = if (listDecoding) wholeSearch.copy(listDecoding = localSearch) else null,
    )

    private fun context(diagnostic: RecoverySearchDiagnostic, candidates: Int = 1) = RecoveryDiagnosticContext(
        candidateCount = candidates, unsearched = diagnostic.completeness == SearchCompleteness.UNSEARCHED,
        candidatesOmitted = false, searchDiagnostic = diagnostic, selectedEntry = null,
    )

    @Test fun skippedStagesRemainVisibleWithoutAnyDecoderTrials() = inBothLanguages { ja ->
        for (reason in listOf(ListDecodingStatus.ORIGINAL_RS_SUCCEEDED, ListDecodingStatus.ZERO_ERROR_LIMIT,
            ListDecodingStatus.INPUT_CONSTRAINT)) {
            val diagnostic = wholeSearch.copy(listDecoding = null, listDecodingExecution = ListDecodingExecution(
                ListDecodingStage.entries.associateWith { setOf(reason) }))
            val presenter = RecoveryDiagnosticPresenter(ja)
            val failure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 30, 0, 30,
                RecoveryFailureStage.PAYLOAD_VALIDATION, searchDiagnostic = diagnostic)
            val reports = listOf(presenter.candidateReport(candidate(false), null, context(diagnostic)),
                presenter.failureReport(failure, null, context(diagnostic, 0)))
            for (report in reports) {
                val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
                assertTrue(processing.summary.contains(if (ja) "仮置きなしのリスト復号" else "List decoding without tentative assignments"))
                assertTrue(processing.summary.contains(if (ja) "仮置きも使う追加リスト復号" else "Additional list decoding including tentative assignments"))
                assertTrue(processing.summary.contains(diagnostic.listDecodingExecution!!.description()))
                assertFalse(processing.summary.contains(if (ja) "試行回数" else "List-decoding trials"))
            }
        }
    }

    @Test fun combinedExecutionKeepsEveryStageDecisionAndTrialIneligibilityReason() = inBothLanguages { ja ->
        val parts = listOf(
            RecoverySearchDiagnostic(listDecodingExecution = ListDecodingExecution(mapOf(
                ListDecodingStage.WITHOUT_ASSIGNMENTS to setOf(ListDecodingStatus.DEFERRED),
                ListDecodingStage.ADDITIONAL to setOf(ListDecodingStatus.TIME_LIMIT)))),
            RecoverySearchDiagnostic(listDecodingExecution = ListDecodingExecution(mapOf(
                ListDecodingStage.WITHOUT_ASSIGNMENTS to setOf(ListDecodingStatus.COMPLETED),
                ListDecodingStage.ADDITIONAL to setOf(ListDecodingStatus.COMPLETED))),
                listDecoding = ListDecodingSummary(skipReasons = setOf(ListDecodingTrialSkip.TOO_MANY_ERASURES,
                    ListDecodingTrialSkip.KV_PARAMETERS_UNAVAILABLE))))
        val combined = RecoverySearchDiagnostic.combine(parts)!!
        assertEquals(setOf(ListDecodingStatus.DEFERRED, ListDecodingStatus.COMPLETED),
            combined.listDecodingExecution!!.stages[ListDecodingStage.WITHOUT_ASSIGNMENTS])
        assertEquals(setOf(ListDecodingStatus.TIME_LIMIT, ListDecodingStatus.COMPLETED),
            combined.listDecodingExecution.stages[ListDecodingStage.ADDITIONAL])
        val processing = RecoveryDiagnosticPresenter(ja).candidateReport(candidate(false), null, context(combined))
            .sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertTrue(processing.summary.contains(if (ja) "複数の読み取り試行" else "across sampled inputs"))
        assertTrue(processing.details.contains(requireNotNull(combined.listDecoding).calculationDetails()))
    }

    @Test fun candidateSummaryKeepsMethodsAndWholeSearchCountsSeparateFromCalculationDetails() = inBothLanguages { ja ->
        val report = RecoveryDiagnosticPresenter(ja).candidateReport(candidate(), null, context(wholeSearch))
        val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertTrue(processing.summary.contains(if (ja) "この候補を得た方法" else "Methods used to obtain this candidate"))
        assertTrue(processing.summary.contains(if (ja) "ブロック1: Guruswami–Sudan（GS）法によるリスト復号" else "Block 1: Guruswami–Sudan (GS) list decoding"))
        assertTrue(processing.summary.contains(if (ja) "3回、所要時間: 3秒" else "3, elapsed time: 3s"))
        val limit = if (ja) "既知ビットの不一致上限:" else "Known-bit mismatch limit"
        assertFalse(processing.summary.contains(limit))
        assertTrue(processing.details.contains(limit))
        assertFalse(processing.summary.contains(if (ja) "11ビット" else "11 bits"))
        assertTrue(processing.details.contains(if (ja) "11ビット" else "11 bits"))
        for (detail in if (ja) listOf("訂正半径", "残存消失", "多重度", "Y次数")
            else listOf("radius", "erasures", "multiplicity", "Y degree")) {
            assertFalse(processing.summary.contains(detail))
            assertTrue(processing.details.contains(detail))
        }
        assertFalse(report.fullText().contains(if (ja) "補間:" else "Interpolations:"))
        assertTrue(processing.details.contains(if (ja) "一部試行の復号計算を省略" else "Decoding skipped for some trials"))
        assertFalse(report.fullText().contains(if (ja) "最後のGS試行" else "Last GS trial"))
        assertFalse(report.fullText().contains(if (ja) "時間予算" else "time budget"))
    }

    @Test fun reusedResultsAreAggregatedAndDisplayedSeparatelyFromNewTrials() = inBothLanguages { ja ->
        val summary = ListDecodingSummary.combine(listOf(
            ListDecodingSummary(trials = 1, methodTrials = mapOf(ListDecoderMethod.WU to 1), reusedTrials = 1),
            ListDecodingSummary(reusedTrials = 2)))!!
        assertEquals(1, summary.trials)
        assertEquals(3, summary.reusedTrials)
        val diagnostic = wholeSearch.copy(listDecoding = summary)
        val processing = RecoveryDiagnosticPresenter(ja).candidateReport(candidate(method = ListDecoderMethod.WU),
            null, context(diagnostic)).sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertTrue(processing.summary.contains(if (ja) "試行回数（探索全体）: 1回" else "trials (whole search): 1"))
        assertTrue(processing.details.contains(if (ja) "復号結果を再利用: 3回" else "results reused: 3"))
        assertTrue(processing.details.contains(if (ja) "新規の試行回数には含めません" else "excluded from new trial counts"))
    }

    @Test fun failureKeepsSkippedCalculationsInDetailsAndReportsTimeoutInVerification() = inBothLanguages { ja ->
        val diagnostic = wholeSearch.copy(incompleteReasons = setOf(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT))
        val failure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 30, 0, 30,
            RecoveryFailureStage.PAYLOAD_VALIDATION, searchDiagnostic = diagnostic.copy(listDecoding = localSearch))
        val report = RecoveryDiagnosticPresenter(ja).failureReport(failure, null, context(diagnostic, 0))
        val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
        val verification = report.sections.single { it.stage == DiagnosticStage.VERIFICATION }
        assertTrue(processing.summary.contains(if (ja) "3回、所要時間: 3秒" else "3, elapsed time: 3s"))
        assertFalse(processing.summary.contains(if (ja) "11ビット" else "11 bits"))
        assertTrue(processing.details.contains(if (ja) "11ビット" else "11 bits"))
        assertFalse(processing.summary.contains(if (ja) "補間" else "Interpolation"))
        assertTrue(processing.details.contains(if (ja) "一部試行の復号計算を省略" else "Decoding skipped for some trials"))
        assertFalse(processing.summary.contains(if (ja) "時間切れ" else "time limit"))
        assertTrue(verification.summary.contains(if (ja) "リスト復号による追加探索は時間切れで終了しました" else "List-decoding fallback ended at the time limit"))
        assertTrue(verification.summary.contains(if (ja) "未探索の組合せ" else "combinations remain unchecked"))
        assertFalse(report.fullText().contains(if (ja) "累計5分" else "five-minute"))
    }

    @Test fun wholeSearchListDecodingDoesNotClaimItWasUsedForAConventionalCandidate() = inBothLanguages { ja ->
        val presenter = RecoveryDiagnosticPresenter(ja)
        val report = presenter.candidateReport(candidate(listDecoding = false), null, context(wholeSearch))
        val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertTrue(processing.summary.contains(if (ja) "ブロック1: 通常RS" else "Block 1: Conventional RS"))
        assertFalse(processing.summary.contains("Guruswami–Sudan"))
        assertTrue(processing.summary.contains(if (ja) "リスト復号の試行回数（探索全体）" else "List-decoding trials (whole search)"))
        assertFalse(processing.summary.contains(if (ja) "11ビット" else "11 bits"))
        assertTrue(processing.details.contains(if (ja) "11ビット" else "11 bits"))
        assertFalse(processing.details.contains(if (ja) "多重度" else "multiplicity"))
        val withoutListDecoding = presenter.candidateReport(candidate(listDecoding = false), null, context(wholeSearch.copy(listDecoding = null)))
            .sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertFalse(withoutListDecoding.summary.contains("GS"))
        assertFalse(withoutListDecoding.details.contains("GS"))
    }

    @Test fun successfulSingleTrialShowsSubsecondTimeAndKeepsNormalStopInDetails() = inBothLanguages { ja ->
        val diagnostic = wholeSearch.copy(listDecoding = localSearch.copy(elapsedMillis = 999, interpolations = 0))
        val selected = candidate(method = ListDecoderMethod.WU).copy(
            combinationSearch = CombinationSearchSummary(stops = setOf(CombinationSearchStop.SUCCESS_WINDOW)))
        val report = RecoveryDiagnosticPresenter(ja).candidateReport(selected, null, context(diagnostic))
        val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertTrue(processing.summary.contains(if (ja) "1回、所要時間: 1秒未満" else "1, elapsed time: less than 1s"))
        val stop = if (ja) "以降の組合せの確認" else "checking further combinations"
        assertFalse(processing.summary.contains(stop))
        assertTrue(processing.details.contains(stop))
        assertTrue(processing.details.contains(if (ja) "QR候補の組合せ探索の終了理由" else "Stop reasons for QR candidate combination search"))
        assertFalse(report.fullText().contains(if (ja) "補間:" else "Interpolations:"))
    }

    @Test fun failureShowsStructureFillingBeforeListDecodingTotals() = inBothLanguages { ja ->
        val diagnostic = wholeSearch.copy(singleSegmentStatuses = setOf(SingleSegmentStatus.APPLIED,
            SingleSegmentStatus.UNSUPPORTED_MODE))
        val failure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 30, 0, 30,
            RecoveryFailureStage.PAYLOAD_VALIDATION, searchDiagnostic = diagnostic,
            displayConditions = RecoveryDisplayConditions(true, true, null, null, null))
        val processing = RecoveryDiagnosticPresenter(ja).failureReport(failure, null, context(diagnostic, 0))
            .sections.single { it.stage == DiagnosticStage.PROCESSING }
        val structure = processing.summary.indexOf(SingleSegmentStatus.APPLIED.trialDescription())
        val totals = processing.summary.indexOf(requireNotNull(diagnostic.listDecoding).description())
        assertTrue(structure >= 0)
        assertTrue(totals > structure)
    }

    @Test fun reportsTheSelectedDecoderAndOnlyItsApplicableCalculationParameters() = inBothLanguages { ja ->
        for (method in ListDecoderMethod.entries) {
            val report = RecoveryDiagnosticPresenter(ja).candidateReport(candidate(method = method), null, context(wholeSearch))
            val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
            assertTrue(processing.summary.contains(method.displayName()))
            assertTrue(processing.details.contains(if (ja) "Wu法によるブロック復号: 1試行" else "Block decoding with Wu: 1 trials"))
            assertTrue(processing.details.contains(if (ja) "Guruswami–Sudan（GS）法によるブロック復号: 1試行" else "Block decoding with Guruswami–Sudan (GS): 1 trials"))
            assertFalse(processing.summary.contains(if (ja) "一致スコア" else "agreement score"))
            if (method == ListDecoderMethod.KOETTER_VARDY) {
                assertTrue(processing.details.contains(if (ja) "一致スコア: 30／必要スコア: 28" else "agreement score: 30 / required score: 28"))
                assertFalse(processing.details.contains(if (ja) "訂正半径" else "radius:"))
            }
            if (method == ListDecoderMethod.WU) {
                assertTrue(processing.details.contains(if (ja) "訂正半径: 4" else "radius: 4"))
                assertFalse(processing.details.contains(if (ja) "多重度" else "multiplicity"))
            }
        }
    }
}
