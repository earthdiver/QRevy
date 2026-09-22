package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RsBitRecoveryIntegrationTest {
    private val text = "abcdefghijklm"
    private val encoded = Encoder.encode(text, ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to 1))
    private fun matrix() = QrMatrix(21).apply {
        for (y in 0 until 21) for (x in 0 until 21) set(x, y,
            if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
            1f, ModuleSource.IMAGE)
    }
    private fun erase(matrix: QrMatrix, coordinates: List<Coord>) {
        coordinates.forEach { matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.USER) }
    }

    @Test fun recoversTwentyDispersedBitsAndPreservesInputAndProvenance() {
        val matrix = matrix()
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)
        val erased = extraction.codewords.take(20).mapIndexed { i, cw -> cw.coordinates[7 - (i % 8)] }
        erase(matrix, erased)
        val candidates = RecoveryEngine().recover(matrix, maxKnownDataEccMismatches = 0)
        val correct = candidates.first { it.text == text }
        assertEquals(0, correct.knownBitMismatchesAfterCorrection)
        assertEquals(0, correct.rsBitDiagnostic?.dimension)
        assertEquals(20, correct.rsBitDiagnostic?.fixedBits)
        assertTrue(correct.repairInfo.any { it.bitConstraintSolved })
        val after = RepairTraceBuilder.candidateMatrix(matrix, correct, RecoveryEngine())
        val traces = RepairTraceBuilder.build(matrix, after, correct)
        assertTrue(erased.all { matrix[it.x, it.y].value == ModuleValue.ERASED })
        assertTrue(erased.all { after[it.x, it.y].source != ModuleSource.USER })
        assertTrue(traces.values.any { it.bitConstraintSolved })
        assertTrue(RecoveryEngine().recover(matrix).any { it.text == text })
    }

    @Test fun retainsAllSixteenPayloadsWithFourFreeDataBitsAndAllEccErased() {
        val matrix = matrix()
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)
        erase(matrix, extraction.codewords.drop(19).flatMap { it.coordinates } + extraction.codewords[5].coordinates.takeLast(4))
        val candidates = RecoveryEngine().recover(matrix, maxKnownDataEccMismatches = 0)
        assertEquals(16, candidates.map { it.dataBytes.toList() }.distinct().size)
        assertTrue(candidates.any { it.text == text })
        assertTrue(candidates.all { it.payloadParsed && it.knownBitMismatchesAfterCorrection == 0 })
        assertEquals(4, candidates.first().rsBitDiagnostic?.dimension)
    }

    @Test fun symbolicSpaceOffersConditionalNonFinderCellEvenWithoutDecodedCandidates() {
        val matrix = matrix()
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)
        erase(matrix, extraction.codewords.flatMap { it.coordinates })
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(matrix, maxKnownDataEccMismatches = 0)
        assertTrue(outcome.candidates.isEmpty())
        val diagnostic = outcome.failure!!.rsBitDiagnostic!!
        assertEquals(152, diagnostic.dimension)
        assertEquals(152, diagnostic.independentCells.size)
        val rec = RepairGuidance.recommendFromBitSpace(matrix, diagnostic)!!
        assertFalse(QrFunctionMask.isFunction(1, rec.coord.x, rec.coord.y))
        assertEquals(152, rec.exactSpaceDimension)
        assertEquals(0, rec.totalCandidates) // Never pretend these are validated payloads.
        assertTrue(rec.reasonJa.contains("仮定"))
        assertTrue(rec.reasonEn.contains("assuming"))
    }

    @Test fun reusedEngineDoesNotRetainFailureOrBitDiagnosticsFromPreviousInput() {
        val damaged = matrix()
        val extraction = CodewordExtractor.extract(damaged, 1, encoded.maskPattern)
        erase(damaged, extraction.codewords.flatMap { it.coordinates })
        val engine = RecoveryEngine()
        val firstOutcome = engine.recoverWithDiagnostics(damaged, maxKnownDataEccMismatches = 0)
        assertTrue(firstOutcome.candidates.isEmpty())
        val expectedFailure = requireNotNull(firstOutcome.failure)
        val expectedAttempts = firstOutcome.formatAttempts
        assertEquals(208, expectedFailure.rsBitDiagnostic?.unknownBits)

        val secondOutcome = engine.recoverWithDiagnostics(matrix(), maxKnownDataEccMismatches = 0)
        val recovered = secondOutcome.candidates.first { it.text == text }
        assertNull(secondOutcome.failure)
        assertSame(expectedFailure, firstOutcome.failure)
        assertSame(firstOutcome.searchDiagnostic, expectedFailure.searchDiagnostic)
        assertEquals(0, recovered.rsBitDiagnostic?.unknownBits)
        assertEquals(0, recovered.rsBitDiagnostic?.dimension)

        val thirdOutcome = engine.recoverWithDiagnostics(damaged, maxKnownDataEccMismatches = 0)
        assertTrue(thirdOutcome.candidates.isEmpty())
        assertEquals(expectedFailure, thirdOutcome.failure)
        assertEquals(expectedAttempts, thirdOutcome.formatAttempts)
    }

    @Test fun erroneousKnownEccRemainsCorrectableButManualPinIsAbsolute() {
        val matrix = matrix()
        val coord = CodewordExtractor.extract(matrix, 1, encoded.maskPattern).codewords.last().coordinates[0]
        val wrong = if (matrix[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        matrix.set(coord.x, coord.y, wrong, 1f, ModuleSource.IMAGE)
        assertTrue(RecoveryEngine().recover(matrix, maxKnownDataEccMismatches = 1).any { it.text == text })
        val exactEngine = RecoveryEngine()
        val outcome = exactEngine.recoverWithDiagnostics(matrix, maxKnownDataEccMismatches = 0)
        assertTrue(outcome.candidates.isEmpty())
        assertNull(RepairGuidance.recommendFromBitSpace(matrix, outcome.failure?.rsBitDiagnostic))
        matrix.set(coord.x, coord.y, wrong, 1f, ModuleSource.USER)
        assertTrue(RecoveryEngine().recover(matrix).none { it.text == text })
    }

    @Test fun rsSuccessWithInvalidPayloadStillProducesNoRecovery() {
        val matrix = matrix()
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)
        val data = IntArray(19) { 255 } // Reserved/invalid QR mode.
        val raw = ReedSolomonErasures().encode(data, 7)
        extraction.codewords.forEachIndexed { i, cw ->
            cw.coordinates.forEachIndexed { bit, c ->
                val original = (cw.value shr (7 - bit)) and 1
                val wanted = (raw[i] shr (7 - bit)) and 1
                if (original != wanted) {
                    matrix.set(c.x, c.y, if (matrix[c.x, c.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
                        1f, ModuleSource.IMAGE)
                }
            }
        }
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(matrix, maxKnownDataEccMismatches = 0)
        assertTrue(outcome.candidates.isEmpty())
        assertEquals(0, outcome.failure?.rsBitDiagnostic?.dimension)
        assertEquals(RecoveryFailureStage.PAYLOAD_VALIDATION, outcome.failure?.stage)
    }
    @Test fun blockAlternativesReachCellExplanationsWithoutChangingTheRepresentative() {
        val matrix = matrix()
        val coord = CodewordExtractor.extract(matrix, 1, encoded.maskPattern).codewords[5].coordinates[7]
        erase(matrix, listOf(coord))
        val candidate = RecoveryEngine().recover(matrix).single()
        val derivations = candidate.blockDerivations.getValue(0)
        assertFalse(derivations.first().bitConstraintSolved)
        assertTrue(derivations.any { it.bitConstraintSolved })
        assertTrue(candidate.minRsMargin > 0)
        val after = RepairTraceBuilder.candidateMatrix(matrix, candidate, RecoveryEngine())
        val trace = RepairTraceBuilder.build(matrix, after, candidate).getValue(coord)
        assertFalse(trace.bitConstraintSolved)
        assertTrue(trace.alternativeRsDerivations.any { it.bitConstraintSolved })
    }
}
