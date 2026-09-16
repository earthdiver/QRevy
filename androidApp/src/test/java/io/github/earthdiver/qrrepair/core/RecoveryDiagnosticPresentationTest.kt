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

    @Test fun successfulConventionalCalculationKeepsInconsistentBitAnalysisInDetails() = inBothLanguages { ja ->
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "",
            RsBitDiagnostic(16, 0, null, 0, emptyList()), null, 2, conventional())
        assertTrue(section.summary.contains(if (ja) "訂正余裕（ブロック最小）: 6" else "minimum across blocks): 6"))
        assertFalse(section.summary.contains(if (ja) "組合せはありません" else "No combination"))
        assertTrue(section.details.contains(if (ja) "自由度0とは異なります" else "not zero free bits"))
        assertFalse(section.details.contains(if (ja) "自由度0（1通り）" else "0 free bits (1 combinations)"))
    }

    @Test fun rankIsDistinctFromEquationCountAndBitRouteRemainsVisible() = inBothLanguages { ja ->
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.L, 2, "",
            RsBitDiagnostic(20, 20, 0, 20, emptyList()), null, 20,
            candidateUsesBitConstraints = true)
        assertTrue(section.summary.contains(if (ja) "不明20bit − 独立な条件20個 = 自由度0" else "20 unknown bits − 20 independent constraints = 0 free bits"))
        assertTrue(section.details.contains(if (ja) "RSの式: 56本" else "RS equations: 56"))
        val withConventional = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "",
            RsBitDiagnostic(60, 56, 4, 0, emptyList()), null, 8, conventional(),
            candidateUsesBitConstraints = true)
        assertTrue(withConventional.summary.contains(if (ja) "16通り" else "16 combinations"))
    }

    @Test fun failedConventionalBlocksHaveUnknownErrorAndMarginAndLargeSpacesStaySymbolic() = inBothLanguages { ja ->
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "",
            RsBitDiagnostic(208, 80, 128, 0, emptyList()), null, 26, conventional(false))
        assertFalse(section.summary.contains(if (ja) "訂正余裕（ブロック最小）" else "Correction margin for this calculation"))
        assertTrue(section.details.contains(if (ja) "誤り— / 余裕—" else "errors — / margin —"))
        assertTrue(section.summary.contains(if (ja) "2の128乗" else "2^128"))
        assertTrue(section.summary.contains(if (ja) "データ16 + ECC10" else "16 data + 10 ECC"))
    }

    @Test fun independentRsResultDoesNotImplySelectedCandidateOrAllowedSettings() = inBothLanguages { ja ->
        val separate = conventional().copy(knownBitMismatches = 3, knownBitMismatchLimit = 0)
        val section = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "", null, 16, 2,
            separate, intArrayOf(9, 9))
        assertTrue(section.summary.contains(if (ja) "そのまま採用できません" else "cannot be accepted as-is"))
        assertTrue(section.summary.contains(if (ja) "選択中の候補とは別" else "differs from the selected candidate"))
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
        assertTrue(bit.contains(if (ja) "自由度: 4" else "degrees of freedom at this calculation stage: 4"))
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
}
