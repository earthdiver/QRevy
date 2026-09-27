package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class PartialBitSearchRecoveryTest {
    private fun ambiguousInput(bits: Int): Pair<QrMatrix, QrFormatConstraint> {
        val qr = Encoder.encode("abcdefghijklmnop", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        assertEquals(21, matrix.dimension)
        val extraction = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        extraction.codewords.forEachIndexed { index, word ->
            val count = when {
                index >= 19 -> 8
                index in 3..5 -> 4
                index == 6 -> bits - 12
                else -> 0
            }
            word.coordinates.takeLast(count).forEach {
                matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }
        return matrix to QrFormatConstraint(qr.ecLevel, qr.maskPattern)
    }

    @Test fun selectedLimitReachesQrValidationAndIsRecordedOnCandidates() {
        for (limit in 13..14) {
            val (matrix, format) = ambiguousInput(limit)
            val engine = RecoveryEngine()
            val lower = engine.recoverWithDiagnostics(matrix, formatConstraint = format,
                maxKnownDataEccMismatches = 0, partialBitSearchLimit = limit - 1)
            assertTrue(lower.candidates.isEmpty())
            val raised = engine.recoverWithDiagnostics(matrix, formatConstraint = format,
                maxKnownDataEccMismatches = 0, partialBitSearchLimit = limit,
                retentionBudget = RecoveryRetentionBudget(maxCandidates = 4, workingLimitBytes = 8L * 1024 * 1024))
            assertTrue(raised.candidates.any { it.payloadParsed })
            assertTrue(raised.candidates.all { it.knownBitMismatchesAfterCorrection == 0 })
            assertTrue(raised.candidates.all { it.displayConditions?.partialBitSearchLimit == limit })
        }
    }

    @Test fun raisedLimitUsesTheWorkingBudgetBeforeMaterializingCandidates() {
        val (matrix, format) = ambiguousInput(14)
        val outcome = RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format,
            maxKnownDataEccMismatches = 0, partialBitSearchLimit = 14,
            retentionBudget = RecoveryRetentionBudget(workingLimitBytes = 2L * 1024 * 1024))
        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.searchDiagnostic.unsearched)
        assertTrue(CombinationSearchStop.WORKING_MEMORY_LIMIT in outcome.failure!!.combinationSearch.stops)
    }
}
