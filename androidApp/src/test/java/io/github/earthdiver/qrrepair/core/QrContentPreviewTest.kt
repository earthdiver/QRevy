package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrContentPreviewTest {
    private fun candidate(text: String?) = RecoveryCandidate(
        version = 1,
        ecLevel = ErrorCorrectionLevel.M,
        dataMask = 0,
        text = text,
        dataBytes = text?.toByteArray() ?: byteArrayOf(0x01),
        formatMatches = 15,
        formatMismatches = 0,
        originalErasedCodewords = 0,
        erasedCodewords = 0,
        correctedErrataSymbols = 0,
        knownBitMismatchesAfterCorrection = 0,
        knownBitsCompared = 8,
        correctedInterleaved = intArrayOf(),
        extraction = CodewordExtraction(emptyList()),
    )

    @Test
    fun parsesWifiAndMasksPassword() {
        val preview = QrContentPreviewParser.parse(candidate("WIFI:T:WPA;S:LabNet;P:secret;;"))!!
        assertEquals(QrContentKind.WIFI, preview.kind)
        val display = preview.toDisplayText()
        assertTrue(display.contains("••••"))
        assertTrue(display.contains("セキュリティ: WPA"))
        assertTrue(display.contains("パスワード: "))
        assertTrue(display.contains("QRデータ構造: 適合\nテキスト: 適合"))
    }

    @Test
    fun parsesUrlAndVcard() {
        assertEquals(QrContentKind.URL, QrContentPreviewParser.parse(candidate("https://example.com/a"))!!.kind)
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:Alice\nTEL:+81\nEND:VCARD"
        assertEquals(QrContentKind.VCARD, QrContentPreviewParser.parse(candidate(vcard))!!.kind)
    }

    @Test
    fun binaryStatusDoesNotUseSlashAsAWordSeparator() {
        val display = QrContentPreviewParser.parse(candidate(null))!!.toDisplayText()

        assertTrue(display.contains("テキスト: バイナリまたは不明"))
        assertTrue(!display.contains("バイナリ/不明"))
    }

    @Test
    fun preservesLeadingAndTrailingWhitespaceExactly() {
        val payload = "  https://example.com/a\n\t"

        val preview = QrContentPreviewParser.parse(candidate(payload))!!

        assertEquals(QrContentKind.TEXT, preview.kind)
        assertEquals(payload, preview.summary)
        assertTrue(preview.toDisplayText().contains(payload))
    }
}
