package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryOutcomePublicationTest {
    private class Fixture {
        val text = "RECOVERY EVIDENCE"
        val qr = Encoder.encode(text, ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 2))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
        val format = QrFormatConstraint(qr.ecLevel, qr.maskPattern)
        val extraction = CodewordExtractor.extract(matrix, 2, qr.maskPattern)

        fun erasePartialWords() {
            // Exact constraints can recover the same QR with and without inferred padding.
            extraction.codewords.drop(2).forEach { word ->
                val coord = word.coordinates.last()
                matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }
    }

    @Test fun allRetainedRoutesShareFinalDiagnosticsAndCompareTheOriginalManualValues() {
        val fixture = Fixture()
        fixture.erasePartialWords()
        val ignored = fixture.extraction.codewords[0].coordinates[0]
        val fixed = fixture.extraction.codewords[1].coordinates[0]
        val external = fixture.extraction.codewords[2].coordinates[0]
        for (coord in listOf(ignored, fixed)) {
            fixture.matrix.set(coord.x, coord.y, fixture.matrix[coord.x, coord.y].value, 1f, ModuleSource.USER)
        }
        val opposite = if (fixture.matrix[external.x, external.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        val outcome = RecoveryEngine().recoverWithDiagnostics(fixture.matrix, formatConstraint = fixture.format,
            maxKnownDataEccMismatches = 0, manualEdits = mapOf(ignored to ModuleValue.ERASED, external to opposite),
            displayLockManualBits = false, listDecodingBudget = ListDecodingBudget(0))
        val candidate = outcome.candidates.single()
        assertEquals(fixture.text, candidate.text)
        val routes = listOf(candidate) + candidate.alternativeDerivations
        assertTrue(routes.any { it.constraintFilledBits == 0 })
        assertTrue(routes.any { it.constraintFilledBits > 0 })
        val conditions = RecoveryDisplayConditions(false, false, fixture.format, null, 0)
        for (route in routes) {
            assertSame(outcome.searchDiagnostic, route.searchDiagnostic)
            assertEquals(conditions, route.displayConditions)
            assertEquals(FormatSelection(true, true), route.formatSelection)
            assertEquals(2, route.manualDataEccCompared)
            assertEquals(1, route.manualDataEccMismatches)
            assertArrayEquals(fixture.extraction.codewords.map { it.value }.toIntArray(), route.correctedInterleaved)
            assertEquals(0, route.knownBitMismatchesAfterCorrection)
            assertTrue(route.repairInfo.any { it.originalUnknownMask != 0 })
        }
        outcome.failure?.let { failure ->
            assertSame(outcome.searchDiagnostic, failure.searchDiagnostic)
            assertEquals(conditions, failure.displayConditions)
            assertEquals(candidate.combinationSearch, failure.combinationSearch)
        }
        assertEquals(RecoveryDiscoveryCount.Exact(1), outcome.discoveredCandidates)
        assertTrue(outcome.searchDiagnostic.listDecodingExecution!!.stages.values.all {
            it == setOf(ListDecodingStatus.ZERO_ERROR_LIMIT)
        })
        assertEquals(ModuleSource.USER, fixture.matrix[fixed.x, fixed.y].source)
        val erased = fixture.extraction.codewords[2].coordinates.last()
        assertEquals(ModuleValue.ERASED, fixture.matrix[erased.x, erased.y].value)
    }

    @Test fun aFixedStructureConflictPublishesFailureWithTheSameConditionsAndDiagnostic() {
        val fixture = Fixture()
        fixture.matrix.set(0, 0, ModuleValue.ZERO, 1f, ModuleSource.USER)
        val outcome = RecoveryEngine().recoverWithDiagnostics(fixture.matrix, formatConstraint = fixture.format,
            maxFormatMismatches = 0, maxKnownDataEccMismatches = 2, displayLockManualBits = true)
        assertTrue(outcome.candidates.isEmpty())
        val failure = requireNotNull(outcome.failure)
        assertSame(outcome.searchDiagnostic, failure.searchDiagnostic)
        assertEquals(RecoveryDisplayConditions(false, true, fixture.format, 0, 2), failure.displayConditions)
        assertEquals(RecoveryInputBlocker.STRUCTURAL_FIXED_CELLS, outcome.searchDiagnostic.inputBlocker)
        assertTrue(outcome.searchDiagnostic.listDecodingExecution!!.stages.values.all {
            it == setOf(ListDecodingStatus.INPUT_CONSTRAINT)
        })
        assertEquals(RecoveryDiscoveryCount.Exact(0), outcome.discoveredCandidates)
    }
}
