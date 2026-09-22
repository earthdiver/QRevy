package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoverySearchDiagnosticTest {
    private val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 1))
    private val format = QrFormatConstraint(qr.ecLevel, qr.maskPattern)
    private fun matrix() = QrMatrix(qr.matrix.width).apply {
        for (y in 0 until dimension) for (x in 0 until dimension)
            set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
    }
    private fun flip(matrix: QrMatrix, coord: Coord, source: ModuleSource = ModuleSource.IMAGE) {
        matrix.set(coord.x, coord.y, if (matrix[coord.x, coord.y].value == ModuleValue.ONE)
            ModuleValue.ZERO else ModuleValue.ONE, 1f, source)
    }
    private fun eccError() = matrix().apply {
        flip(this, CodewordExtractor.extract(this, 1, qr.maskPattern).codewords.last().coordinates.first())
    }
    private fun unknownData() = matrix().apply {
        CodewordExtractor.extract(this, 1, qr.maskPattern).codewords.flatMap { it.coordinates }.forEach {
            set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
    }

    @Test fun zeroLimitProvesInputConflictEvenWhenReferenceRsSucceedsAndResetsOnNextRun() {
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(eccError(), maxKnownDataEccMismatches = 0, formatConstraint = format)
        assertTrue(outcome.candidates.isEmpty())
        val failure = outcome.failure!!
        assertTrue(failure.conventionalRsDiagnostic!!.succeeded)
        assertEquals(RecoveryInputBlocker.ZERO_MISMATCH, failure.searchDiagnostic!!.inputBlocker)
        assertTrue(failure.searchDiagnostic.candidateCalculations > 0)
        val candidate = engine.recover(matrix(), maxKnownDataEccMismatches = 0, formatConstraint = format).first()
        assertNull(candidate.searchDiagnostic!!.inputBlocker)
        assertTrue(candidate.searchDiagnostic.acceptedCandidate)
        assertEquals("", candidate.searchDiagnostic.failureExplanation(candidate.displayConditions))
    }

    @Test fun structuralPinStopsBeforeCandidateCalculationWhileDataPinRejectsComputedCandidates() {
        val engine = RecoveryEngine()
        val structure = matrix().apply { flip(this, Coord(0, 0), ModuleSource.USER) }
        val firstOutcome = engine.recoverWithDiagnostics(structure, formatConstraint = format)
        assertTrue(firstOutcome.candidates.isEmpty())
        assertEquals(RecoveryInputBlocker.STRUCTURAL_FIXED_CELLS, firstOutcome.searchDiagnostic.inputBlocker)
        assertEquals(0, firstOutcome.searchDiagnostic.candidateCalculations)
        assertNull(firstOutcome.failure!!.conventionalRsDiagnostic)

        val data = matrix().apply {
            flip(this, CodewordExtractor.extract(this, 1, qr.maskPattern).codewords.last().coordinates.first(), ModuleSource.USER)
        }
        val secondOutcome = engine.recoverWithDiagnostics(data, formatConstraint = format)
        assertTrue(secondOutcome.candidates.isEmpty())
        val summary = secondOutcome.searchDiagnostic
        assertNull(summary.inputBlocker)
        assertTrue(summary.candidateCalculations > 0)
        assertEquals(setOf(RecoveryCandidateRejection.FIXED_CELLS), summary.candidateRejections)
    }

    @Test fun conflictingFormatSelectionAndFormatLimitAreRecordedBeforeAnyRsCalculation() {
        val engine = RecoveryEngine()
        val input = matrix().apply { flip(this, Coord(0, 8), ModuleSource.USER) }
        val firstOutcome = engine.recoverWithDiagnostics(input, formatConstraint = format)
        assertTrue(firstOutcome.candidates.isEmpty())
        assertEquals(RecoveryInputBlocker.FORMAT_SELECTION, firstOutcome.searchDiagnostic.inputBlocker)
        assertEquals(0, firstOutcome.searchDiagnostic.candidateCalculations)
        input[0, 8].source = ModuleSource.IMAGE
        val secondOutcome = engine.recoverWithDiagnostics(input, maxFormatMismatches = 0)
        assertTrue(secondOutcome.candidates.isEmpty())
        assertEquals(RecoveryInputBlocker.FORMAT_LIMIT, secondOutcome.searchDiagnostic.inputBlocker)
        assertEquals(0, secondOutcome.searchDiagnostic.candidateCalculations)
    }

    @Test fun unexaminedFormatsAndLargeBitSpacesDoNotProveThatNoRsCandidateExists() {
        val engine = RecoveryEngine()
        val input = eccError()
        FormatEditPolicy.coordinates(1).forEach { input.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        val firstOutcome = engine.recoverWithDiagnostics(input, maxFormatCandidates = 1, maxKnownDataEccMismatches = 0)
        assertNull(firstOutcome.searchDiagnostic.inputBlocker)
        assertTrue(firstOutcome.searchDiagnostic.unsearched)
        val secondOutcome = engine.recoverWithDiagnostics(unknownData(), maxKnownDataEccMismatches = 0, formatConstraint = format)
        assertTrue(secondOutcome.candidates.isEmpty())
        assertNull(secondOutcome.searchDiagnostic.inputBlocker)
        assertTrue(secondOutcome.searchDiagnostic.unsearched)
        assertEquals("", secondOutcome.searchDiagnostic.failureExplanation(secondOutcome.failure!!.displayConditions))
    }

    @Test fun mixedOrMissingGeometryEvidenceCannotBecomeAUniversalFailureReason() {
        val engine = RecoveryEngine()
        val firstOutcome = engine.recoverWithDiagnostics(eccError(), maxKnownDataEccMismatches = 0, formatConstraint = format)
        val noExactCandidate = firstOutcome.searchDiagnostic
        val settings = firstOutcome.failure!!.displayConditions
        val secondOutcome = engine.recoverWithDiagnostics(unknownData(), maxKnownDataEccMismatches = 0, formatConstraint = format)
        val bounded = secondOutcome.searchDiagnostic
        val mixed = RecoverySearchDiagnostic.combine(listOf(noExactCandidate, bounded))!!
        assertNull(mixed.inputBlocker)
        assertTrue(mixed.unsearched)
        assertEquals("", mixed.failureExplanation(settings))
        val missing = RecoverySearchDiagnostic.combine(listOf(noExactCandidate, null))!!
        assertFalse(missing.coverageComplete)
        assertEquals("", missing.failureExplanation(settings))
        val thirdOutcome = engine.recoverWithDiagnostics(matrix(), maxKnownDataEccMismatches = 0, formatConstraint = format)
        val recovered = RecoverySearchDiagnostic.combine(listOf(noExactCandidate, thirdOutcome.searchDiagnostic))!!
        assertTrue(recovered.acceptedCandidate)
        assertEquals("", recovered.failureExplanation(settings))
    }
}
