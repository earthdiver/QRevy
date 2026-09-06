package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class OverturnedConstraintProvenanceTest {
    @Test fun rsCanOverturnAForcedStructureBit() {
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
        val original = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        val de = QrBlockInterleaver.deinterleave(original, 1, qr.ecLevel)
        val constraints = PayloadConstraintSolver.apply(de, 1, trustKnownCount = true)
        val candidates = RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(qr.ecLevel, qr.maskPattern), trustKnownPayloadCount = true)
        val routes = candidates.flatMap { listOf(it) + it.alternativeDerivations }
        var overturned = 0
        var tracedOverturned = 0
        for (candidate in routes.filter { it.constraintFilledBits > 0 }) {
            val after = RepairTraceBuilder.candidateMatrix(matrix, candidate, RecoveryEngine())
            val traces = RepairTraceBuilder.build(matrix, after, candidate)
            for (filled in constraints.filled) {
                val raw = de.rawLocations.indexOfFirst { it?.blockIndex == filled.blockIndex && it.localIndex == filled.localByteIndex }
                if (((candidate.correctedInterleaved[raw] shr (7 - filled.bitFromMsb)) and 1) == filled.value) continue
                overturned++
                val cell = original.codewords[raw].coordinates[filled.bitFromMsb]
                val trace = traces[cell] ?: continue
                tracedOverturned++
                assertNotNull(trace.overturnedConstraint)
                assertEquals(filled.value, trace.overturnedConstraintValue)
                assertFalse(trace.supportingReasons.any { it in ModuleRepairTrace.PAYLOAD_REASONS })
                assertFalse("Overturned constraint at $cell", trace.primaryReason in ModuleRepairTrace.PAYLOAD_REASONS)
            }
        }
        assertTrue("Fixture must exercise an overturned constraint", overturned > 0)
        assertTrue("The erased cell must expose the corrected provenance", tracedOverturned > 0)
        assertEquals("abc", candidates.single().text)
    }
}
