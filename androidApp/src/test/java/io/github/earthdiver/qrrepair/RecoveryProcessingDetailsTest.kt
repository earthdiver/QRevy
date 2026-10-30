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
class RecoveryProcessingDetailsTest {
    private fun candidate(bitConstraints: Boolean = false) = RecoveryCandidate(
        1, ErrorCorrectionLevel.M, 2, "SYNTHETIC", byteArrayOf(), 30, 0, 5, 3, 3, 1, 100,
        intArrayOf(), CodewordExtraction(emptyList()),
        actualBlockMethods = listOf(RecoveryBlockMethod(0, bitConstraints, if (bitConstraints) null else 2, 2)),
    )

    private val wholeSearch = RecoverySearchDiagnostic(
        completedSteps = 1, acceptedCandidate = true,
        listDecoding = ListDecodingSummary(trials = 3, elapsedMillis = 900, reusedTrials = 2,
            methodTrials = mapOf(ListDecoderMethod.GURUSWAMI_SUDAN to 3)),
    )

    private fun context(diagnostic: RecoverySearchDiagnostic? = wholeSearch, entry: RecoveryResultEntry? = null) =
        RecoveryDiagnosticContext(1, false, false, diagnostic, entry, 1)

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

    @Test fun candidateMethodsAndBlockValuesStaySeparateFromWholeSearchTrials() = inBothLanguages { ja ->
        val report = RecoveryDiagnosticPresenter(ja).candidateReport(candidate(), null, context())
        val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
        assertEquals(listOf(DiagnosticDetailId.METHODS, DiagnosticDetailId.CALCULATIONS, DiagnosticDetailId.SEARCH), processing.groups.map { it.id })
        val (methods, calculations, search) = processing.groups
        assertTrue(methods.text.contains(if (ja) "通常RSによる確認・補完・訂正" else "Conventional RS was used"))
        assertFalse(methods.text.contains("Guruswami"))
        assertFalse(calculations.text.contains("Guruswami"))
        assertTrue(calculations.text.contains(if (ja) "ブロック1\n方法: 通常RS" else "Block 1\nMethod: Conventional RS"))
        assertTrue(calculations.collapsible)
        assertFalse(methods.collapsible)
        assertTrue(search.collapsible)
        assertEquals(if (ja) "リスト復号の実行状況（探索全体）" else "List decoding execution status (whole search)", search.title)
        assertTrue(search.summary.contains(if (ja) "選択中の候補に使わなかった" else "did not produce the selected candidate"))
        assertTrue(search.summary.contains(requireNotNull(wholeSearch.listDecoding).description()))
        assertTrue(search.text.contains(if (ja) "再利用: 2回" else "reused: 2"))
        assertTrue(search.text.contains("Guruswami"))
        assertTrue(report.fullText().contains(calculations.text))
        assertTrue(report.fullText().indexOf(calculations.text) < report.fullText().indexOf(search.text))
    }

    @Test fun knownBitExampleKeepsItsMethodAcrossSummariesDetailsAndCalculationRecords() {
        val directory = java.io.File("../docs/examples/priority-list-decoding")
        val source = java.io.File(directory, "before.txt").readText()
        val expected = QrTextMatrixParser.parse(java.io.File(directory, "after.txt").readText()).matrix
        val words = CodewordExtractor.extract(expected, 1, 0).codewords.map { it.value }.toIntArray()
        for (keepPriority in listOf(true, false)) {
            val matrix = QrTextMatrixParser.parse(if (keepPriority) source else source.replace('*', '?')).matrix
            val outcome = RecoveryEngine().recoverWithDiagnostics(matrix, knownBitErrorLimit = 2,
                maxFormatMismatches = 6, maxKnownDataEccMismatches = 2, listDecodingBudget = ListDecodingBudget(0))
            val selected = outcome.candidates.single { it.correctedInterleaved.contentEquals(words) }
            assertEquals("QRevy repaired.", selected.text)
            assertEquals(0, selected.constraintFilledBits)
            assertTrue(selected.priorityAssignments.isEmpty())
            assertFalse(requireNotNull(selected.conventionalRsDiagnostic).succeeded)
            val method = selected.actualBlockMethods.single()
            assertEquals(2, requireNotNull(method.knownBitErrors).size)
            assertNull(method.listDecoding)
            assertNull(method.rsMargin)
            val derivation = selected.selectedBlockDerivations().getValue(0)
            assertTrue(derivation.knownBitSearch)
            assertEquals(method.knownBitErrors, derivation.knownBitErrors)
            inBothLanguages { ja ->
                val processing = RecoveryDiagnosticPresenter(ja).candidateReport(selected, null, context(null)).sections[2]
                val methods = processing.groups.single { it.id == DiagnosticDetailId.METHODS }
                val calculations = processing.groups.single { it.id == DiagnosticDetailId.CALCULATIONS }
                for (text in listOf(selected.resultExplanationText(), selected.usedMethodsText(), processing.summary,
                    methods.text, calculations.summary, calculations.text)) {
                    assertTrue(text, text.contains(if (ja) "既知bit誤り探索" else "known-bit error search", ignoreCase = true))
                    assertFalse(text, text.contains(if (ja) "通常RS" else "conventional RS", ignoreCase = true))
                }
                assertTrue(calculations.text.contains(if (ja) "2bit変更" else "2 changed bits"))
            }
        }
    }

