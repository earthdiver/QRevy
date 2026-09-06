package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class TrustedHeaderBodyErrorTest {
    private fun damaged(text: String, headerBits: Int, groupBits: Int): Pair<QrMatrix, QrFormatConstraint> {
        val qr = Encoder.encode(text, ErrorCorrectionLevel.L)
        val matrix = QrMatrix(qr.matrix.width)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            matrix.set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        fun write(bit: Int, value: Int) {
            val coord = extraction.codewords[bit / 8].coordinates[bit % 8]
            val masked = value xor if (CodewordExtractor.maskApplies(qr.maskPattern, coord.x, coord.y)) 1 else 0
            matrix.set(coord.x, coord.y, if (masked == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        for (bit in headerBits until headerBits + groupBits) write(bit, 1)
        // Both fixtures end at bit 24; damage five suffix bytes beyond conventional RS capacity.
        for (bit in 24 until 64) write(bit, ((extraction.codewords[bit / 8].value ushr (7 - bit % 8)) and 1) xor 1)
        return matrix to QrFormatConstraint(qr.ecLevel, qr.maskPattern)
    }

    @Test fun trustedSuffixAllowsRsToRepairOutOfRangeBodyValues() {
        for ((text, header, group) in listOf(Triple("123", 14, 10), Triple("AB", 13, 11))) {
            val (matrix, format) = damaged(text, header, group)
            assertTrue(RecoveryEngine().recover(matrix, formatConstraint = format).none { it.text == text })
            val result = RecoveryEngine().recover(matrix, formatConstraint = format, trustKnownPayloadCount = true)
                .first { it.text == text }
            assertTrue(result.constraintForcedByUserSetting)
            assertEquals(40, result.constraintFilledBits)
            assertTrue(result.knownBitMismatchesAfterCorrection > 40)
            assertTrue(RecoveryEngine().recover(matrix, formatConstraint = format, trustKnownPayloadCount = true,
                maxKnownDataEccMismatches = 40).none { it.text == text })
            val coord = CodewordExtractor.extract(matrix, 1, format.dataMask!!).codewords[3].coordinates[0]
            matrix[coord.x, coord.y].source = ModuleSource.USER
            assertTrue(RecoveryEngine().recover(matrix, formatConstraint = format, trustKnownPayloadCount = true)
                .none { it.text == text })
        }
    }
}
