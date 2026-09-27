package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryCompletenessTest {
    @Test fun exactAndConventionalRecoveryBothCompletePolicy() {
        val qr = Encoder.encode("abc", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(21).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
        }
        val format = QrFormatConstraint(qr.ecLevel, qr.maskPattern)
        for (force in listOf(false, true)) {
            val exact = RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format,
                trustKnownPayloadCount = force, maxKnownDataEccMismatches = 0)
            assertTrue(exact.candidates.any { it.text == "abc" })
            assertEquals(SearchCompleteness.COMPLETE, exact.searchDiagnostic.completeness)
            val ordinary = RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format,
                trustKnownPayloadCount = force)
            assertTrue(ordinary.candidates.any { it.text == "abc" })
            assertEquals(SearchCompleteness.COMPLETE, ordinary.searchDiagnostic.completeness)
            assertFalse(ordinary.searchDiagnostic.unsearched)
        }
    }

    @Test fun ordinarySuccessNormallyBypassesPriorityAssignments() {
        val qr = Encoder.encode("abc", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(21).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
        }
        val cell = CodewordExtractor.extract(matrix, 1, qr.maskPattern).codewords[2].coordinates[0]
        matrix.set(cell.x, cell.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        matrix.setPrioritySearch(cell.x, cell.y, true)
        val outcome = RecoveryEngine().recoverWithDiagnostics(matrix,
            formatConstraint = QrFormatConstraint(qr.ecLevel, qr.maskPattern))
        assertTrue(outcome.candidates.any { it.text == "abc" })
        assertEquals(SearchCompleteness.COMPLETE, outcome.searchDiagnostic.completeness)
        assertFalse(outcome.searchDiagnostic.unsearched)
    }

    @Test fun retentionOmissionDoesNotChangeCompleteness() {
        val complete = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE)
        assertEquals(SearchCompleteness.COMPLETE, complete.copy(candidatesOmitted = true).completeness)
        assertEquals(SearchCompleteness.COMPLETE, complete.copy(derivationsOmitted = true).completeness)
        assertEquals(SearchCompleteness.UNKNOWN, RecoverySearchDiagnostic.combine(listOf(complete, null))?.completeness)
        assertEquals(SearchCompleteness.UNSEARCHED,
            RecoverySearchDiagnostic.combine(listOf(complete, complete.withUnsearched()))?.completeness)
    }
    @Test fun provisionalFormatSuccessCompletesWithoutTryingOtherFormats() {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(21).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
        }
        FormatSolver.copies(21).forEachIndexed { index, copy ->
            val (x, y) = copy[index]
            matrix.set(x, y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        assertNull(FormatSolver.trustedCandidate(matrix))
        assertNotNull(FormatSolver.matchingInformationCandidate(matrix))
        val outcome = RecoveryEngine().recoverWithDiagnostics(matrix)
        assertEquals(1, outcome.formatAttempts)
        assertTrue(outcome.candidates.any { it.text == "HELLO" })
        assertEquals(SearchCompleteness.COMPLETE, outcome.searchDiagnostic.completeness)
    }

    @Test fun failureBeforeAnySearchWorkHasAnExplicitInternalErrorReason() {
        val outcome = RecoveryEngine().recoverWithDiagnostics(QrMatrix(21),
            stopRequested = { error("Injected processing failure") })
        assertEquals(SearchCompleteness.UNSEARCHED, outcome.searchDiagnostic.completeness)
        assertEquals(setOf(SearchIncompleteReason.INTERNAL_ERROR), outcome.searchDiagnostic.incompleteReasons)
        assertEquals(0, outcome.searchDiagnostic.completedSteps)
        assertTrue(outcome.candidates.isEmpty())
    }

    @Test fun reasonAggregationDoesNotDependOnCandidateRetentionOrSelection() {
        val complete = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE, completedSteps = 1)
        val interrupted = RecoverySearchDiagnostic(incompleteReasons = setOf(SearchIncompleteReason.USER_INTERRUPTED))
        val error = RecoverySearchDiagnostic(incompleteReasons = setOf(SearchIncompleteReason.INTERNAL_ERROR))
        val summary = RecoverySearchDiagnostic.combine(listOf(complete, interrupted, error))!!
        assertEquals(SearchCompleteness.UNSEARCHED, summary.completeness)
        assertEquals(setOf(SearchIncompleteReason.USER_INTERRUPTED, SearchIncompleteReason.INTERNAL_ERROR), summary.incompleteReasons)
        assertEquals(1, summary.completedSteps)
        assertEquals(summary.completeness, summary.copy(candidatesOmitted = true).completeness)
    }

}
