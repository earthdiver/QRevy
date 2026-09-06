package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.ZxingPayloadBridge
import org.junit.Assert.*
import org.junit.Test

class Fnc1SecondPositionRecoveryTest {
    @Test fun preservesBodyAndOriginalBytesWithEitherPrefix() {
        for (append in listOf(false, true)) for (indicator in listOf(1, 65, 165)) {
            val data = PayloadBits().apply {
                if (append) { put(3, 4); put(0x0123, 16) }
                put(9, 4); put(indicator, 8)
                put(4, 4); put(1, 8); put(65, 8)
            }.finish()
            val original = data.copyOf()
            val decoded = ZxingPayloadBridge.decodeData(data, 1, ErrorCorrectionLevel.L, null)
            assertEquals("A", decoded.text)
            assertArrayEquals(original, data)
            assertArrayEquals(original, decoded.rawBytes)
            assertEquals(if (append) 1 else -1, decoded.structuredAppendSequenceNumber)
            assertEquals(if (append) 0x23 else -1, decoded.structuredAppendParity)
            val result = recoverPayload(data).single()
            assertEquals("A", result.text)
            assertEquals(indicator, result.fnc1ApplicationIndicator)
            assertArrayEquals(original, result.dataBytes)
            assertEquals(0, result.knownBitMismatchesAfterCorrection)
        }
    }

    @Test fun retainsFnc1EscapingAndOrdinaryModes() {
        for (mode in listOf(0, 5, 9)) {
            val data = PayloadBits().apply {
                if (mode != 0) put(mode, 4)
                if (mode == 9) put(1, 8)
                put(2, 4); put(3, 9)
                put(38 * 45 + 38, 11); put(38, 6) // %%%
            }.finish()
            val result = recoverPayload(data).single()
            assertEquals(if (mode == 0) "%%%" else "%\u001d", result.text)
            assertEquals(if (mode == 9) 1 else null, result.fnc1ApplicationIndicator)
        }
    }

    @Test fun emptyBodyRemainsValidAndMissingIndicatorDoesNotDecode() {
        val empty = PayloadBits().apply { put(9, 4); put(1, 8) }.finish()
        assertEquals("", recoverPayload(empty).single().text)
        assertThrows(com.google.zxing.FormatException::class.java) {
            ZxingPayloadBridge.decodeData(byteArrayOf(0x90.toByte()), 1, ErrorCorrectionLevel.L, null)
        }
    }
}