    @Test fun inferredAndTentativeValuesAreNotPresentedAsUnchangedOriginalInput() = inBothLanguages { ja ->
        val selected = candidate(bitConstraints = true).copy(
            constraintFilledBits = 24, constraintEvidenceBits = 8, constraintForcedByUserSetting = true,
            priorityAssignments = mapOf(Coord(10, 10) to ModuleValue.ZERO),
            singleSegmentStatus = SingleSegmentStatus.APPLIED,
            displayConditions = RecoveryDisplayConditions(true, true, null, null, null),
        )
        val processing = RecoveryDiagnosticPresenter(ja).candidateReport(selected, null, context(null)).sections[2]
        val methods = processing.groups.single { it.id == DiagnosticDetailId.METHODS }.text
        assertTrue(methods.contains(if (ja) "不明なセルの白黒を、QRの誤り訂正の条件に合うように" else "values for unknown cells that satisfy the QR error-correction conditions"))
        assertTrue(methods.contains(if (ja) "QRの構造から補完・変更した値" else "Values filled or changed using QR structure"))
        assertTrue(methods.contains(if (ja) "優先指定した不明セルに仮置きした白黒" else "Tentative values assigned to prioritized unknown cells"))
        assertTrue(methods.contains(if (ja) "最終的な不一致数とは異なります" else "not the final mismatch count"))
        assertTrue(methods.contains(if (ja) "この候補に適用" else "applied to this candidate"))
        val amount = if (ja) "構造補完（24bit）" else "structure filling (24 bits)"
        assertEquals(1, processing.details.split(amount).size - 1)
        assertFalse(processing.details.contains(if (ja) "入力済みの0/1を保つ" else "preserving the entered values"))
        assertFalse(processing.details.contains(if (ja) "通常RSの計算値" else "Conventional RS calculation"))
        assertTrue(processing.groups.single { it.id == DiagnosticDetailId.CALCULATIONS }.text.contains(
            if (ja) "このブロックの自由度: 2" else "Degrees of freedom for this block: 2"))
    }

    @Test fun degreesOfFreedomDoNotImplyPriorStructureFillingOrTentativeAssignments() = inBothLanguages { ja ->
        for (structure in listOf(false, true)) for (priority in listOf(false, true)) {
            val selected = candidate(bitConstraints = true).copy(partialGuessedBits = 2,
                constraintFilledBits = if (structure) 8 else 0,
                priorityAssignments = if (priority) mapOf(Coord(10, 10) to ModuleValue.ZERO) else emptyMap())
            val methods = selected.processingMethodsText()
            assertEquals(structure, methods.contains(if (ja) "QRの構造から補完・変更した値" else "Values filled or changed using QR structure"))
            assertEquals(priority, methods.contains(if (ja) "仮置きした白黒も、候補を求める条件" else "Tentative values assigned to prioritized unknown cells"))
            assertFalse(methods.contains(if (ja) "この計算で決まっている" else "this calculation stage"))
            val calculations = RecoveryDiagnosticPresenter(ja).candidateReport(selected, null, context(null)).sections[2]
                .groups.single { it.id == DiagnosticDetailId.CALCULATIONS }.text
            assertTrue(calculations.contains(if (ja) "このブロックの自由度: 2" else "Degrees of freedom for this block: 2"))
            assertTrue(calculations.contains(if (ja) "独立に選べる不明bitの数" else "unknown bits that can be chosen independently"))
        }
    }

