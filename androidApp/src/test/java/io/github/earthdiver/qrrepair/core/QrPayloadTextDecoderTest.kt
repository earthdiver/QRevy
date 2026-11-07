package io.github.earthdiver.qrrepair.core

import com.google.zxing.DecodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.ZxingPayloadBridge
import org.junit.Assert.*
import org.junit.Test

class QrPayloadTextDecoderTest {
    @Test fun splitUtf8PreservesRawDataAndByteSegmentBoundariesAcrossVersionGroups() {
        for (version in listOf(1, 10, 27)) for (explicit in listOf(false, true)) {
            val data = PayloadBits().apply {
                if (explicit) { put(7, 4); put(26, 8) }
                for (value in listOf(0xc2, 0xa2)) {
                    put(4, 4); put(1, if (version < 10) 8 else 16); put(value, 8)
                }
            }.finish()
            val hints = if (explicit) null else mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")
            val result = ZxingPayloadBridge.decodeData(data, version, ErrorCorrectionLevel.L, hints)
            assertEquals("\u00a2", result.text)
            assertArrayEquals(data, result.rawBytes)
            assertEquals(2, result.byteSegments.size)
            assertArrayEquals(byteArrayOf(0xc2.toByte()), result.byteSegments[0])
            assertArrayEquals(byteArrayOf(0xa2.toByte()), result.byteSegments[1])
        }
    }

    @Test fun explicitEciInterpretsNumericAndKanjiExpandedBytes() {
        val latin = PayloadBits().apply {
            put(7, 4); put(3, 8)
            put(1, 4); put(2, 10); put(12, 7)
            put(8, 4); put(1, 8); put(109, 13) // 81 AD
        }.finish()
        assertEquals("12\u0081\u00ad", recoverPayload(latin).single().text)
        val utf16 = PayloadBits().apply {
            put(7, 4); put(25, 8)
            put(1, 4); put(2, 10); put(12, 7) // Expanded bytes 31 32, one UTF-16 code unit.
            put(4, 4); put(2, 8); put(0, 8); put(65, 8)
        }.finish()
        assertEquals("\u3132A", recoverPayload(utf16).single().text)
    }

    @Test fun repeatedUtf8DesignationAndByteToKanjiSwitchDoNotBreakCharacters() {
        val repeated = PayloadBits().apply {
            put(7, 4); put(26, 8); put(4, 4); put(1, 8); put(0xc2, 8)
            put(7, 4); put(26, 8); put(4, 4); put(1, 8); put(0xa2, 8)
        }.finish()
        assertEquals("\u00a2", recoverPayload(repeated).single().text)
        val mixed = PayloadBits().apply {
            put(7, 4); put(26, 8)
            put(4, 4); put(1, 8); put(0xe7, 8)
            put(8, 4); put(1, 8); put(0x148b, 13) // 9C 8B completes E7 9C 8B.
        }.finish()
        assertEquals("\u770b", recoverPayload(mixed).single().text)
    }

    @Test fun explicitEciRetainsFnc1PercentEscapes() {
        for (eciFirst in listOf(false, true)) {
            val data = PayloadBits().apply {
                if (eciFirst) { put(7, 4); put(26, 8) }
                put(5, 4)
                if (!eciFirst) { put(7, 4); put(26, 8) }
                put(2, 4); put(3, 9); put(38 * 45 + 38, 11); put(38, 6)
                put(4, 4); put(1, 8); put(65, 8)
            }.finish()
            assertEquals("%\u001dA", recoverPayload(data).single().text)
        }
    }
}
