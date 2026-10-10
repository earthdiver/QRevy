package io.github.earthdiver.qrrepair

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryDiagnosticOverviewTest {
    private fun candidate(): RecoveryCandidate {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        return RecoveryEngine().recover(matrix).first { it.text == "HELLO" }
    }

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

    @Test fun overviewSeparatesStructureDecodingInputComparisonAndSearchLimits() {
        val selected = candidate().copy(text = null, formatMatches = 26, formatMismatches = 2,
            knownBitsCompared = 192, knownBitMismatchesAfterCorrection = 3)
        inBothLanguages { ja ->
            val diagnostic = RecoverySearchDiagnostic(incompleteReasons = setOf(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT))
            val report = RecoveryDiagnosticPresenter(ja).candidateReport(selected, null,
                RecoveryDiagnosticContext(3, true, true, diagnostic, null, 2))
            assertEquals(if (ja) "候補2の診断（保持中3件）" else "Diagnostics for candidate 2 (3 retained)", report.scope)
            val text = report.overview
            assertTrue(text.contains(if (ja) "QR構造: 確認済み" else "QR structure: verified"))
            assertTrue(text.contains(if (ja) "最終復号: 成功していません" else "Final decoding: not successful"))
            assertTrue(text.contains(if (ja) "形式情報: 不一致2bit／比較対象28bit" else "Format information: 2 mismatches / 28 bits compared"))
            assertTrue(text.contains(if (ja) "データ・ECC: 不一致3bit／比較対象192bit" else "Data/ECC: 3 mismatches / 192 bits compared"))
            assertTrue(text.contains(if (ja) "テキストとしての復号には成功していません" else "Decoding as text has not succeeded"))
            assertFalse(text.contains(if (ja) "文字として表示できない" else "cannot be displayed as text"))
            assertTrue(text.contains(if (ja) "未探索の組合せ" else "combinations remain unchecked"))
            assertTrue(text.contains(if (ja) "時間切れ" else "time limit"))
            assertTrue(text.contains(if (ja) "候補の省略: あり" else "Candidates omitted: yes"))
            assertTrue(text.contains(if (ja) "同一であることを保証するものではありません" else "does not establish"))
            val summary = report.sections.last().summary
            for (evidence in listOf(
                if (ja) "QR構造: 確認済み" else "QR structure: verified",
                if (ja) "最終復号: 成功していません" else "Final decoding: not successful",
                if (ja) "形式情報: 不一致2bit／比較対象28bit" else "Format information: 2 mismatches / 28 bits compared",
                if (ja) "データ・ECC: 不一致3bit／比較対象192bit" else "Data/ECC: 3 mismatches / 192 bits compared",
                if (ja) "時間切れ" else "time limit",
                if (ja) "候補の省略: あり" else "Candidates omitted: yes",
            )) assertTrue(evidence, summary.contains(evidence))
        }
    }

    @Test fun candidateCountsNeverDetermineSearchCompletion() {
        val selected = candidate()
        val failure = RecoveryFailure(ErrorCorrectionLevel.M, selected.dataMask, 30, 0, 30, RecoveryFailureStage.REED_SOLOMON)
        inBothLanguages { ja ->
            for (count in listOf(0, 1, 3)) for (completeness in SearchCompleteness.entries) {
                val diagnostic = RecoverySearchDiagnostic(completedSteps = 1, solverCompleteness = completeness,
                    coverageComplete = completeness != SearchCompleteness.UNKNOWN,
                    incompleteReasons = if (completeness == SearchCompleteness.UNSEARCHED)
                        setOf(SearchIncompleteReason.USER_INTERRUPTED) else emptySet())
                val context = RecoveryDiagnosticContext(count, completeness == SearchCompleteness.UNSEARCHED, false, diagnostic,
                    null, if (count > 0) 1 else null)
                val presenter = RecoveryDiagnosticPresenter(ja)
                val report = if (count == 0) presenter.failureReport(failure, null, context)
                    else presenter.candidateReport(selected, null, context)
                assertTrue("$count/$completeness", report.overview.contains(completeness.description()))
                assertTrue("$count/$completeness", report.sections.last().summary.contains(completeness.description()))
                assertTrue(report.sections.last().summary.contains(if (ja) "候補の省略: なし" else "Candidates omitted: no"))
                if (count == 0) {
                    assertTrue(report.overview.contains(if (ja) "調べた範囲では" else "in the checked range"))
                    assertFalse(report.overview.contains(if (ja) "QR構造: 確認済み" else "QR structure: verified"))
                } else assertTrue(report.overview.contains(if (ja) "最終復号: 成功" else "Final decoding: succeeded"))
            }
        }
    }

    @Test fun retentionOmissionsAndPolicyLimitsDoNotBecomeUnfinishedSearch() {
        val selected = candidate().copy(combinationSearch = CombinationSearchSummary(
            stops = setOf(CombinationSearchStop.COMBINATION_LIMIT)))
        inBothLanguages { ja ->
            val diagnostic = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE,
                candidatesOmitted = true, completedSteps = 1)
            val report = RecoveryDiagnosticPresenter(ja).candidateReport(selected, null,
                RecoveryDiagnosticContext(1, false, true, diagnostic, null, 1))
            assertTrue(report.overview.contains(SearchCompleteness.COMPLETE.description()))
            assertFalse(report.overview.contains(SearchCompleteness.UNSEARCHED.description()))
            assertTrue(report.overview.contains(if (ja) "候補の省略: あり" else "Candidates omitted: yes"))
            assertTrue(report.sections[2].details.contains(selected.combinationSearch.stopReasonsText()))
        }
    }

    @Test fun missingRecordsAndNoComparedBitsRemainExplicit() {
        val selected = candidate().copy(displayConditions = null, conventionalRsDiagnostic = null,
            knownBitsCompared = 0, knownBitMismatchesAfterCorrection = 0, formatMatches = 0, formatMismatches = 0)
        inBothLanguages { ja ->
            val report = RecoveryDiagnosticPresenter(ja).candidateReport(selected, null,
                RecoveryDiagnosticContext(1, false, false, null, null, 1))
            assertTrue(report.overview.contains(SearchCompleteness.UNKNOWN.description()))
            assertTrue(report.overview.contains(if (ja) "形式情報: 比較対象0bit（一致は未確認）" else "Format information: 0 known bits to compare (agreement unknown)"))
            assertTrue(report.overview.contains(if (ja) "データ・ECC: 比較対象0bit（一致は未確認）" else "Data/ECC: 0 known bits to compare (agreement unknown)"))
            assertTrue(report.sections.last().summary.contains(if (ja) "比較対象0bit（一致は未確認）" else "0 known bits to compare (agreement unknown)"))
            assertTrue(report.sections.last().summary.contains(SearchCompleteness.UNKNOWN.description()))
            assertTrue(report.sections[0].summary.contains(if (ja) "記録されていません" else "not recorded"))
            assertTrue(report.sections[1].summary.contains(if (ja) "記録なし" else "Not recorded"))
            assertFalse(report.sections[1].summary.contains(if (ja) "未実行" else "not run"))
        }
    }

    @Test fun anInternalErrorBeforeAnyWorkDoesNotClaimACompletedAttempt() = inBothLanguages { ja ->
        val diagnostic = RecoverySearchDiagnostic(incompleteReasons = setOf(SearchIncompleteReason.INTERNAL_ERROR))
        val failure = RecoveryFailure(ErrorCorrectionLevel.M, 0, 0, 0, 0, RecoveryFailureStage.EXTRACTION)
        val report = RecoveryDiagnosticPresenter(ja).failureReport(failure, null,
            RecoveryDiagnosticContext(0, true, false, diagnostic, null))
        assertTrue(report.overview.contains(if (ja) "修復処理を実行できませんでした" else "prevented repair from running"))
        assertFalse(report.fullText().contains(if (ja) "探索しました" else "were searched"))
        assertFalse(report.fullText().contains(if (ja) "探索を行いました" else "Searched under"))
        assertTrue(report.sections[2].summary.contains(if (ja) "記録はありません" else "not recorded"))
        assertTrue(report.sections[2].details.isBlank())
        assertTrue(report.sections.last().summary.contains(if (ja) "修復処理を実行できませんでした" else "prevented repair from running"))
    }

    @Test fun failureSummaryLabelsTheReferenceInputAndDistinguishesMissingComparisonFromZeroKnownBits() = inBothLanguages { ja ->
        val failure = RecoveryFailure(ErrorCorrectionLevel.Q, 5, 24, 3, 27, RecoveryFailureStage.PAYLOAD_VALIDATION)
        val geometry = GridGeometry(2, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
        val context = RecoveryDiagnosticContext(0, false, false,
            RecoverySearchDiagnostic(completedSteps = 1, solverCompleteness = SearchCompleteness.COMPLETE), null)
        val presenter = RecoveryDiagnosticPresenter(ja)
        val missing = presenter.failureReport(failure, geometry, context).sections.last().summary
        assertTrue(missing.contains(if (ja) "保持している修復候補: 0件" else "Retained recovery candidates: 0"))
        assertTrue(missing.contains("V2 / ECC Q /"))
        assertTrue(missing.contains(if (ja) "マスク5" else "mask 5"))
        assertTrue(missing.contains(if (ja) "形式情報: 不一致3bit／比較対象27bit" else "Format information: 3 mismatches / 27 bits compared"))
        assertTrue(missing.contains(if (ja) "データ・ECCの比較結果: 記録なし" else "Data/ECC comparison: not recorded"))
        assertFalse(missing.contains(if (ja) "QR構造: 確認済み" else "QR structure: verified"))
        for (known in listOf(0, 88)) {
            val summary = presenter.failureReport(failure.copy(knownBitsCompared = known,
                knownBitMismatches = if (known == 0) 0 else 7), geometry, context).sections.last().summary
            assertTrue(summary.contains(if (ja) "採用できなかった候補の比較結果" else "Comparison for the rejected candidate"))
            assertTrue(summary.contains(if (known == 0) {
                if (ja) "比較対象0bit（一致は未確認）" else "0 known bits to compare (agreement unknown)"
            } else if (ja) "データ・ECC: 不一致7bit／比較対象88bit" else "Data/ECC: 7 mismatches / 88 bits compared"))
            assertFalse(summary.contains(if (ja) "比較結果: 記録なし" else "comparison: not recorded"))
        }
    }

    @Test fun collapsedVerificationUpdatesWithTheCandidateAndKeepsSearchEvidenceConsistent() {
        val selected = candidate()
        inBothLanguages { ja ->
            val diagnostic = RecoverySearchDiagnostic(completedSteps = 1,
                solverCompleteness = SearchCompleteness.COMPLETE, candidatesOmitted = true)
            val context = RecoveryDiagnosticContext(2, true, false, diagnostic, null, 1)
            val presenter = RecoveryDiagnosticPresenter(ja)
            val first = presenter.candidateReport(selected.copy(knownBitsCompared = 100,
                knownBitMismatchesAfterCorrection = 9), null, context)
            val second = presenter.candidateReport(selected.copy(knownBitsCompared = 200,
                knownBitMismatchesAfterCorrection = 2), null, context.copy(selectedCandidateNumber = 2))
            val view = RecoveryDiagnosticView(RuntimeEnvironment.getApplication())
            view.showReport(first, resetExpanded = true)
            val toggle = diagnosticButton(view, DiagnosticStage.VERIFICATION)
            assertEquals(view.context.getString(R.string.diagnostic_collapsed), androidx.core.view.ViewCompat.getStateDescription(toggle))
            assertTrue(diagnosticText(view).contains(first.sections.last().summary))
            view.showReport(second, resetExpanded = false)
            assertTrue(diagnosticText(view).contains(second.sections.last().summary))
            assertFalse(diagnosticText(view).contains(if (ja) "不一致9bit／比較対象100bit" else "9 mismatches / 100 bits compared"))
            val verification = second.sections.last()
            for (text in listOf(verification.summary, verification.details)) {
                assertTrue(text.contains(SearchCompleteness.UNSEARCHED.description()))
                assertTrue(text.contains(if (ja) "終了理由を確認できません" else "stop reason is unavailable"))
                assertTrue(text.contains(if (ja) "保持上限" else "retention limit"))
            }
            assertFalse(diagnosticText(view).contains(verification.details))
        }
    }
}
