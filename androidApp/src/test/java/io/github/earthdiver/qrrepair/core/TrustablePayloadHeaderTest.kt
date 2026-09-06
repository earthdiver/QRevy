package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class TrustablePayloadHeaderTest {
    @Test fun onlyFullyObservedSupportedLeadingHeadersEnableForcing() {
        for (data in listOf(intArrayOf(0x10, 0x04), intArrayOf(0x20, 0x08),
            intArrayOf(0x40, 0x01), intArrayOf(0x80, 0x01))) {
            assertTrue(trustablePayloadHeaderAvailable(data, IntArray(data.size), 1))
            assertFalse(trustablePayloadHeaderAvailable(data, intArrayOf(0x10, 0), 1))
            assertFalse(trustablePayloadHeaderAvailable(data, intArrayOf(0, 0x80), 1))
        }
        for (data in listOf(intArrayOf(0x70, 0x34, 0x01), intArrayOf(0x54, 0x01),
            intArrayOf(0x90, 0x04, 0x01), intArrayOf(0x30, 0, 0x04, 0x01), intArrayOf(0xD1, 0x01))) {
            assertTrue(completePayloadHeaderAvailable(data, IntArray(data.size), 1))
            assertFalse(trustablePayloadHeaderAvailable(data, IntArray(data.size), 1))
        }
        assertFalse(trustablePayloadHeaderAvailable(intArrayOf(0x40), intArrayOf(0), 1))
    }

    @Test fun confirmedEciFormatCannotBeEnabledByAnUnrelatedMask() {
        val qr = Encoder.encode("hello", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val engine = RecoveryEngine()
        assertNotNull(FormatSolver.trustedCandidate(matrix))
        assertTrue(engine.hasCompletePayloadHeader(matrix))
        assertFalse(engine.hasTrustablePayloadHeader(matrix))
        assertFalse(engine.hasTrustablePayloadHeader(matrix, QrFormatConstraint(qr.ecLevel, qr.maskPattern)))
    }
}