    @Test fun recordsFromOneInputAreNotPresentedAsTotalsForEverySampledInput() = inBothLanguages { ja ->
        val selected = candidate().copy(searchDiagnostic = wholeSearch)
        val failure = RecoveryFailure(ErrorCorrectionLevel.M, 2, 30, 0, 30, RecoveryFailureStage.REED_SOLOMON,
            searchDiagnostic = wholeSearch.copy(acceptedCandidate = false))
        val presenter = RecoveryDiagnosticPresenter(ja)
        val reports = listOf(presenter.candidateReport(selected, null, context(null)),
            presenter.failureReport(failure, null, context(null).copy(candidateCount = 0)))
        for ((index, report) in reports.withIndex()) {
            val search = report.sections[2].groups.single { it.id == DiagnosticDetailId.SEARCH }
            val expectedTitle = if (index == 0) {
                if (ja) "リスト復号の実行状況（選択候補の入力）" else "List decoding execution status (selected candidate's input)"
            } else {
                if (ja) "リスト復号の実行状況（参考診断の入力）" else "List decoding execution status (reference diagnostic input)"
            }
            assertEquals(expectedTitle, search.title)
            assertTrue(search.summary.contains(if (ja) "探索全体の集計は記録されていません" else "Totals across all sampled inputs were not recorded"))
            assertTrue(search.summary.contains(if (ja) "試行回数（対象の入力）: 3回" else "trials (identified input): 3"))
            assertTrue(search.text.contains(if (ja) "再利用: 2回" else "reused: 2"))
            assertFalse(search.fullText().contains(if (ja) "（探索全体）" else "(whole search)"))
            assertTrue(report.sections.last().summary.contains(SearchCompleteness.UNKNOWN.description()))
        }
    }

    @Test fun alternativeBlockRoutesQrRoutesAndSampledInputsKeepTheirOwnCountsAndEvidence() = inBothLanguages { ja ->
        val blockRoute = PartialCodewordSolver.Derivation(listOf(1), 2, 0, emptyList(), 0, 0, true)
        val other = candidate(true)
        val selected = candidate().copy(alternativeDerivations = listOf(other),
            blockDerivations = mapOf(0 to listOf(blockRoute, blockRoute.copy(guessedBits = 3))), derivationsOmitted = true)
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
        val hypothesis = GeometryHypothesis(emptyList(), geometry, 0.0)
        val stats = MatrixSampler.SamplingStats(128.0, 255.0, 32.0)
        val attempt = GeometryAttempt(hypothesis, QrMatrix(21), stats, listOf(selected), null)
        val entry = RecoveryResultEntry(attempt, selected, null,
            listOf(RecoveryResultDerivation(hypothesis, stats, null, other)))
        val processing = RecoveryDiagnosticPresenter(ja).candidateReport(selected, geometry, context(entry = entry)).sections[2]
        val alternative = processing.groups.single { it.id == DiagnosticDetailId.CALCULATIONS }
        assertTrue(alternative.collapsible)
        assertTrue(alternative.summary.contains(if (ja) "2通りの計算で同じQRが得られました。" else "2 calculation methods yielded the same QR."))
        assertTrue(alternative.summary.contains(if (ja) "別の読み取り試行: 1件" else "Other sampled-input attempts: 1"))
        assertTrue(alternative.summary.contains(if (ja) "保持上限" else "retention limit"))
        assertTrue(alternative.text.contains(if (ja) "件数は修復候補の件数ではありません" else "counts are not candidate counts"))
        assertTrue(alternative.text.contains(if (ja) "ブロック1・同じ値を得た別の計算: 2通り" else "Block 1: 2 other calculations"))
        assertTrue(alternative.text.contains(if (ja) "別の読み取り試行1" else "Other sampled-input attempt 1"))
        assertEquals(listOf(DiagnosticDetailId.METHODS, DiagnosticDetailId.CALCULATIONS, DiagnosticDetailId.SEARCH), processing.groups.map { it.id })
        assertFalse(processing.groups.last().text.contains(if (ja) "方法A" else "Method A"))
    }

