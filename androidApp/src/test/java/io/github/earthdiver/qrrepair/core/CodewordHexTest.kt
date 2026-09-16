package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodewordHexTest {
    @Test fun formatsMaskedAndUnmaskedBytesWithUnknownMarker() {
        val matrix = QrMatrix(21)
        val coords = (0 until 8).map { Coord(it, 9) }
        val bits = listOf(1, 0, 1, 0, 0, 1, 0, 1)
        coords.zip(bits).forEach { (c, bit) ->
            matrix.set(c.x, c.y, if (bit == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtraction(listOf(PartialCodeword(0x3C, 0, coords)))
        assertEquals("0000  A5", CodewordHex.masked(matrix, extraction))
        assertEquals("0000  3C", CodewordHex.unmasked(extraction))

        matrix.set(coords[2].x, coords[2].y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        assertTrue(CodewordHex.masked(matrix, extraction).endsWith("??"))
    }

    @Test fun rendersCorrectedBytesInBothMaskStates() {
        val coords = (0 until 8).map { Coord(it, 9) }
        val extraction = CodewordExtraction(listOf(PartialCodeword(0, 0xFF, coords)))
        val corrected = intArrayOf(0x3C)

        assertEquals("0000  3C", CodewordHex.corrected(corrected))
        assertEquals("0000  69", CodewordHex.correctedMasked(corrected, extraction, 0))
        assertTrue(CodewordHex.correctedMaskedBinary(corrected, extraction, 0).endsWith("01101001"))
    }

    @Test fun recoveredContentUsesValidatedTextWithoutReDecodingItsBytes() {
        // Shift_JIS bytes decoded as UTF-8 would contain replacement characters. The data view
        // must use the text already decoded by the QR parser instead.
        val shiftJisPayload = PayloadBits().apply {
            put(4, 4); put(6, 8)
            "日本語".toByteArray(charset("Shift_JIS")).forEach { put(it.toInt() and 255, 8) }
        }.finish()
        val rendered = CodewordHex.interpretedRecovered(shiftJisPayload, 1, "日本語")

        assertTrue(rendered.contains("日本語"))
        assertTrue(!rendered.contains('\uFFFD'))
    }

    @Test fun recoveredContentFallsBackToDeinterleavedDataWhenDecodeIsUnavailable() {
        // Byte mode, count 1, payload 'A'.
        val data = byteArrayOf(0x40, 0x14, 0x10)

        assertTrue(CodewordHex.interpretedRecovered(data, 1, null).endsWith("0000  41"))
    }
    @Test fun byteContentShowsExactPayloadBeforeReferenceText() {
        val data = PayloadBits().apply {
            put(4, 4); put(4, 8)
            listOf(0, 255, 128, 65).forEach { put(it, 8) }
        }.finish()
        val rendered = CodewordHex.interpretedRecovered(data, 1, "�A")
        assertTrue(rendered.contains("バイト数: 4\n実データ (HEX)\n0000  00 FF 80 41"))
        assertTrue(rendered.indexOf("00 FF 80 41") < rendered.indexOf("参考:"))
        assertTrue(!rendered.contains("EC 11"))
    }

    @Test fun eciAndMixedModesKeepByteSegmentBoundaries() {
        val data = PayloadBits().apply {
            put(7, 4); put(26, 8)
            put(1, 4); put(1, 10); put(7, 4)
            put(4, 4); put(1, 8); put(255, 8)
            put(4, 4); put(1, 8); put(0, 8)
        }.finish()
        val rendered = CodewordHex.interpretedRecovered(data, 1, "7�")
        assertTrue(rendered.startsWith("モード: 数字 → バイト → バイト"))
        assertTrue(rendered.contains("セグメント 2 / バイト数: 1\n実データ (HEX)\n0000  FF"))
        assertTrue(rendered.contains("セグメント 3 / バイト数: 1\n実データ (HEX)\n0000  00"))
    }

    @Test fun numericContentStaysTextAndInvalidStreamsDoNotClaimPayloadBytes() {
        val data = PayloadBits().apply { put(1, 4); put(3, 10); put(123, 10) }.finish()
        assertEquals("モード: 数字\n内容\n123", CodewordHex.interpretedRecovered(data, 1, "123"))
        assertEquals("QRデータ構造を確認できません。",
            CodewordHex.interpretedRecovered(byteArrayOf(0x40), 1, "bogus"))
    }

    @Test fun byteCountUsesSixteenBitsFromVersionTen() {
        val data = PayloadBits().apply { put(4, 4); put(1, 16); put(128, 8) }.finish()
        assertTrue(CodewordHex.interpretedRecovered(data, 10, null).endsWith("0000  80"))
    }

    @Test fun partialByteContentDoesNotInventZeroOrDecodeAsUtf8() {
        val data = byteArrayOf(0x40, 0x20, 0x0F, 0xF0.toByte())
        val extraction = CodewordExtraction(data.mapIndexed { i, value ->
            PartialCodeword(value.toInt() and 255, if (i == 1) 1 else 0, emptyList())
        })
        assertTrue(CodewordHex.interpreted(extraction, 1).endsWith("?? FF"))
    }

}
