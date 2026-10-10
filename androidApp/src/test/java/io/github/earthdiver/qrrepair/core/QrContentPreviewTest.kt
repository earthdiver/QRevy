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
        dataBytes = text?.let { byteStream(it) } ?: byteArrayOf(0x01),
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

    private fun byteStream(text: String): ByteArray {
        val bytes = text.toByteArray()
        return stream("0100" + bytes.size.toString(2).padStart(8, '0') +
            bytes.joinToString("") { (it.toInt() and 255).toString(2).padStart(8, '0') })
    }

    @Test
    fun qrStructureDoesNotDependOnPayloadClassificationOrTextConversion() {
        val text = QrContentPreviewParser.parse(candidate("CONFIG:T:TYPE;S:;P:secret;;"))!!
        assertTrue(text.qrStructureValid)
        assertEquals(QrContentKind.TEXT, text.kind)
        assertTrue(text.contentFormatValid)
        assertTrue(text.toDisplayText().startsWith("表示中の候補は、QRのデータ形式として読み取れました。"))
        assertTrue(text.toDisplayText().contains("CONFIG:T:TYPE;S:;P:secret;;"))
        val binary = QrContentPreviewParser.parse(candidate(null).copy(dataBytes = byteStream("A")))!!
        assertTrue(binary.qrStructureValid)
        assertTrue(binary.toDisplayText().startsWith("表示中の候補は、QRのデータ形式として読み取れました。"))
        assertTrue(binary.toDisplayText().contains("HEX／Binary"))
        val invalid = QrContentPreviewParser.parse(candidate("A").copy(dataBytes = byteArrayOf(0x4)))!!
        assertTrue(!invalid.qrStructureValid)
        assertTrue(invalid.contentFormatValid)
    }

    private fun stream(bits: String): ByteArray {
        val terminated = bits + "0000"
        val aligned = terminated.padEnd((terminated.length + 7) / 8 * 8, '0')
        return aligned.chunked(8).map { it.toInt(2).toByte() }.toByteArray()
    }

    @Test
    fun byteModeUsesActualPayloadAndLabelsTextAsAConversion() {
        // Byte segment, one byte FF. Its conversion is not evidence of valid text.
        val data = stream("0100" + "00000001" + "11111111")
        val preview = QrContentPreviewParser.parse(candidate("�").copy(dataBytes = data, inferredMode = 1))!!
        assertEquals(listOf(4), preview.encodingModes)
        val display = preview.toDisplayText()
        assertTrue(display.startsWith("表示中の候補は、QRのデータ形式として読み取れました。\n符号化モード: バイト\n"))
        assertTrue(display.contains("内容プレビュー（文字列に変換した表示）"))
        assertTrue(!display.contains("文字列として読み取れました"))
        assertTrue(!display.contains("符号化モード: 数字"))
    }

    @Test
    fun mixedSegmentsAndControlPrefixesDoNotBecomeAFalseSingleMode() {
        // FNC1, ECI, numeric digit 7, byte A.
        val data = stream("0101" + "0111" + "00011010" + "0001" + "0000000001" + "0111" +
            "0100" + "00000001" + "01000001")
        assertTrue(StrictQrPayloadValidator.isValid(data, 1))
        val preview = QrContentPreviewParser.parse(candidate("7A").copy(dataBytes = data))!!
        assertEquals(listOf(1, 4), preview.encodingModes)
        assertTrue(preview.toDisplayText().contains("符号化モード: 複数セグメント — 数字 → バイト"))
        assertTrue(StrictQrPayloadValidator.encodingModes(byteArrayOf(0x4), 1) == null)
    }

    @Test
    fun structuredLookingUnknownPayloadIsDisplayedAsText() {
        val payload = "CONFIG:T:TYPE;S:Example;P:secret;;"
        val preview = QrContentPreviewParser.parse(candidate(payload))!!
        assertEquals(QrContentKind.TEXT, preview.kind)
        val display = preview.toDisplayText()
        assertTrue(display.contains(payload))
        assertTrue(!display.contains("セキュリティ: WPA"))
        assertTrue(!display.contains("パスワード: "))
        assertTrue(display.contains("QRのデータ形式として読み取れました。"))
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

        assertTrue(display.startsWith("QRのデータ構造を確認できませんでした。\n"))
        assertTrue(display.contains("内容はバイナリまたは未解釈データです"))
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

    @Test
    fun whitespacePayloadsAreDisplayedAndHighlightedWithoutChangingTheirContents() {
        val payloads = listOf("   ", "\n\t", " \r\n\t ", "  text\n\t")

        payloads.forEach { payload ->
            val preview = QrContentPreviewParser.parse(candidate(payload))!!
            val ranges = mutableListOf<IntRange>()
            val result = preview.resultContentText(payload) { start, end -> ranges += start until end }

            assertEquals(QrContentKind.TEXT, preview.kind)
            assertEquals(payload, preview.recoveredText)
            assertEquals(1, ranges.size)
            val range = ranges.single()
            assertEquals(payload, result.substring(range.first, range.last + 1))
            val characterCount = preview.fields.single()
            assertTrue(preview.toDisplayText().contains("\n$payload\n${characterCount.label}: ${payload.length}"))
        }
    }

    @Test
    fun supplementaryUnicodeCharactersCountOnceWithoutChangingTheRecoveredText() {
        for ((payload, count) in listOf("😀" to 1, "𠮷" to 1, "A😀𠮷あ" to 4, "😀 😀\n" to 4)) {
            val preview = QrContentPreviewParser.parse(candidate(payload))!!

            assertEquals(payload, preview.recoveredText)
            assertEquals(count.toString(), preview.fields.single().value)
            assertTrue(preview.toDisplayText().contains("\n$payload\n${preview.fields.single().label}: $count"))
        }
    }

    @Test
    fun emptyTextDoesNotAddAPayloadLineOrHighlight() {
        val preview = QrContentPreviewParser.parse(candidate(""))!!
        val ranges = mutableListOf<IntRange>()
        val result = preview.resultContentText("") { start, end -> ranges += start until end }

        assertTrue(ranges.isEmpty())
        assertEquals("", preview.recoveredText)
        assertTrue(!result.contains("\n\n"))
        val characterCount = preview.fields.single()
        assertTrue(preview.toDisplayText().contains("${characterCount.label}: 0"))
    }

    @Test
    fun longTextIsNotSilentlyTruncatedInDiagnostics() {
        val payload = "long diagnostic text: " + "0123456789".repeat(22)

        val preview = QrContentPreviewParser.parse(candidate(payload))!!

        assertEquals(payload, preview.summary)
        assertTrue(preview.toDisplayText().contains(payload))
    }

    @Test
    fun structuredPreviewsAlsoShowTheExactRecoveredText() {
        val payloads = listOf(
            "smsto:+819012345678:keep this complete message",
            "mailto:alice@example.com?subject=Full%20subject&body=Full%20body",
            "BEGIN:VCARD\nVERSION:3.0\nFN:Alice\nNOTE:Keep this note\nEND:VCARD",
        )

        payloads.forEach { payload ->
            val preview = QrContentPreviewParser.parse(candidate(payload))!!
            assertTrue(preview.toDisplayText().contains(payload))
            assertTrue(preview.resultContentText(payload).contains(payload))
        }
    }

    @Test
    fun resultShowsPayloadHexBeforeConvertedTextUsingTheDataDialogFormatter() {
        val data = PayloadBits().apply {
            put(4, 4); put(3, 8); put(0, 8); put(255, 8); put(65, 8)
        }.finish()
        val display = QrContentPreviewParser.parse(candidate("�A").copy(dataBytes = data))!!.toDisplayText()
        val payload = "セグメント 1 / バイト数: 3\n実データ (HEX)\n0000  00 FF 41"
        assertTrue(display.contains(payload))
        assertTrue(CodewordHex.interpretedRecovered(data, 1, "�A").contains(payload))
        assertTrue(display.indexOf(payload) < display.indexOf("参考: 文字列変換"))
        assertTrue(!display.contains("EC 11"))
    }

    @Test
    fun resultHandlesMixedSegmentsAndBytesWithoutTextConversion() {
        val data = PayloadBits().apply {
            put(7, 4); put(26, 8)
            put(1, 4); put(1, 10); put(7, 4)
            put(4, 4); put(1, 8); put(255, 8)
        }.finish()
        val display = QrContentPreviewParser.parse(candidate(null).copy(dataBytes = data))!!.toDisplayText()
        assertTrue(display.contains("数字 → バイト"))
        assertTrue(display.contains("セグメント 2 / バイト数: 1\n実データ (HEX)\n0000  FF"))
        assertTrue(!display.contains("参考: 文字列変換"))
        val invalid = QrContentPreviewParser.parse(candidate("�").copy(dataBytes = byteArrayOf(0x40)))!!
        assertTrue(!invalid.toDisplayText().contains("実データ (HEX)"))
    }

    @Test
    fun resultKeepsNumericContentAsText() {
        val data = PayloadBits().apply { put(1, 4); put(3, 10); put(123, 10) }.finish()
        val display = QrContentPreviewParser.parse(candidate("123").copy(dataBytes = data))!!.toDisplayText()
        assertTrue(display.contains("符号化モード: 数字"))
        assertTrue(display.contains("内容プレビュー: テキスト\n123"))
        assertTrue(!display.contains("文字列変換"))
        assertTrue(!display.contains("実データ (HEX)"))
    }

    @Test
    fun ordinaryTextAndHexBothPreserveActualData() {
        val preview = QrContentPreviewParser.parse(candidate("CONFIG:T:TYPE;S:Example;P:secret;;"))!!
        val masked = preview.toDisplayText()
        assertTrue(masked.contains("バイト数:"))
        assertTrue(!masked.contains("機密情報を含むため非表示"))
        assertTrue(masked.contains("secret"))
        assertTrue(!masked.contains("パスワード: "))
        assertTrue(masked.contains("73 65 63 72 65 74"))
        assertTrue(masked.contains("0000  "))
        assertTrue(preview.toDisplayText().contains("0000  43 4F 4E 46"))
    }

}