    @Test fun referenceFailureRecordsDoNotBecomeWholeSearchConclusions() = inBothLanguages { ja ->
        val failure = RecoveryFailure(ErrorCorrectionLevel.M, 2, 28, 2, 30, RecoveryFailureStage.REED_SOLOMON,
            failedBlockIndex = 0, rsBlockCount = 2, ecSymbolsPerBlock = 10, failedBlockErasures = 12)
        val diagnostic = wholeSearch.copy(acceptedCandidate = false, coverageComplete = false)
        val report = RecoveryDiagnosticPresenter(ja).failureReport(failure,
            GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0), context(diagnostic).copy(candidateCount = 0, selectedCandidateNumber = null))
        val groups = report.sections[2].groups
        assertTrue(report.sections[2].summary.contains(if (ja) "調べた範囲では修復候補は見つかりませんでした" else "No recovery candidate was found in the checked range"))
        assertTrue(report.sections[2].summary.contains(SearchCompleteness.UNKNOWN.description()))
        assertFalse(report.sections[2].summary.contains(if (ja) "参考" else "reference"))
        val reference = groups.single { it.id == DiagnosticDetailId.CALCULATIONS }
        assertTrue(reference.collapsible)
        assertTrue(reference.summary.contains("V1 / ECC M /"))
        assertTrue(reference.summary.contains(if (ja) "探索全体の失敗原因を示すものではありません" else "not a diagnosis of why the whole search failed"))
        assertTrue(reference.text.contains(if (ja) "この参考計算で最初に結果を得られなかったRSブロック: 1" else "First RS block without a result in this reference calculation: 1"))
        assertFalse(report.overview.contains(if (ja) "RSブロック" else "RS block"))
        assertFalse(groups.single { it.id == DiagnosticDetailId.SEARCH }.summary.contains(if (ja) "選択中" else "selected candidate"))
    }

    @Test fun noCandidateProcessingExplainsCompletionBeforeAnyReferenceCalculation() = inBothLanguages { ja ->
        val failure = RecoveryFailure(ErrorCorrectionLevel.M, 2, 30, 0, 30, RecoveryFailureStage.REED_SOLOMON,
            reason = RecoveryFailureReason.BOUNDED_BIT_SEARCH_LIMIT,
            failedBlockIndex = 0, rsBlockCount = 1, ecSymbolsPerBlock = 10, failedBlockErasures = 24,
            originalErasedCodewords = 24, erasedCodewords = 24)
        val complete = RecoverySearchDiagnostic(completedSteps = 1, unresolvedCalculations = true,
            solverCompleteness = SearchCompleteness.COMPLETE)
        val interrupted = complete.copy(incompleteReasons = setOf(SearchIncompleteReason.USER_INTERRUPTED))
        val unknown = complete.copy(coverageComplete = false)
        for (diagnostic in listOf(complete, interrupted, unknown)) {
            val report = RecoveryDiagnosticPresenter(ja).failureReport(failure, null,
                context(diagnostic).copy(candidateCount = 0, selectedCandidateNumber = null))
            val processing = report.sections[2]
            assertTrue(processing.summary.contains(if (ja) "調べた範囲では修復候補は見つかりませんでした"
                else "No recovery candidate was found in the checked range"))
            assertTrue(processing.summary.contains(diagnostic.completeness.description()))
            assertFalse(processing.summary.contains(if (ja) "参考" else "reference"))
            assertFalse(processing.summary.contains(if (ja) "消失" else "erasure"))
            val view = RecoveryDiagnosticView(RuntimeEnvironment.getApplication())
            view.showReport(report, resetExpanded = true)
            assertTrue(diagnosticText(view).contains(processing.summary))
            diagnosticButton(view, DiagnosticStage.PROCESSING).performClick()
            assertFalse(diagnosticText(view).contains(if (ja) "失敗ブロックのECCシンボル数" else "ECC symbols in the failing block"))
            diagnosticCalculationButton(view, processing.groups.single { it.id == DiagnosticDetailId.CALCULATIONS }.title).performClick()
            assertTrue(diagnosticText(view).contains(if (ja) "失敗ブロックのECCシンボル数: 10" else "ECC symbols in the failing block: 10"))
        }
    }

    @Test fun missingRunRecordsAndUncheckedWorkCannotBecomeCompletedSearches() = inBothLanguages { ja ->
        val local = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE)
        val failure = RecoveryFailure(ErrorCorrectionLevel.M, 2, 30, 0, 30, RecoveryFailureStage.PAYLOAD_VALIDATION,
            searchDiagnostic = local)
        val presenter = RecoveryDiagnosticPresenter(ja)
        val missing = presenter.failureReport(failure, null, context(null).copy(candidateCount = 0)).sections[2]
        assertTrue(missing.summary.contains(SearchCompleteness.UNKNOWN.description()))
        assertFalse(missing.summary.contains(SearchCompleteness.COMPLETE.description()))
        val unsearched = presenter.failureReport(failure, null,
            context(local).copy(candidateCount = 0, unsearched = true)).sections[2]
        assertTrue(unsearched.summary.contains(SearchCompleteness.UNSEARCHED.description()))
        assertFalse(unsearched.summary.contains(SearchCompleteness.COMPLETE.description()))
    }

    @Test fun initialPartialBitAnalysisIsExplainedOnceWithoutDroppingItsConditions() = inBothLanguages { ja ->
        val analysis = RsBitDiagnostic(16, 12, 4, 0, emptyList())
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "", analysis, 16, 2)
        assertEquals(1, section.details.split(analysis.inputAnalysisDescription()).size - 1)
        assertTrue(section.details.contains(if (ja) "任意のセルをその数だけ確認すればよい" else "not a count of arbitrary cells to check"))
    }
}
