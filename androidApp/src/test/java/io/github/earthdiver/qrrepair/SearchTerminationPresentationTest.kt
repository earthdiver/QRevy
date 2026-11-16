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
class SearchTerminationPresentationTest {
    private val complete = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE)

    @Test fun normalSearchBoundariesNameTheUncheckedWorkWithoutClaimingTimeoutOrExhaustion() = inBothLanguages { ja ->
        val labels = mapOf(
            CombinationSearchStop.SUCCESS_WINDOW to if (ja) "候補発見後の追加探索" else "Additional search after finding a candidate",
            CombinationSearchStop.COMBINATION_LIMIT to if (ja) "仕様で定めた件数" else "count defined by the search policy",
            CombinationSearchStop.NODE_LIMIT to if (ja) "仕様で定めた計算量" else "amount of work defined by the search policy")
        for ((stop, label) in labels) {
            val diagnostic = complete.copy(unresolvedCalculations = true, combinationStops = setOf(stop),
                formatOutcomes = listOf(RecoveryFormatOutcome(ErrorCorrectionLevel.M, 1,
                    RecoveryFormatState.UNRESOLVED, true, stop.name)))
            val text = diagnostic.terminationText()
            assertTrue(text, text.contains(label))
            assertTrue(text, text.contains(if (ja) "調べていません" else "not checked"))
            assertFalse(text.contains(if (ja) "時間切れ" else "time limit"))
            assertFalse(text.contains(if (ja) "未解決" else "unresolved processing"))
            assertFalse(text.contains(SearchCompleteness.COMPLETE.description()))
            assertTrue(diagnostic.formatTerminationDetails().contains("M/1"))
            assertEquals(SearchCompleteness.COMPLETE, diagnostic.completeness)
            assertTrue(diagnostic.unresolvedCalculations)
        }
    }

    @Test fun interruptionsKeepTheirCauseEvenAfterACompletedOrPolicyLimitedTrial() = inBothLanguages { ja ->
        val labels = mapOf(
            SearchIncompleteReason.USER_INTERRUPTED to if (ja) "利用者が探索を中断" else "interrupted by the user",
            SearchIncompleteReason.TIME_LIMIT to if (ja) "試行の時間上限" else "trial reached its time limit",
            SearchIncompleteReason.LIST_DECODING_TIME_LIMIT to if (ja) "リスト復号による追加探索が時間切れ" else "List-decoding fallback reached its time limit",
            SearchIncompleteReason.KNOWN_BIT_SEARCH_TIME_LIMIT to if (ja) "既知bit誤り探索が時間切れ" else "Known-bit error search reached its time limit",
            SearchIncompleteReason.KNOWN_BIT_SEARCH_LIMIT to if (ja) "試行数の上限" else "trial-count limit",
            SearchIncompleteReason.WORKING_MEMORY_LIMIT to if (ja) "計算用メモリの上限" else "working-memory limit",
            SearchIncompleteReason.INTERNAL_ERROR to if (ja) "内部エラー" else "internal error")
        for ((reason, label) in labels) {
            val combined = RecoverySearchDiagnostic.combine(listOf(
                complete.copy(incompleteReasons = setOf(reason)),
                complete.copy(combinationStops = setOf(CombinationSearchStop.SUCCESS_WINDOW))))!!
            val text = combined.terminationText()
            assertTrue(text, text.contains(label))
            assertTrue(text.contains(if (ja) "候補発見後の追加探索" else "Additional search after finding a candidate"))
            assertFalse(text.contains(SearchCompleteness.COMPLETE.description()))
            assertEquals(SearchCompleteness.UNSEARCHED, combined.completeness)
            assertEquals(setOf(reason), combined.incompleteReasons)
            assertEquals(setOf(CombinationSearchStop.SUCCESS_WINDOW), combined.combinationStops)
        }
    }

    @Test fun inconclusiveFormatsNameTheAffectedInputAndMissingCalculation() = inBothLanguages { ja ->
        val labels = mapOf(
            "BIT_SPACE_LIMIT" to if (ja) "不明ビットの組合せ" else "Unknown-bit combinations",
            "KNOWN_BIT_CONSTRAINT_CONFLICT" to if (ja) "読み取れた白黒をすべて正しい" else "preserving every observed bit",
            "SINGLE_SEGMENT_HEADER_UNRESOLVED" to if (ja) "データ先頭の種類・文字数" else "data header's mode/count",
            "PRIORITY_ASSIGNMENT_UNRESOLVED" to if (ja) "*で指定したセル" else "cells marked *",
            "NOT_ATTEMPTED" to if (ja) "探索を実行した記録がありません" else "No search execution was recorded")
        for ((reason, label) in labels) {
            val diagnostic = complete.copy(unresolvedCalculations = true, formatOutcomes = listOf(
                RecoveryFormatOutcome(ErrorCorrectionLevel.Q, 6, RecoveryFormatState.UNRESOLVED, false, reason)))
            val text = diagnostic.formatTerminationDetails()
            assertTrue(text.contains("Q/6"))
            assertTrue(text, text.contains(label))
            assertTrue(diagnostic.terminationText().contains(label))
            assertFalse(diagnostic.terminationText().contains(SearchCompleteness.COMPLETE.description()))
        }
    }

    @Test fun missingReasonsAndMissingCoverageStayUnknownWithoutInventingFailures() = inBothLanguages { ja ->
        for (diagnostic in listOf(complete.copy(unresolvedCalculations = true),
            complete.copy(unsearched = true), complete.copy(coverageComplete = false))) {
            val text = diagnostic.terminationText()
            assertTrue(text, text.contains(if (ja) "記録" else "record"))
            assertFalse(text.contains(if (ja) "時間切れ" else "time limit"))
            assertFalse(text.contains(SearchCompleteness.COMPLETE.description()))
        }
    }

    private fun inBothLanguages(check: (Boolean) -> Unit) {
        val previous = AppLanguageManager.current
        try { for (language in AppLanguage.entries) {
            AppLanguageManager.applyTo(RuntimeEnvironment.getApplication(), language)
            check(language == AppLanguage.JAPANESE)
        } } finally { AppLanguageManager.applyTo(RuntimeEnvironment.getApplication(), previous) }
    }
}
