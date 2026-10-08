package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class ManualFormatRecoveryTest {
    private fun fixture(version: Int = 2): Pair<QrMatrix, QrFormatConstraint> {
        val qr = Encoder.encode("FORMAT-LOCK", ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to version))
        val matrix = QrMatrix(qr.matrix.width)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) matrix.set(x, y,
            if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        return matrix to QrFormatConstraint(qr.ecLevel, qr.maskPattern)
    }
    private fun opposite(value: ModuleValue) = if (value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE

    @Test fun automaticBchConfirmationRespectsTheManualValueOption() {
        // Intentionally contradictory evidence: mask-2 data, complete mask-0 format,
        // and a remainder value compatible only with the former of those two masks.
        val qr = Encoder.encode("BCH-MANUAL-PRIORITY", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 2, EncodeHintType.QR_MASK_PATTERN to 2))
        val original = QrMatrix(qr.matrix.width)
        for (y in 0 until original.dimension) for (x in 0 until original.dimension) {
            original.set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
        val codewordCoords = CodewordExtractor.extract(original, 2, 2).codewords.flatMapTo(hashSetOf()) { it.coordinates }
        val pin = (0 until original.dimension).flatMap { y ->
            (0 until original.dimension).map { x -> Coord(x, y) }
        }.first { !QrFunctionMask.isFunction(2, it.x, it.y) && it !in codewordCoords &&
            CodewordExtractor.maskApplies(0, it.x, it.y) != CodewordExtractor.maskApplies(2, it.x, it.y) }
        val formatZero = QrMatrix(original.dimension).also { StructuralRepair.apply(it, ErrorCorrectionLevel.L, 0) }
        for (c in FormatEditPolicy.coordinates(2)) {
            original.set(c.x, c.y, formatZero[c.x, c.y].value, 1f, ModuleSource.IMAGE)
        }
        val edits = mapOf(pin to original[pin.x, pin.y].value)
        for ((locked, explicitMask) in listOf(true to false, false to false, true to true)) {
            val settings = PreprocessSettings(lockManualBits = locked, preRecoveryEdits = edits)
            val matrix = original.copy().apply {
                set(pin.x, pin.y, edits.getValue(pin), 1f, settings.sourceForPreRecoveryEdit(edits.getValue(pin)))
            }
            val before = matrix.copy()
            assertEquals(0, FormatSolver.trustedCandidate(matrix)?.dataMask)
            val engine = RecoveryEngine()
            val outcome = engine.recoverWithDiagnostics(matrix, manualEdits = settings.preRecoveryEdits,
                formatConstraint = if (explicitMask) QrFormatConstraint(dataMask = 0) else null,
                maxKnownDataEccMismatches = 0)
            val results = outcome.candidates
            if (locked && !explicitMask) {
                assertTrue(results.any { it.dataMask == 2 && it.text == "BCH-MANUAL-PRIORITY" })
                assertTrue(results.all { candidate ->
                    RepairTraceBuilder.candidateMatrix(matrix, candidate, engine)[pin.x, pin.y].value == edits.getValue(pin)
                })
            } else {
                assertTrue(results.isEmpty())
                if (explicitMask) assertEquals(RecoveryFailureReason.MANUAL_PIN_CONFLICT, outcome.failure?.reason)
                else assertEquals(2, outcome.formatAttempts) // Original RS and exact constraints keep the trusted format.
            }
            assertEquals(edits, settings.preRecoveryEdits)
            for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
                assertEquals(before[x, y], matrix[x, y])
            }
        }
    }

    @Test fun selectionDeterminedManualBitsAreExcludedEvenWithLockOff() {
        for (locked in listOf(true, false)) {
            val (matrix, full) = fixture()
            val constraint = QrFormatConstraint(ecLevel = full.ecLevel)
            val fixed = FormatEditPolicy.fixedValues(2, constraint)
            val coord = fixed.keys.first()
            val value = fixed.getValue(coord)
            matrix.set(coord.x, coord.y, value, 1f, if (locked) ModuleSource.USER else ModuleSource.IMAGE)
            val candidate = RecoveryEngine().recover(matrix, formatConstraint = constraint,
                maxFormatMismatches = 0, maxKnownDataEccMismatches = 0, manualEdits = mapOf(coord to value))
                .first { it.text == "FORMAT-LOCK" }
            assertEquals(29, candidate.formatMatches)
            assertEquals(0, candidate.formatMismatches)
            assertEquals(CodewordExtractor.extract(matrix, 2, full.dataMask!!).codewords.size * 8,
                candidate.knownBitsCompared)
        }
    }

    @Test fun manualBitsNotDeterminedBySelectionsRemainComparedAndRespectLocking() {
        val (original, full) = fixture()
        for (constraint in listOf(null, QrFormatConstraint(ecLevel = full.ecLevel),
            QrFormatConstraint(dataMask = full.dataMask))) {
            val coord = FormatEditPolicy.coordinates(2).first { it !in FormatEditPolicy.fixedValues(2, constraint) }
            val wrong = opposite(original[coord.x, coord.y].value)
            val edits = mapOf(coord to wrong)
            assertNull(FormatEditPolicy.conflict(2, constraint, edits))
            assertTrue(FormatEditPolicy.ignoredComparisonCoords(2, constraint, edits).isEmpty())
            for (locked in listOf(true, false)) {
                val matrix = original.copy().apply {
                    set(coord.x, coord.y, wrong, 1f, if (locked) ModuleSource.USER else ModuleSource.IMAGE)
                }
                val results = RecoveryEngine().recover(matrix, formatConstraint = constraint,
                    maxFormatMismatches = 1, maxKnownDataEccMismatches = 0, manualEdits = edits)
                if (locked) assertTrue(results.none { it.text == "FORMAT-LOCK" })
                else {
                    val candidate = results.first { it.text == "FORMAT-LOCK" }
                    assertEquals(29, candidate.formatMatches)
                    assertEquals(1, candidate.formatMismatches)
                    assertEquals(0, candidate.knownBitMismatchesAfterCorrection)
                }
            }
        }
    }

    @Test fun explicitSelectionConflictBlocksBothLockedAndUnlockedLegacyInputs() {
        for (locked in listOf(true, false)) {
            val (matrix, selected) = fixture()
            val coord = Coord(0, 8)
            val wrong = opposite(matrix[coord.x, coord.y].value)
            matrix.set(coord.x, coord.y, wrong, 1f, if (locked) ModuleSource.USER else ModuleSource.IMAGE)
            val engine = RecoveryEngine()
            val outcome = engine.recoverWithDiagnostics(matrix, formatConstraint = selected,
                manualEdits = mapOf(coord to wrong))
            assertTrue(outcome.candidates.isEmpty())
            assertEquals(RecoveryFailureReason.FORMAT_EDIT_CONFLICT, outcome.failure?.reason)
            assertEquals(coord, outcome.failure?.manualConflicts?.single()?.coord)
            assertEquals(0, outcome.formatAttempts)
            assertEquals(wrong, matrix[coord.x, coord.y].value)
        }
    }

    @Test fun imageFormatErrorsUseFormatLimitEvenWithBothSelections() {
        val (matrix, selected) = fixture()
        val coord = Coord(0, 8)
        matrix.set(coord.x, coord.y, opposite(matrix[coord.x, coord.y].value), 1f, ModuleSource.IMAGE)
        assertTrue(RecoveryEngine().recover(matrix, maxFormatMismatches = 0).none { it.text == "FORMAT-LOCK" })
        val candidate = RecoveryEngine().recover(matrix, maxFormatMismatches = 1, maxKnownDataEccMismatches = 0)
            .first { it.text == "FORMAT-LOCK" }
        assertEquals(1, candidate.formatMismatches)
        assertEquals(0, candidate.knownBitMismatchesAfterCorrection)
        assertTrue(RecoveryEngine().recover(matrix, formatConstraint = selected, maxFormatMismatches = 0,
            maxKnownDataEccMismatches = 0).none { it.text == "FORMAT-LOCK" })
    }

    @Test fun automaticFormatDoesNotPermitContradictoryManualFormatPins() {
        val (matrix, _) = fixture()
        matrix.set(0, 8, opposite(matrix[0, 8].value), 1f, ModuleSource.USER)
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(matrix)
        assertTrue(outcome.candidates.none { it.text == "FORMAT-LOCK" })
        assertEquals(RecoveryFailureReason.MANUAL_PIN_CONFLICT, outcome.failure?.reason)
    }

    @Test fun nonRsPinsAreCheckedWithoutCountingImageStructureErrorsAsDataErrors() {
        for (version in listOf(2, 7)) {
            val (original, selected) = fixture(version)
            val extraction = CodewordExtractor.extract(original, version, selected.dataMask!!)
            val codewordCoords = extraction.codewords.flatMapTo(hashSetOf()) { it.coordinates }
            val remainder = (0 until original.dimension).flatMap { y -> (0 until original.dimension).map { x -> Coord(x, y) } }
                .firstOrNull { !QrFunctionMask.isFunction(version, it.x, it.y) && it !in codewordCoords }
            val coords = listOfNotNull(Coord(0, 0), Coord(8, original.dimension - 8), remainder,
                if (version >= 7) Coord(original.dimension - 11, 0) else null)
            for (coord in coords) {
                val matrix = original.copy()
                matrix.set(coord.x, coord.y, opposite(matrix[coord.x, coord.y].value), 1f, ModuleSource.IMAGE)
                val candidate = RecoveryEngine().recover(matrix, formatConstraint = selected, maxKnownDataEccMismatches = 0)
                    .first { it.text == "FORMAT-LOCK" }
                assertEquals(0, candidate.knownBitMismatchesAfterCorrection)
                assertEquals(extraction.codewords.size * 8, candidate.knownBitsCompared)
                matrix[coord.x, coord.y].source = ModuleSource.USER
                val engine = RecoveryEngine()
                val outcome = engine.recoverWithDiagnostics(matrix, formatConstraint = selected)
                assertTrue(outcome.candidates.isEmpty())
                assertEquals(RecoveryFailureReason.MANUAL_PIN_CONFLICT, outcome.failure?.reason)
                assertEquals(coord, outcome.failure?.manualConflicts?.single()?.coord)
                matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
                assertTrue(engine.recover(matrix, formatConstraint = selected).any { it.text == "FORMAT-LOCK" })
            }
        }
    }

    @Test fun allVersionsHaveCompleteFunctionExpectations() {
        for (version in 1..40) {
            val matrix = QrMatrix(17 + 4 * version)
            StructuralRepair.apply(matrix, ErrorCorrectionLevel.L, 0)
            for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
                if (QrFunctionMask.isFunction(version, x, y)) assertNotEquals("V$version ($x,$y)", ModuleValue.ERASED, matrix[x,y].value)
            }
        }
    }
    @Test fun excludedFormatsRetainCountsAndLimitWithoutAttemptingRs() {
        val (original, selected) = fixture()
        val coord = Coord(0, 8)
        for (constraint in listOf(null, QrFormatConstraint(ecLevel = selected.ecLevel),
            QrFormatConstraint(dataMask = selected.dataMask), selected)) {
            val matrix = original.copy()
            matrix.set(coord.x, coord.y, opposite(matrix[coord.x, coord.y].value), 1f, ModuleSource.IMAGE)
            val engine = RecoveryEngine()
            val outcome = engine.recoverWithDiagnostics(matrix, formatConstraint = constraint, maxFormatMismatches = 0)
            assertTrue(outcome.candidates.isEmpty())
            val failure = requireNotNull(outcome.failure)
            assertEquals(RecoveryFailureReason.FORMAT_MISMATCH_LIMIT, failure.reason)
            assertEquals(1, failure.formatMismatches)
            assertEquals(30, failure.formatKnownBits)
            assertEquals(0, failure.formatMismatchLimit)
            assertEquals(0, outcome.formatAttempts)
            assertTrue(engine.recover(matrix, formatConstraint = constraint, maxFormatMismatches = 1)
                .any { it.text == "FORMAT-LOCK" })
            assertTrue(engine.recover(matrix, formatConstraint = selected, maxFormatMismatches = 0).isEmpty())
            assertEquals(SearchCompleteness.COMPLETE, outcome.searchDiagnostic.completeness)
        }
    }
}
