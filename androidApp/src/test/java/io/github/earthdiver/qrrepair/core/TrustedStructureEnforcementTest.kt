package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class TrustedStructureEnforcementTest {
    @Test fun rsCannotOverturnTheForcedHeaderOrSuffix() {
        val qr = Encoder.encode("abc", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(21).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        // Byte count 3 -> 2. The forced terminator then overlaps the third character.
        val coord = extraction.codewords[1].coordinates[3]
        matrix.set(coord.x, coord.y, if (matrix[coord.x, coord.y].value == ModuleValue.ONE)
            ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.IMAGE)
        val erased = extraction.codewords[3].coordinates[5]
        matrix.set(erased.x, erased.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        val format = QrFormatConstraint(qr.ecLevel, qr.maskPattern)
        val forced = RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format,
            trustKnownPayloadCount = true)
        assertTrue(forced.candidates.isEmpty())
        assertEquals(5, forced.formatAttempts) // Every recovery stage obeys the same requested structure.
        assertNotNull(forced.searchDiagnostic.listDecoding)
        assertTrue(RecoveryCandidateRejection.SINGLE_SEGMENT_STRUCTURE in forced.searchDiagnostic.candidateRejections)
        val ordinary = RecoveryEngine().recover(matrix, formatConstraint = format)
        assertTrue(ordinary.any { it.text == "abc" })
    }
}
