package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class AutomaticExactRecoveryTest {
    private val text = "EXACT FORMAT RECOVERY 123"

    @Test fun sharedPaddingRecoveryKeepsUnresolvedAlternativesVisibleInTheBoundedTrial() {
        val qr = Encoder.encode("a", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 3)).matrix
        val input = QrMatrix(21).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        // Erase one padding byte and all parity. RS alone leaves eight free bits;
        // the known payload and remaining padding determine the erased padding byte.
        for (word in CodewordExtractor.extract(input, 1, 3).codewords.drop(18))
            for (p in word.coordinates) input.set(p.x, p.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        val extraction = CodewordExtractor.extract(input, 1, 3)
        val de = QrBlockInterleaver.deinterleave(extraction, 1, ErrorCorrectionLevel.L)
        assertEquals(8, RsBitConstraintSolver().analyze(de.blocks.single(), de.ecSymbolsPerBlock)!!.dimension)
        for (policy in listOf(RecoveryExecutionPolicy.DETAILED, RecoveryExecutionPolicy.AUTOMATIC_EXACT)) {
            val outcome = RecoveryEngine().recoverWithDiagnostics(input, maxKnownDataEccMismatches = 0,
                maxFormatMismatches = 6, executionPolicy = policy)
            if (policy == RecoveryExecutionPolicy.AUTOMATIC_EXACT) {
                // A recovered constrained candidate cannot hide the unenumerated original space.
                assertTrue(outcome.searchDiagnostic.unresolvedCalculations)
                assertFalse(QuickAutoRecovery.canReport(outcome))
            } else assertTrue(QuickAutoRecovery.canReport(outcome))
            val candidate = outcome.candidates.single()
            assertEquals("a", candidate.text)
            assertEquals(0, candidate.knownBitMismatchesAfterCorrection)
            assertEquals(0, candidate.partialGuessedBits)
            extraction.codewords.forEachIndexed { index, word ->
                assertEquals(0, (candidate.correctedInterleaved[index] xor word.value) and word.unknownBits.inv() and 255)
            }
        }
    }

    @Test fun aKnownParityErrorCannotBeRepairedUnderTheZeroErrorCondition() {
        val input = matrix()
        val p = CodewordExtractor.extract(input, 3, 2).codewords.last().coordinates.last()
        input.set(p.x, p.y, if (input[p.x, p.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
            1f, ModuleSource.IMAGE)
        val observed = input[p.x, p.y].value
        for (policy in listOf(RecoveryExecutionPolicy.DETAILED, RecoveryExecutionPolicy.AUTOMATIC_EXACT)) {
            val outcome = RecoveryEngine().recoverWithDiagnostics(input, maxKnownDataEccMismatches = 0,
                maxFormatMismatches = 6, executionPolicy = policy)
            assertTrue(outcome.candidates.isEmpty())
            assertFalse(QuickAutoRecovery.canReport(outcome))
            assertEquals(observed, input[p.x, p.y].value)
        }
    }

    private fun matrix(content: String = text): QrMatrix {
        val encoded = Encoder.encode(content, ErrorCorrectionLevel.H,
            mapOf(EncodeHintType.QR_VERSION to 3, EncodeHintType.QR_MASK_PATTERN to 2)).matrix
        return QrMatrix(encoded.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (encoded[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
    }

    private fun missingFormat(content: String = text) = matrix(content).apply {
        for (i in 0 until dimension) {
            set(8, i, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            set(i, 8, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
    }

    private fun exact(matrix: QrMatrix, budget: RecoveryRetentionBudget = RecoveryRetentionBudget(),
        stop: () -> Boolean = { false }) = RecoveryEngine().recoverWithDiagnostics(matrix,
        maxKnownDataEccMismatches = 0, maxFormatMismatches = 6,
        executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_EXACT, retentionBudget = budget, stopRequested = stop)

    @Test fun absentFormatInformationCanBeProvedUniqueAfterPayloadValidation() {
        val input = missingFormat()
        assertNull(FormatSolver.trustedCandidate(input))
        val result = exact(input)
        assertEquals(32, result.formatAttempts)
        assertEquals(RecoveryDiscoveryCount.Exact(1), result.discoveredCandidates)
        val candidate = result.candidates.single()
        assertEquals(text, candidate.text)
        assertEquals(ErrorCorrectionLevel.H, candidate.ecLevel)
        assertEquals(2, candidate.dataMask)
        assertEquals(0, candidate.knownBitMismatchesAfterCorrection)
        assertTrue(result.searchDiagnostic.candidateRejections.contains(RecoveryCandidateRejection.QR_STRUCTURE))
        assertFalse(result.searchDiagnostic.unresolvedCalculations)
        assertFalse(result.searchDiagnostic.knownBitSearchAttempted)
        assertTrue(QuickAutoRecovery.canReport(result))
    }

    @Test fun confirmedOrExplicitFormatsDoNotRepeatTheExactFormatTrial() {
        assertFalse(QuickAutoRecovery.needsExactFormatTrial(matrix(), null))
        assertTrue(QuickAutoRecovery.needsExactFormatTrial(missingFormat(), null))
        assertTrue(QuickAutoRecovery.needsExactFormatTrial(missingFormat(), QrFormatConstraint(ecLevel = ErrorCorrectionLevel.H)))
        assertFalse(QuickAutoRecovery.needsExactFormatTrial(missingFormat(), QrFormatConstraint(ErrorCorrectionLevel.H, 2)))
    }

    @Test fun aSlowFirstCandidateKeepsItsFormatVerificationWithinTheTotalBudget() {
        fun recover(continueVerification: Boolean): Pair<RecoveryOutcome, String> {
            var now = 0L
            val trace = AutomaticScanTrace { now }
            val budget = AutomaticScanBudget(clockMillis = { now }, trace = trace)
            val counter = RecoveryDiscoveryCounter(onChanged = { now = 650L })
            val stop = if (continueVerification) budget.exactVerificationSlice(counter) else budget.slice(600)
            val result = RecoveryEngine().recoverWithDiagnostics(missingFormat(),
                maxKnownDataEccMismatches = 0, maxFormatMismatches = 6,
                executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_EXACT,
                discoveryCounter = counter, stopRequested = stop)
            return result to trace.report()
        }
        val interrupted = recover(false).first
        assertEquals(RecoveryDiscoveryCount.Exact(1), interrupted.discoveredCandidates)
        assertTrue(interrupted.formatAttempts < 32)
        assertFalse(QuickAutoRecovery.canReport(interrupted))
        val (completed, trace) = recover(true)
        assertEquals(32, completed.formatAttempts)
        assertEquals(text, completed.candidates.single().text)
        assertTrue(QuickAutoRecovery.canReport(completed))
        assertEquals(1, trace.lines().count { it.contains("EXACT_VERIFICATION: CONTINUE") })
    }

    @Test fun exactVerificationStillStopsAtTheTotalDeadlineOrUserCancellation() {
        for (cancel in listOf(false, true)) {
            var now = 0L
            var cancelled = false
            val budget = AutomaticScanBudget(clockMillis = { now }, stopRequested = { cancelled })
            val counter = RecoveryDiscoveryCounter(onChanged = {
                now = if (cancel) 650L else 3_000L
                cancelled = cancel
            })
            val outcome = RecoveryEngine().recoverWithDiagnostics(missingFormat(),
                maxKnownDataEccMismatches = 0, maxFormatMismatches = 6,
                executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_EXACT,
                discoveryCounter = counter, stopRequested = budget.exactVerificationSlice(counter))
            assertEquals(RecoveryDiscoveryCount.Exact(1), outcome.discoveredCandidates)
            assertFalse(QuickAutoRecovery.canReport(outcome))
            assertEquals(if (cancel) "STOP_REQUESTED" else "TOTAL_TIME_LIMIT", budget.stopReason())
        }
    }

    @Test fun emptyOrAmbiguousTrialsDoNotReceiveExtraVerificationTime() {
        var now = 0L
        val budget = AutomaticScanBudget(clockMillis = { now })
        val counter = RecoveryDiscoveryCounter()
        val stop = budget.exactVerificationSlice(counter)
        now = 600L
        assertTrue(stop())
        counter.record(exact(missingFormat()).candidates.single())
        assertFalse(stop())
        counter.record(exact(missingFormat("ANOTHER EXACT QR 123")).candidates.single())
        assertTrue(stop())
        // A subsequent trial cannot borrow an earlier trial's discovery.
        val next = budget.exactVerificationSlice(RecoveryDiscoveryCounter())
        now = 1_200L
        assertTrue(next())
    }

    @Test fun uncertainAlternativePayloadsAndResourceLimitsPreventAutomaticSelection() {
        val first = matrix("EXACT FORMAT A")
        val second = matrix("EXACT FORMAT B")
        for (y in 0 until first.dimension) for (x in 0 until first.dimension) {
            if (first[x, y].value != second[x, y].value || x == 8 || y == 8)
                first.set(x, y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        val ambiguous = exact(first)
        assertTrue(ambiguous.searchDiagnostic.unresolvedCalculations)
        assertFalse(QuickAutoRecovery.canReport(ambiguous))
        assertFalse(QuickAutoRecovery.canReport(exact(missingFormat(), RecoveryRetentionBudget(workingLimitBytes = 0))))
        assertFalse(QuickAutoRecovery.canReport(exact(missingFormat(), stop = { true })))
        val result = exact(missingFormat())
        for (reason in SearchIncompleteReason.entries) assertFalse(QuickAutoRecovery.canReport(result.copy(
            searchDiagnostic = result.searchDiagnostic.copy(incompleteReasons = setOf(reason)))))
    }

    @Test fun quickAutoReportsExactConditionsAndPreservesSavedPreferences() {
        val input = missingFormat()
        val pitch = 6
        val side = input.dimension * pitch
        val image = LumaImage(side, side, IntArray(side * side) { i ->
            when (input[i % side / pitch, i / side / pitch].value) {
                ModuleValue.ONE -> 0
                ModuleValue.ZERO -> 255
                else -> 128
            }
        })
        val first = (pitch - 1) / 2.0
        val last = first + (input.dimension - 1) * pitch
        val saved = PreprocessSettings(version = 3, automaticThresholds = true, photographicSource = true,
            manualQuad = ImageQuad(PointD(first, first), PointD(last, first), PointD(last, last), PointD(first, last)),
            lmCorrectionEnabled = false).withInitialErrorLimits()
        val result = QuickAutoRecovery().run(image, saved, AutomaticScanBudget(clockMillis = { 0L }, trace = AutomaticScanTrace()))
        assertEquals(text, result.selected?.text)
        assertEquals(AutomaticScanAssumption.EXACT_KNOWN_BITS, result.assumption)
        assertEquals(0, result.selected!!.displayConditions!!.dataEccLimit)
        assertFalse(result.diagnosticReport!!.contains("policy=AUTOMATIC_EXTENDED"))
        assertEquals(28, saved.effectiveDataEccMismatchLimit)
        assertFalse(saved.lmCorrectionEnabled)
    }
}
