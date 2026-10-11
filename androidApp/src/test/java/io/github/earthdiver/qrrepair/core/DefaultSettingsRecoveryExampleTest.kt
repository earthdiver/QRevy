package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

/** Independently encoded counterpart of the public synthetic PNG/TEXT example. */
class DefaultSettingsRecoveryExampleTest {
    private val text = "QRevy repaired."

    private class Fixture(val matrix: QrMatrix, val original: IntArray, val priority: Coord)

    private fun fixture(): Fixture {
        val qr = Encoder.encode(text, ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 0))
        val matrix = QrMatrix(qr.matrix.width)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            matrix.set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        fun erase(coord: Coord) = matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        // Erase the 20-bit suffix and four complete ECC words. The remaining known
        // mode/count bits need one explicit priority assignment before forcing the suffix.
        for (bit in 132 until 152) erase(extraction.codewords[bit / 8].coordinates[bit % 8])
        for (word in 19..22) extraction.codewords[word].coordinates.forEach(::erase)
        val priority = extraction.codewords[0].coordinates[1]
        erase(priority)
        matrix.setPrioritySearch(priority.x, priority.y, true)
        for (word in listOf(2, 4)) {
            val coord = extraction.codewords[word].coordinates[7]
            matrix.set(coord.x, coord.y,
                if (matrix[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
                1f, ModuleSource.IMAGE)
        }
        return Fixture(matrix, extraction.codewords.map { it.value }.toIntArray(), priority)
    }

    private fun recover(f: Fixture, single: Boolean, lists: Boolean = true): RecoveryOutcome {
        val defaults = PreprocessSettings().withInitialErrorLimits()
        assertEquals(4, defaults.prioritySearchLimit)
        assertEquals(12, defaults.partialBitSearchLimit)
        assertEquals(1, defaults.listDecodingAdditionalErrors)
        assertEquals(2, defaults.effectiveDataEccMismatchLimit)
        return RecoveryEngine().recoverWithDiagnostics(f.matrix,
            trustKnownPayloadCount = single,
            formatConstraint = defaults.formatConstraint,
            maxFormatMismatches = defaults.effectiveFormatMismatchLimit,
            maxKnownDataEccMismatches = defaults.effectiveDataEccMismatchLimit,
            partialBitSearchLimit = defaults.partialBitSearchLimit,
            listDecodingAdditionalErrors = defaults.listDecodingAdditionalErrors,
            listDecodingBudget = if (lists) ListDecodingBudget() else ListDecodingBudget(0))
    }

    @Test fun defaultSettingsRecoverTheOriginalThroughPriorityHeaderAndListDecoding() {
        val f = fixture()
        val outcome = recover(f, single = true)
        val candidate = outcome.candidates.single()
        assertEquals(text, candidate.text)
        assertArrayEquals(f.original, candidate.correctedInterleaved)
        assertEquals(2, candidate.knownBitMismatchesAfterCorrection)
        assertEquals(20, candidate.constraintFilledBits)
        assertEquals(setOf(f.priority), candidate.priorityAssignments.keys)
        assertEquals(SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER, candidate.singleSegmentStatus)
        assertTrue(candidate.constraintForcedByUserSetting)
        val route = requireNotNull(candidate.actualBlockMethods.single().listDecoding)
        assertEquals(ListDecoderMethod.WU, route.method)
        assertEquals(2, route.radius)
        assertEquals(4, route.remainingErasures)
        assertEquals(2, route.symbolErrors)
        assertTrue(outcome.searchDiagnostic.coverageComplete)
        assertFalse(outcome.searchDiagnostic.unsearched)
        assertFalse(outcome.searchDiagnostic.candidatesOmitted)
        assertFalse(outcome.searchDiagnostic.derivationsOmitted)
        assertTrue(outcome.searchDiagnostic.incompleteReasons.isEmpty())
    }

    @Test fun disablingTheSingleSegmentAssumptionReturnsNoCandidates() {
        val outcome = recover(fixture(), single = false)
        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.searchDiagnostic.coverageComplete)
        assertFalse(outcome.searchDiagnostic.unsearched)
        assertTrue(outcome.searchDiagnostic.incompleteReasons.isEmpty())
    }

    @Test fun ordinaryStagesReturnNoCandidatesWhenListStagesAreDeliberatelyDisabled() {
        val outcome = recover(fixture(), single = true, lists = false)
        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.searchDiagnostic.coverageComplete)
        assertEquals(0, requireNotNull(outcome.searchDiagnostic.listDecoding).trials)
        // Preserve the explicit resource stop; this control does not complete list decoding.
        assertEquals(setOf(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT),
            outcome.searchDiagnostic.incompleteReasons)
    }

    @Test fun removingThePriorityMarkLeavesTheSingleSegmentHeaderUnavailable() {
        val f = fixture()
        f.matrix.setPrioritySearch(f.priority.x, f.priority.y, false)
        val outcome = recover(f, single = true)
        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.searchDiagnostic.coverageComplete)
        assertFalse(outcome.searchDiagnostic.unsearched)
        assertTrue(outcome.searchDiagnostic.incompleteReasons.isEmpty())
        assertEquals(setOf(SingleSegmentStatus.INCOMPLETE_HEADER), outcome.searchDiagnostic.singleSegmentStatuses)
    }
}
