package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.AppLanguage
import io.github.earthdiver.qrrepair.AppLanguageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryDiagnosticPresentationTest {
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

    private fun conventional(success: Boolean = true) = ConventionalRsDiagnostic(
        listOf(ConventionalRsBlockDiagnostic(0, 26, 16, 10, if (success) 2 else 12,
            if (success) 1 else null, if (success) 6 else null,
            if (success) ConventionalRsStatus.SUCCESS else ConventionalRsStatus.ERASURE_CAPACITY_EXCEEDED)),
        if (success) intArrayOf(1, 2) else null,
    )

    @Test fun referenceDiagnosticDistinguishesSkippedFailedSuccessfulAndUnrecordedCalculations() = inBothLanguages { ja ->
        fun section(diagnostic: ConventionalRsDiagnostic?) = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2,
            "", null, null, null, diagnostic)
        val skipped = conventional(false)
        assertTrue(section(skipped).details.contains(if (ja) "訂正計算を省略しました" else "correction was skipped"))
        val mixed = skipped.copy(blocks = listOf(skipped.blocks.single(), conventional().blocks.single(),
            skipped.blocks.single().copy(status = ConventionalRsStatus.CORRECTION_FAILED)))
        val report = section(mixed)
        assertTrue(report.summary.contains(if (ja) "訂正結果あり 1／計算省略 1／訂正結果なし 1 ブロック" else "1 with a correction result / 1 skipped / 1 without a correction result (blocks)"))
        assertTrue(report.details.contains(if (ja) "計算したが訂正結果なし：1ブロック" else "correction attempted without a result: 1 blocks"))
        assertTrue(report.details.contains(if (ja) "12個がECCの10個を超える" else "12 codewords contain unknown bits, exceeding 10"))
        assertTrue(section(null).summary.contains(if (ja) "記録なし" else "Not recorded"))
        assertFalse(section(null).summary.contains(if (ja) "省略しました" else "was skipped"))
    }

    @Test fun zeroLimitPolicyAndReferenceResultDoNotClaimTheSameExecution() = inBothLanguages { ja ->
        val settings = RecoveryDisplayConditions(true, false, null, null, 0)
        assertTrue(settings.candidateCalculationPolicyText().contains(if (ja) "部分bit制約を使う設定" else "configured to use partial-bit constraints"))
        assertTrue(settings.runSettingsText().contains(if (ja) "完全に一致する候補だけ" else "matching every entered"))
        assertTrue(settings.runSettingsText().contains(if (ja) "固定セルとの矛盾を許容していても" else "does not override"))
        val input = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "", null, null, null, conventional(), conditions = settings)
        assertTrue(input.summary.contains(if (ja) "修復開始時の入力を対象にした参考診断" else "Reference diagnostic of the input at repair start"))
        assertTrue(input.details.contains(if (ja) "通常RSだけを適用した参考診断" else "applies conventional RS alone"))
        assertTrue(input.details.contains(if (ja) "不明セルの白黒の仮置きは加えていません" else "without structure filling or tentative assignments"))
        assertFalse(input.details.contains(if (ja) "別の計算" else "separate calculation"))
        assertEquals("", settings.copy(dataEccLimit = 1).candidateCalculationPolicyText())
        assertEquals("", (null as RecoveryDisplayConditions?).candidateCalculationPolicyText())
    }

    @Test fun runSettingsShowTheRecordedPartialBitLimitInSummaryAndDetails() = inBothLanguages { ja ->
        for (limit in listOf(12, 14)) {
            val settings = RecoveryDisplayConditions(false, true, null, null, 0, partialBitSearchLimit = limit)
            val section = settings.runSettingsSection()
            val expected = if (ja) "部分bit探索・自由度の上限: ${limit}bit／RSブロック"
                else "Partial-bit search / dimension limit: $limit bits per RS block"

            assertTrue(section.summary, section.summary.contains(expected))
            assertTrue(section.details, section.details.contains(expected))
        }
        val unrecorded = (null as RecoveryDisplayConditions?).runSettingsSection()
        assertFalse(unrecorded.summary.contains(if (ja) "自由度の上限" else "dimension limit"))
    }

    @Test fun finalResultsSeparateNotFoundFromUnsearchedAndMixedRejectionReasons() = inBothLanguages { ja ->
        val settings = RecoveryDisplayConditions(false, true, null, null, 2)
        val mixed = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE, candidateRejections = setOf(
            RecoveryCandidateRejection.FIXED_CELLS, RecoveryCandidateRejection.MISMATCH_LIMIT))
        assertTrue(mixed.failureExplanation(settings).contains(if (ja) "不一致上限を超えるものや" else "exceeded the mismatch limit or"))
        assertEquals("", mixed.copy(unsearched = true).failureExplanation(settings))
        assertEquals("", mixed.copy(acceptedCandidate = true).failureExplanation(settings))
        assertTrue(searchResultText(0, io.github.earthdiver.qrrepair.core.SearchCompleteness.UNKNOWN).contains(if (ja) "見つかりませんでした" else "was found"))
        assertFalse(searchResultText(0, io.github.earthdiver.qrrepair.core.SearchCompleteness.UNKNOWN).contains(if (ja) "候補はありません" else "No RS candidate meets"))
        assertTrue(searchResultText(0, io.github.earthdiver.qrrepair.core.SearchCompleteness.UNSEARCHED).contains(if (ja) "未探索" else "unchecked"))
        assertTrue(searchResultText(2, io.github.earthdiver.qrrepair.core.SearchCompleteness.UNSEARCHED).contains(if (ja) "未探索" else "unchecked"))
        assertTrue(searchResultText(2, io.github.earthdiver.qrrepair.core.SearchCompleteness.UNKNOWN).contains(if (ja) "2件" else "2 recovery candidates"))
    }

    @Test fun coverageStatesAndRetentionOmissionAreIndependentInBothLanguages() = inBothLanguages { ja ->
        val complete = searchResultText(2, SearchCompleteness.COMPLETE)
        val unknown = searchResultText(2, SearchCompleteness.UNKNOWN)
        val unchecked = searchResultText(2, SearchCompleteness.UNSEARCHED)
        assertTrue(complete.contains(if (ja) "範囲で探索を完了しました" else "Search completed"))
        assertTrue(unknown.contains(if (ja) "完了状況を確認できません" else "completion could not be determined"))
        assertFalse(unknown.contains(if (ja) "未探索" else "unchecked"))
        assertTrue(unchecked.contains(if (ja) "未探索" else "unchecked"))
        val retained = searchResultText(0, SearchCompleteness.COMPLETE, candidatesOmitted = true)
        assertTrue(retained.contains(if (ja) "保持上限" else "retention limit"))
        assertTrue(retained.contains(if (ja) "範囲で探索を完了しました" else "Search completed"))
        assertFalse(retained.contains(if (ja) "未探索" else "unchecked"))
        assertFalse(retained.contains(if (ja) "見つかりません" else "No recovery candidate"))
    }

    @Test fun uncheckedCombinationsDoNotImplyAResourceLimit() = inBothLanguages { ja ->
        val text = searchResultText(0, io.github.earthdiver.qrrepair.core.SearchCompleteness.UNSEARCHED)
        assertFalse(text.contains(if (ja) "上限に達しました" else "limit was reached"))
        assertTrue(text.contains(if (ja) "未探索の組合せ" else "combinations remain unchecked"))
        val interrupted = CombinationSearchSummary(stops = setOf(CombinationSearchStop.USER_INTERRUPTED)).stopReasonsText()
        assertTrue(interrupted.contains(if (ja) "中断" else "interrupted"))
        assertFalse(interrupted.contains(if (ja) "上限" else "limit"))
        val limited = CombinationSearchSummary(stops = setOf(CombinationSearchStop.COMBINATION_LIMIT)).stopReasonsText()
        assertTrue(limited.contains(if (ja) "上限" else "limit"))
    }

    @Test fun successfulConventionalCalculationKeepsInconsistentBitAnalysisInDetails() = inBothLanguages { ja ->
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "",
            RsBitDiagnostic(16, 0, null, 0, emptyList()), null, 2, conventional())
        assertTrue(section.details.contains(if (ja) "訂正余裕（ブロック最小）: 6" else "minimum across blocks): 6"))
        assertFalse(section.summary.contains(if (ja) "組合せはありません" else "No combination"))
        assertTrue(section.details.contains(if (ja) "自由度0とは異なります" else "not zero free bits"))
        assertFalse(section.details.contains(if (ja) "自由度0（1通り）" else "0 free bits (1 combinations)"))
    }

    @Test fun rankIsDistinctFromEquationCountAndBitRouteRemainsVisible() = inBothLanguages { ja ->
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.L, 2, "",
            RsBitDiagnostic(20, 20, 0, 20, emptyList()), null, 20)
        assertTrue(section.details.contains(if (ja) "不明20bit − 独立な条件20個 = 自由度0" else "20 unknown bits − 20 independent constraints = 0 free bits"))
        assertTrue(section.details.contains(if (ja) "RSの式: 56本" else "RS equations: 56"))
        val withConventional = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "",
            RsBitDiagnostic(60, 56, 4, 0, emptyList()), null, 8, conventional())
        assertTrue(withConventional.details.contains(if (ja) "16通り" else "16 combinations"))
    }

    @Test fun failedConventionalBlocksHaveUnknownErrorAndMarginAndLargeSpacesStaySymbolic() = inBothLanguages { ja ->
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "",
            RsBitDiagnostic(208, 80, 128, 0, emptyList()), null, 26, conventional(false))
        assertFalse(section.details.contains(if (ja) "訂正余裕（ブロック最小）" else "RS correction margin in the initial-input reference diagnostic"))
        assertTrue(section.details.contains(if (ja) "誤り— / 余裕—" else "errors — / margin —"))
        assertTrue(section.details.contains(if (ja) "2の128乗" else "2^128"))
        assertTrue(section.details.contains(if (ja) "データ16 + ECC10" else "16 data + 10 ECC"))
    }

    @Test fun independentRsResultDoesNotImplySelectedCandidateOrAllowedSettings() = inBothLanguages { ja ->
        val separate = conventional().copy(knownBitMismatches = 3, knownBitMismatchLimit = 0)
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "", null, 16, 2,
            separate, intArrayOf(9, 9))
        assertTrue(section.details.contains(if (ja) "そのまま採用できません" else "cannot be accepted as-is"))
        assertTrue(section.details.contains(if (ja) "選択中の候補とは別" else "differs from the selected candidate"))
    }

    @Test fun mixedRepresentativeBlockMethodsAreBothReported() = inBothLanguages { ja ->
        val candidate = RecoveryCandidate(1, ErrorCorrectionLevel.M, 2, "HELLO", byteArrayOf(),
            30, 0, 2, 2, 2, 0, 192, intArrayOf(), CodewordExtraction(emptyList()),
            actualBlockMethods = listOf(RecoveryBlockMethod(0, false, 6, 0), RecoveryBlockMethod(1, true, null, 0)))
        val compact = candidate.compactUsedMethodsText()
        val detailed = candidate.usedMethodsText()
        assertTrue(compact.contains(if (ja) "通常RS" else "conventional RS"))
        assertTrue(compact.contains(if (ja) "部分bit制約" else "partial-bit constraints"))
        assertTrue(detailed.contains(if (ja) "通常RS" else "Conventional RS"))
        assertTrue(detailed.contains(if (ja) "部分bit制約" else "Partial-bit constraints"))
    }

    @Test fun conventionalComparisonReportsEqualityDifferenceAndMissingResult() = inBothLanguages { ja ->
        assertTrue(conventional().comparisonText(intArrayOf(1, 2)).contains(if (ja) "同じデータ・ECCコード語" else "same data/ECC codewords"))
        assertTrue(conventional().comparisonText(intArrayOf(1, 3)).contains(if (ja) "異なります" else "differs"))
        assertTrue(conventional(false).comparisonText(intArrayOf(1, 2)).contains(if (ja) "比較結果はありません" else "No comparison result"))
        val normal = RecoveryBlockMethod(0, false, 4, 3).searchDescription()
        val bit = RecoveryBlockMethod(1, true, null, 4).searchDescription()
        assertTrue(normal.contains(if (ja) "仮置きした不明bit: 3" else "assigned before calculation: 3"))
        assertFalse(normal.contains(if (ja) "自由度" else "degrees of freedom"))
        assertTrue(bit.contains(if (ja) "自由度: 4" else "degrees of freedom for this block: 4"))
    }

    @Test fun referenceConflictsDescribeTheSavedFixedCellSettingWithoutInventingAnAcceptanceResult() = inBothLanguages { ja ->
        val diagnostic = conventional().copy(manualConflicts = listOf(
            ManualCellConflict(Coord(10, 10), ModuleValue.ZERO, ModuleValue.ONE)))
        for (lock in listOf(true, false, null)) {
            val settings = RecoveryDisplayConditions(false, lock, null, null, null)
            val text = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "", null, null, null,
                diagnostic, conditions = settings).details
            assertTrue(text.contains(if (ja) "固定セル1個の0/1が一致しません" else "conflicts with 1 fixed 0/1 values"))
            assertEquals(lock == true, text.contains(if (ja) "そのまま採用できません" else "cannot be accepted as-is"))
            if (lock == false) assertTrue(text.contains(if (ja) "他の採用条件も適用されます" else "other acceptance conditions"))
            if (lock == null) assertTrue(text.contains(if (ja) "固定セルの扱いは記録されていません" else "setting for handling fixed cells was not recorded"))
        }
    }

    @Test fun emptyReferenceRecordsDoNotProduceBlockCalculations() = inBothLanguages { ja ->
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "", null, null, null,
            ConventionalRsDiagnostic(emptyList(), null))
        assertTrue(section.summary.contains(if (ja) "記録なし" else "Not recorded"))
        assertFalse(section.details.contains(if (ja) "ブロック別記録" else "Block records"))
        assertFalse(section.details.contains(if (ja) "訂正余裕 =" else "Margin ="))
    }

    @Test fun cellCodewordUsesExtractionOrderAndOneBasedBlockNumbers() = inBothLanguages { ja ->
        val text = codewordCellText(5, 2, 0x86, 0x20, 0xa6, 0, 3, true)
        assertTrue(text.contains("10[?]00110 → 10[1]00110"))
        assertTrue(text.contains(if (ja) "RSブロック1" else "RS block 1"))
        assertTrue(text.contains(if (ja) "マスク解除後" else "removing the QR mask"))
        assertTrue(text.contains(if (ja) "コード語6" else "Codeword 6"))
        assertTrue(codewordCellText(0, 0, 0, 128, 128, 0, 0, false).contains("[?]0000000 → [1]0000000"))
        assertTrue(codewordCellText(0, 7, 0, 1, 1, 0, 0, false).contains("0000000[?] → 0000000[1]"))
    }

    @Test fun longDifferencesPreserveUnicodeAndExposeWhitespaceAndEnd() = inBothLanguages { ja ->
        val prefix = "😀".repeat(140)
        val diff = firstDifferenceExcerpt(prefix + "A", prefix + "B")
        assertEquals(141, diff.position)
        assertTrue(diff.left.contains("A"))
        assertTrue(diff.right.contains("B"))
        assertTrue(diff.truncated)
        assertEquals(diff.left, String(diff.left.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        val escaped = firstDifferenceExcerpt("a\n", "a\\n")
        assertNotEquals(escaped.left, escaped.right)
        val space = firstDifferenceExcerpt(" ", "␠")
        assertNotEquals(space.left, space.right)
        assertTrue(firstDifferenceExcerpt("a\u200Bb", "ab").left.contains("\\u{200B}"))
        val added = firstDifferenceExcerpt("abc", "abc ")
        assertTrue(added.left.contains(if (ja) "末尾" else "end"))
        assertTrue(added.right.contains("␠"))
        assertTrue(firstDifferenceExcerpt("a\n", "a\t").left.contains("\\n"))
        assertTrue(firstDifferenceExcerpt("a\n", "a\t").right.contains("\\t"))
    }
    @Test fun internalErrorMessagesUseExecutionEvidenceRatherThanCandidateCount() = inBothLanguages { ja ->
        val error = RecoverySearchDiagnostic(incompleteReasons = setOf(SearchIncompleteReason.INTERNAL_ERROR),
            solverCompleteness = SearchCompleteness.COMPLETE)
        assertTrue(error.completionReasonsText().contains(if (ja) "修復処理を実行できません" else "prevented repair"))
        assertTrue(error.copy(completedSteps = 1).completionReasonsText().contains(if (ja) "一部の探索" else "part of the search"))
        assertTrue(error.copy(coverageComplete = false).completionReasonsText().contains(if (ja) "完了状況を確認できません" else "determining search completion"))
        val both = error.copy(incompleteReasons = error.incompleteReasons + SearchIncompleteReason.WORKING_MEMORY_LIMIT)
        assertTrue(both.completionReasonsText().contains(if (ja) "計算用メモリ" else "working-memory"))
    }

}
