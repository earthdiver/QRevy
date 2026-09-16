package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryDisplayConditionsTest {
    @Test fun conditionsAndManualComparisonsAreCapturedForEveryOptionCombination() {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) {
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
            }
        }
        val coord = CodewordExtractor.extract(matrix, 1, qr.maskPattern).codewords[2].coordinates[0]
        val input = matrix[coord.x, coord.y].value
        val engine = RecoveryEngine()
        val retained = mutableListOf<RecoveryCandidate>()
        for (trust in listOf(false, true)) for (lock in listOf(false, true)) for (limit in listOf(0, 2, null)) {
            val format = QrFormatConstraint(ErrorCorrectionLevel.L, qr.maskPattern)
            val candidate = engine.recover(matrix, trustKnownPayloadCount = trust,
                formatConstraint = format, maxFormatMismatches = 0, maxKnownDataEccMismatches = limit,
                manualEdits = mapOf(coord to input), displayLockManualBits = lock).first { it.text == "HELLO" }
            assertEquals(RecoveryDisplayConditions(trust, lock, format, 0, limit), candidate.displayConditions)
            assertEquals(FormatSelection(true, true), candidate.formatSelection)
            assertEquals(FormatSelectionReason.USER, candidate.formatReasonAt(8, 8))
            assertEquals(0, candidate.manualDataEccMismatches)
            assertEquals(1, candidate.manualDataEccCompared)
            retained += candidate
        }
        assertEquals(12, retained.size)
        assertEquals(0, retained.first().displayConditions!!.dataEccLimit)
        assertFalse(retained.first().displayConditions!!.trustSingleSegment)
        assertNull(retained.last().displayConditions!!.dataEccLimit)
    }

    @Test fun unlockedManualMismatchIsComparedWithOriginalInput() {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) {
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
            }
        }
        val coord = CodewordExtractor.extract(matrix, 1, qr.maskPattern).codewords[2].coordinates[0]
        val wrong = if (matrix[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        matrix.set(coord.x, coord.y, wrong, 1f, ModuleSource.IMAGE)
        val result = RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, qr.maskPattern),
            maxKnownDataEccMismatches = 2, manualEdits = mapOf(coord to wrong), displayLockManualBits = false)
            .first { it.text == "HELLO" }
        assertEquals(1, result.manualDataEccMismatches)
        assertEquals(1, result.knownBitMismatchesAfterCorrection)
    }
}
