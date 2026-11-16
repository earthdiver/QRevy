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
    @Test fun selectedTrialAndCumulativeCandidatesHaveSeparateConditionsCountsAndCompletion() {
        val selected = candidate().let { it.copy(displayConditions = it.displayConditions!!.copy(
            assumeUtf8ByteSegments = true, trustSingleSegment = true, partialBitSearchLimit = 0, exactSpaceBitLimit = 8)) }
        val geometry = GridGeometry(selected.version, FinderRole.TOP_LEFT, 30.0, 30.0, 5.0, 0.0)
        val complete = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE, completedSteps = 1)
        val attempt = GeometryAttempt(GeometryHypothesis(emptyList(), geometry, 0.0), QrMatrix(geometry.dimension),
            MatrixSampler.SamplingStats(128.0, 64.0, 32.0), listOf(selected), searchDiagnostic = complete)
        val entry = RecoveryResultEntry(attempt, selected, null)
        val cumulative = complete.copy(unresolvedCalculations = true,
            incompleteReasons = setOf(SearchIncompleteReason.TIME_LIMIT),
            formatOutcomes = listOf(RecoveryFormatOutcome(ErrorCorrectionLevel.Q, 6,
                RecoveryFormatState.UNRESOLVED, false, "BIT_SPACE_LIMIT")),
            candidateRejections = setOf(RecoveryCandidateRejection.UTF8_BYTE_SEGMENT))
        inBothLanguages { ja ->
            val context = RecoveryDiagnosticContext(3, false, false, cumulative, entry, 3, RecoveryDiscoveryCount.Exact(3))
            val presenter = RecoveryDiagnosticPresenter(ja)
            val report = presenter.candidateReport(selected, geometry, context)
            val summary = presenter.searchResultDescription(context)
            val trial = summary
            assertTrue(trial.contains(if (ja) "この試行の保持候補: 1件" else "Candidates retained from this trial: 1"))
            assertTrue(trial.contains(SearchCompleteness.COMPLETE.description()))
            assertTrue(summary.contains(if (ja) "全条件・格子の結果一覧" else "Results across all conditions and grids"))
            assertTrue(summary.contains(if (ja) "見つかった候補: 3件" else "Candidates found: 3"))
            assertFalse(summary.contains(if (ja) "指定条件を満たす修復候補が3件" else "3 recovery candidates meeting the specified conditions"))
            for (text in listOf(report.overview, summary)) {
                assertFalse(text, text.contains(if (ja) "試行の時間上限" else "trial reached its time limit"))
                assertFalse(text, text.contains(if (ja) "不明ビットの組合せ" else "Unknown-bit combinations"))
                assertTrue(text, text.contains(SearchCompleteness.COMPLETE.description()))
                assertTrue(text, text.contains(if (ja) "UTF-8と単一セグメントを仮定" else "assuming UTF-8 and a single segment"))
            }
            val records = report.sections[2].groups.single { it.id == DiagnosticDetailId.TRIALS }
            assertTrue(records.collapsible)
            assertTrue(records.summary.contains(if (ja) "選択候補を得た試行と、別条件" else "include the selected candidate's trial and trials with other conditions"))
            assertTrue(records.text.contains(if (ja) "試行の時間上限" else "trial reached its time limit"))
            assertTrue(records.text.contains(if (ja) "不明ビットの組合せ" else "Unknown-bit combinations"))
            assertTrue(records.text.contains("Q/6"))
            val settings = report.sections.first().summary
            assertTrue(settings.contains(if (ja) "バイト区間の UTF-8 仮定: ON" else "UTF-8 assumption for Byte segments: ON"))
            assertEquals(SearchCompleteness.UNSEARCHED, cumulative.completeness)
            assertTrue(cumulative.unresolvedCalculations)
        }
    }

    @Test fun interruptedAndOmittedTrialsNeverAcquireAUniqueCompletedLabel() {
        val selected = candidate()
        val geometry = GridGeometry(selected.version, FinderRole.TOP_LEFT, 30.0, 30.0, 5.0, 0.0)
        val diagnostic = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE,
            unresolvedCalculations = true, incompleteReasons = setOf(SearchIncompleteReason.USER_INTERRUPTED), candidatesOmitted = true)
        val attempt = GeometryAttempt(GeometryHypothesis(emptyList(), geometry, 0.0), QrMatrix(geometry.dimension),
            MatrixSampler.SamplingStats(128.0, 64.0, 32.0), listOf(selected), searchDiagnostic = diagnostic)
        inBothLanguages { ja ->
            val context = RecoveryDiagnosticContext(1, true, true, diagnostic,
                RecoveryResultEntry(attempt, selected, null), 1, RecoveryDiscoveryCount.AtLeast(2))
            val text = RecoveryDiagnosticPresenter(ja).searchResultDescription(context)
            assertTrue(text.contains(if (ja) "見つかった候補: 2件以上" else "Candidates found: at least 2"))
            assertTrue(text.contains(if (ja) "保持上限" else "retention limit"))
            assertTrue(text.contains(if (ja) "対象の形式・計算と理由は記録されていません" else "their format, calculation, and reason were not recorded"))
            assertTrue(text.contains(if (ja) "利用者が探索を中断" else "interrupted by the user"))
            assertFalse(text.contains(SearchCompleteness.COMPLETE.description()))
        }
    }

    @Test fun unresolvedDirectSearchDoesNotClaimUnqualifiedCompletion() = inBothLanguages { ja ->
        val diagnostic = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE, unresolvedCalculations = true)
        val text = RecoveryDiagnosticPresenter(ja).searchResultDescription(RecoveryDiagnosticContext(1, false, false, diagnostic, null))
        assertTrue(text.contains(if (ja) "対象の形式・計算と理由は記録されていません" else "their format, calculation, and reason were not recorded"))
        assertFalse(text.contains(SearchCompleteness.COMPLETE.description()))
        assertEquals(SearchCompleteness.COMPLETE, diagnostic.completeness)
    }

    @Test fun fourCornerGeometryDoesNotClaimTheCornersWereManuallyAssigned() = inBothLanguages { ja ->
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 30.0, 30.0, 5.0, 0.0).asProjective()
            .copy(model = GeometryModel.MANUAL_PROJECTIVE)
        val text = RecoveryDiagnosticPresenter(ja).geometryDiagnosticText(geometry)
        assertTrue(text.contains(if (ja) "四隅に基づく射影変換" else "Projective mapping from four corners"))
        assertFalse(text.contains(if (ja) "手動" else "Manual"))
        val warp = ThinPlateWarp(listOf(PointD(0.0, 0.0), PointD(1.0, 0.0), PointD(0.0, 1.0))
            .map { WarpLandmark(it, PointD(0.0, 0.0)) })
        val curved = RecoveryDiagnosticPresenter(ja).geometryDiagnosticText(geometry.copy(model = GeometryModel.THIN_PLATE_SPLINE, warp = warp))
        assertTrue(curved.contains(if (ja) "曲面補正を使用" else "Distortion correction applied"))
    }

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
            val text = report.overview + "\n" + report.sections.last().summary
            assertTrue(text.contains(if (ja) "QR構造: 確認済み" else "QR structure: verified"))
            assertTrue(text.contains(if (ja) "最終復号: 成功していません" else "Final decoding: not successful"))
            assertTrue(text.contains(if (ja) "形式情報: 不一致2bit／比較対象28bit" else "Format information: 2 mismatches / 28 bits compared"))
            assertTrue(text.contains(if (ja) "データ・ECC: 不一致3bit／比較対象192bit" else "Data/ECC: 3 mismatches / 192 bits compared"))
            assertTrue(text.contains(if (ja) "テキストとしての復号には成功していません" else "Decoding as text has not succeeded"))
            assertFalse(text.contains(if (ja) "文字として表示できない" else "cannot be displayed as text"))
            assertTrue(text.contains(if (ja) "その追加探索で残っていた組合せ" else "Remaining combinations in that fallback"))
            assertTrue(text.contains(if (ja) "時間切れ" else "time limit"))
            assertTrue(text.contains(if (ja) "候補の省略: あり" else "Candidates omitted: yes"))
            assertTrue(text.contains(if (ja) "同一であることを保証するものではありません" else "does not establish"))
            val summary = report.sections.last().summary
            for (evidence in listOf(
                if (ja) "QR構造: 確認済み" else "QR structure: verified",
                if (ja) "最終復号: 成功していません" else "Final decoding: not successful",
                if (ja) "形式情報: 不一致2bit／比較対象28bit" else "Format information: 2 mismatches / 28 bits compared",
                if (ja) "データ・ECC: 不一致3bit／比較対象192bit" else "Data/ECC: 3 mismatches / 192 bits compared",
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
                assertTrue("$count/$completeness", report.overview.contains(diagnostic.terminationText()))
                assertFalse("$count/$completeness", report.sections.last().summary.contains(diagnostic.terminationText()))
                if (count == 0) {
                    assertTrue(report.overview.contains(if (ja) "調べた範囲では" else "in the checked range"))
                    assertFalse(report.overview.contains(if (ja) "QR構造: 確認済み" else "QR structure: verified"))
                } else assertTrue(report.sections.last().summary.contains(if (ja) "最終復号: 成功" else "Final decoding: succeeded"))
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
            assertTrue(report.sections.last().summary.contains(if (ja) "形式情報: 比較対象0bit（一致は未確認）" else "Format information: 0 known bits to compare (agreement unknown)"))
            assertTrue(report.sections.last().summary.contains(if (ja) "データ・ECC: 比較対象0bit（一致は未確認）" else "Data/ECC: 0 known bits to compare (agreement unknown)"))
            assertTrue(report.sections.last().summary.contains(if (ja) "比較対象0bit（一致は未確認）" else "0 known bits to compare (agreement unknown)"))
            assertFalse(report.sections.last().summary.contains(SearchCompleteness.UNKNOWN.description()))
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
        assertFalse(report.sections[2].summary.contains(if (ja) "修復処理を実行できませんでした" else "prevented repair from running"))
        assertFalse(report.sections[2].summary.contains(if (ja) "調べた範囲では修復候補は見つかりませんでした"
            else "No recovery candidate was found in the checked range"))
        assertTrue(report.sections[2].details.isBlank())
        assertFalse(report.sections.last().summary.contains(if (ja) "修復処理を実行できませんでした" else "prevented repair from running"))
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
            for (text in listOf(second.overview, diagnosticText(view))) {
                assertTrue(text.contains(if (ja) "確認を終えていない探索" else "Some searches were not fully checked"))
                assertTrue(text.contains(if (ja) "対象の処理と終了理由は記録されていません" else "the affected work and stop reason were not recorded"))
                assertTrue(text.contains(if (ja) "保持上限" else "retention limit"))
            }
            assertFalse(diagnosticText(view).contains(verification.details))
        }
    }
    @Test fun discoveryLowerBoundAndRetainedResultsRemainSeparateFromSearchCompleteness() {
        inBothLanguages { ja ->
            val context = RecoveryDiagnosticContext(2, false, true,
                RecoverySearchDiagnostic(coverageComplete = true, solverCompleteness = SearchCompleteness.COMPLETE,
                    candidatesOmitted = true), null, discoveredCandidates = RecoveryDiscoveryCount.AtLeast(7))
            val text = RecoveryDiagnosticPresenter(ja).searchResultDescription(context)
            assertTrue(text.contains(if (ja) "見つかった候補: 7件以上" else "Candidates found: at least 7"))
            assertTrue(text.contains(if (ja) "結果一覧: 2件" else "Results available: 2"))
            assertTrue(text.contains(if (ja) "保持上限" else "retention limit"))
            assertFalse(text.contains(if (ja) "未探索の組合せ" else "combinations remain unchecked"))
        }
    }

}
