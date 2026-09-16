package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedCandidateComparisonTest {
    private fun candidate(text: String?, bytes: ByteArray = text?.toByteArray() ?: byteArrayOf()) = RecoveryCandidate(
        version = 1, ecLevel = ErrorCorrectionLevel.M, dataMask = 0, text = text,
        dataBytes = bytes, formatMatches = 30, formatMismatches = 0,
        originalErasedCodewords = 0, erasedCodewords = 0, correctedErrataSymbols = 0,
        knownBitMismatchesAfterCorrection = 0, knownBitsCompared = 100,
        correctedInterleaved = intArrayOf(), extraction = CodewordExtraction(emptyList()),
    )

    @Test fun arbitraryCandidateNumbersCompareTextBeyondPreviewLimit() {
        val prefix = "a".repeat(300)
        val result = candidatePairComparisonText(candidate(prefix + "123"), candidate(prefix + "128"), 3, 7)
        assertTrue(result.contains("候補3と候補7"))
        assertTrue(result.contains("123"))
        assertTrue(result.contains("303文字目"))
        assertTrue(result.contains("128"))
        assertFalse(result.contains(prefix))
    }

    @Test fun unknownStructuredTextUsesTheOrdinaryTextComparison() {
        val first = candidate("CONFIG:T:TYPE;S:network;P:alpha;;")
        val second = candidate("CONFIG:T:TYPE;S:network;P:beta;;")
        val result = candidatePairComparisonText(first, second, 2, 4)
        assertTrue(result.contains("内容 — 最初の違い"))
        assertTrue(result.contains("alpha"))
        assertTrue(result.contains("beta"))
        assertFalse(result.contains("SSID"))
        assertFalse(result.contains("パスワード"))
        val preview = QrContentPreviewParser.parse(first)!!
        assertEquals(QrContentKind.TEXT, preview.kind)
        assertTrue(preview.toDisplayText().contains("alpha"))
        assertTrue(preview.resultContentText(first.text).contains("alpha"))
        assertFalse(candidatePairComparisonText(first, second, 1, 2).contains("••••"))
    }

    @Test fun urlComparesRawPathAndQueryWithoutDecodingAwayDifferences() {
        val result = candidatePairComparisonText(candidate("https://example.com/a%2Fb?q=1"),
            candidate("https://example.com/a/b?q=2"), 1, 3)
        assertTrue(result.contains("パス"))
        assertTrue(result.contains("/a%2Fb"))
        assertTrue(result.contains("クエリ"))
        assertTrue(result.contains("q=2"))
        assertFalse(result.contains("接続先"))
    }

    @Test fun identicalTextDoesNotImplyIdenticalData() {
        val result = candidatePairComparisonText(candidate("same", byteArrayOf(1)),
            candidate("same", byteArrayOf(2)), 1, 2)
        assertTrue(result.contains("表示する内容は同じ"))
        assertTrue(result.contains("データコード語列は異なります"))
    }

    @Test fun binaryCandidatesDoNotClaimTheirDisplayedContentIsIdentical() {
        val result = candidatePairComparisonText(candidate(null, byteArrayOf(1)),
            candidate(null, byteArrayOf(2)), 1, 2)
        assertFalse(result.contains("表示する内容は同じ"))
        assertTrue(result.contains("データコード語列は異なります"))
    }

    @Test fun compactOverviewKeepsManualDifferencesWithoutRepeatingComparisonTotals() {
        val result = candidate("test").copy(manualDataEccMismatches = 2).resultExplanationText()
        assertTrue(result.contains("手動指定と異なる値: 2bit"))
        assertFalse(result.contains("100bit"))
    }
    @Test fun urlLikeTextWithWhitespaceOrMalformedEscapesKeepsOriginalDifference() {
        val inputs = listOf(" https://example.com/a", "https://example.com/a ",
            "https://example.com/%zz", "https://example.com/a\n")
        inputs.forEach { raw ->
            val result = candidatePairComparisonText(candidate(raw), candidate("https://example.com/a"), 1, 2)
            assertTrue(result.contains("候補1"))
            assertTrue(result.contains("候補2"))
            assertFalse(result.contains("表示する内容は同じ"))
        }
    }

    @Test fun longTextDifferenceUsesTheOrdinaryTextExcerpt() {
        val prefix = "p".repeat(130)
        val first = candidate("CONFIG:T:TYPE;S:network;P:${prefix}A;;")
        val second = candidate("CONFIG:T:TYPE;S:network;P:${prefix}B;;")
        val result = candidatePairComparisonText(first, second, 1, 2)
        assertTrue(result.contains("内容 — 最初の違い"))
        assertTrue(result.contains("A"))
        assertTrue(result.contains("B"))
        assertTrue(result.contains("${first.text!!.indexOf('A') + 1}文字目"))
        assertTrue(result.contains("各候補の内容表示で全文を確認できます"))
        assertFalse(result.contains("表示する内容は同じ"))
        assertTrue(QrContentPreviewParser.parse(first)!!.resultContentText(first.text).contains(prefix + "A"))
        assertTrue(QrContentPreviewParser.parse(second)!!.resultContentText(second.text).contains(prefix + "B"))
    }

}
