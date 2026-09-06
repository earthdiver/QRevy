package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class QrCharacterValidationTest {
    private fun stream(mode: Int, vararg values: Int) = PayloadBits().apply {
        put(mode, 4)
        if (mode == 13) put(1, 4)
        put(values.size, 8)
        values.forEach { put(it, 13) }
    }.finish()

    @Test fun rejectsInvalidKanjiAndHanziEvenWithExactRsParity() {
        // Kanji: 817F, 81FD, 81FF, unassigned 81AD. Hanzi: A1FF, A2A1 (unassigned), FBC0.
        for ((mode, values) in listOf(8 to listOf(63, 189, 191, 109), 13 to listOf(94, 96, 8191))) {
            for (value in values) {
                val data = stream(mode, value)
                assertFalse("mode=$mode value=$value", StrictQrPayloadValidator.isValid(data, 1))
                assertTrue("Invalid character survived QR recovery", recoverPayload(data).isEmpty())
            }
        }
    }

    @Test fun acceptsAssignedCharactersAndPrunesOnlyAfterTheWholeCharacterIsKnown() {
        // 0 maps to the ideographic space in both QR character modes.
        for (mode in listOf(8, 13)) {
            val data = stream(mode, 0, 0)
            assertTrue(StrictQrPayloadValidator.isValid(data, 1))
            assertEquals("\u3000\u3000", recoverPayload(data).single().text)
            for (length in 0..data.size) {
                assertTrue("mode=$mode prefix=$length", StrictQrPayloadValidator.canBePrefix(data.copyOf(length), 1))
            }
            val invalid = stream(mode, if (mode == 8) 63 else 94)
            assertTrue(StrictQrPayloadValidator.canBePrefix(invalid.copyOf(3), 1))
            assertFalse(StrictQrPayloadValidator.canBePrefix(invalid.copyOf(4), 1))
        }
    }

    @Test fun acceptsActualKanjiAcrossVersionGroupsAndRsBlocks() {
        for (version in listOf(1, 10, 27)) {
            val text = "漢字"
            val qr = Encoder.encode(text, ErrorCorrectionLevel.L,
                mapOf(EncodeHintType.CHARACTER_SET to "Shift_JIS", EncodeHintType.QR_VERSION to version))
            val matrix = QrMatrix(qr.matrix.width)
            for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
                matrix.set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
            }
            assertEquals(text, RecoveryEngine().recover(matrix,
                formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, qr.maskPattern),
                maxKnownDataEccMismatches = 0).single().text)
        }
    }

    @Test fun arbitraryByteDataIsNotSubjectToKanjiRules() {
        val data = PayloadBits().apply { put(4, 4); put(2, 8); put(0x81, 8); put(0x7F, 8) }.finish()
        assertTrue(StrictQrPayloadValidator.isValid(data, 1))
        assertTrue(recoverPayload(data).single().payloadParsed)
    }
}
