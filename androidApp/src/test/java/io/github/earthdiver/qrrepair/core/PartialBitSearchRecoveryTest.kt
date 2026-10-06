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

    @Test fun unknownModeAndCountAreCheckedTogetherWithRsBeforePayloadEnumeration() {
        val qr = Encoder.encode("abcdefghijklmnop", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        extraction.codewords.forEachIndexed { index, word ->
            word.coordinates.forEachIndexed { bit, coord ->
                val position = index * 8 + bit
                if (index >= 19 || position < 20) matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }
        val observed = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        val blocks = QrBlockInterleaver.deinterleave(observed, 1, qr.ecLevel)
        assertEquals(20, RsBitConstraintSolver().analyze(blocks.blocks.single(), blocks.ecSymbolsPerBlock)!!.dimension)
        val outcome = RecoveryEngine().recoverWithDiagnostics(matrix,
            formatConstraint = QrFormatConstraint(qr.ecLevel, qr.maskPattern), maxKnownDataEccMismatches = 0,
            retentionBudget = RecoveryRetentionBudget(maxCandidates = 512, workingLimitBytes = 2L * 1024 * 1024))
        assertTrue(outcome.candidates.any { it.text == "abcdefghijklmnop" })
        assertTrue(outcome.candidates.all { it.knownBitMismatchesAfterCorrection == 0 })
        assertTrue(outcome.candidates.all { candidate -> candidate.actualBlockMethods.any { it.bitConstraintSolved } })
        assertEquals(20 + 7 * 8, observed.codewords.sumOf { it.unknownBits.countOneBits() })
        assertEquals(observed, CodewordExtractor.extract(matrix, 1, qr.maskPattern))
    }

    @Test fun raisedLimitStreamsCandidatesWithinASmallWorkingBudget() {
        val (matrix, format) = ambiguousInput(14)
        val outcome = RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format,
            maxKnownDataEccMismatches = 0, partialBitSearchLimit = 14,
            retentionBudget = RecoveryRetentionBudget(workingLimitBytes = 2L * 1024 * 1024))
        assertTrue(outcome.candidates.any { it.payloadParsed })
        assertTrue(outcome.candidates.all { CombinationSearchStop.WORKING_MEMORY_LIMIT !in it.combinationSearch.stops })
    }
}
