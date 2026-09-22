package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class ManualDataPinDiagnosticTest {
    private fun fixture(): Pair<QrMatrix, QrFormatConstraint> {
        val qr = Encoder.encode("RECOVERY EVIDENCE", ErrorCorrectionLevel.H,
            mapOf(EncodeHintType.QR_VERSION to 2))
        return QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        } to QrFormatConstraint(qr.ecLevel, qr.maskPattern)
    }

    @Test fun aWrongManualDataPinHasAnActionableDiagnostic() {
        val (matrix, format) = fixture()
        val coord = CodewordExtractor.extract(matrix, 2, format.dataMask!!).codewords[4].coordinates[0]
        val expected = matrix[coord.x, coord.y].value
        val wrong = if (expected == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        matrix.set(coord.x, coord.y, wrong, 1f, ModuleSource.USER)
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(matrix, formatConstraint = format)
        assertTrue(outcome.candidates.isEmpty())
        assertEquals(RecoveryFailureReason.MANUAL_PIN_CONFLICT, outcome.failure?.reason)
        assertEquals(listOf(ManualCellConflict(coord, wrong, expected)), outcome.failure?.manualConflicts)
        assertEquals(wrong, matrix[coord.x, coord.y].value)
    }


}
