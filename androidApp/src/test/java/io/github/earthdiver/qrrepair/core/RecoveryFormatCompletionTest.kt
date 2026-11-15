package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryFormatCompletionTest {
    @Test fun completedPriorityListDecodingResolvesEarlierFailuresUnderBothPolicies() {
        // The repository example is independently generated and includes one explicit header star.
        val matrix = QrTextMatrixParser.parse(java.io.File("../docs/examples/priority-list-decoding/before.txt").readText()).matrix
        for (policy in listOf(RecoveryExecutionPolicy.DETAILED, RecoveryExecutionPolicy.AUTOMATIC_EXTENDED)) {
            for (limit in listOf(2, ErrorLimitPolicy.automaticScanData(1))) {
                val result = RecoveryEngine().recoverWithDiagnostics(matrix.copy(), executionPolicy = policy,
                    allowAutomaticPrioritySearch = true, trustKnownPayloadCount = true,
                    maxFormatMismatches = 6, maxKnownDataEccMismatches = limit,
                    listDecodingAdditionalErrors = 1, knownBitErrorLimit = 0,
                    listDecodingBudget = ListDecodingBudget(clockMillis = { 0L }))
                assertFalse(result.searchDiagnostic.toString(), result.searchDiagnostic.unresolvedCalculations)
                assertEquals(RecoveryFormatState.CANDIDATE, result.searchDiagnostic.formatOutcomes.single().state)
                assertTrue(result.candidates.any { it.text == "QRevy repaired." && it.priorityGuessedBits == 1 })
                assertEquals(if (limit == 2) 1 else 2, result.candidates.size)
                assertEquals(limit == 2, QuickAutoRecovery.canReport(result))
                assertEquals(if (limit == 2) setOf(2) else setOf(2, 6),
                    result.candidates.map { it.knownBitMismatchesAfterCorrection }.toSet())
            }
        }
        assertEquals(11, ErrorLimitPolicy.automaticScanData(1))
    }

    @Test fun interruptionAfterFindingAPriorityListCandidateStillPreventsAutomaticSuccess() {
        val matrix = QrTextMatrixParser.parse(java.io.File("../docs/examples/priority-list-decoding/before.txt").readText()).matrix
        var stopped = false
        val result = RecoveryEngine().recoverWithDiagnostics(matrix,
            executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_EXTENDED, allowAutomaticPrioritySearch = true,
            trustKnownPayloadCount = true, maxFormatMismatches = 6, maxKnownDataEccMismatches = 2,
            listDecodingAdditionalErrors = 1, knownBitErrorLimit = 0,
            listDecodingBudget = ListDecodingBudget(clockMillis = { 0L }), stopRequested = { stopped },
            discoveryCounter = RecoveryDiscoveryCounter(onChanged = { stopped = true }),
            onPriorityProgress = { _, _ -> if (stopped) throw RecoveryCancelledException() })
        assertTrue(result.candidates.any { it.text == "QRevy repaired." })
        assertTrue(SearchIncompleteReason.USER_INTERRUPTED in result.searchDiagnostic.incompleteReasons)
        assertTrue(result.searchDiagnostic.unresolvedCalculations)
        assertFalse(QuickAutoRecovery.canReport(result))
    }

    private val format = QrFormatConstraint(ErrorCorrectionLevel.Q, 6)
    private fun missingSuffixAndEcc(): QrMatrix {
        val encoded = Encoder.encode("STATE 42", ErrorCorrectionLevel.Q,
            mapOf(EncodeHintType.QR_VERSION to 2, EncodeHintType.QR_MASK_PATTERN to 6)).matrix
        val matrix = QrMatrix(encoded.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (encoded[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        CodewordExtractor.extract(matrix, 2, 6).codewords.drop(8).flatMap { it.coordinates }.forEach {
            matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        return matrix
    }

    private fun recover(matrix: QrMatrix, policy: RecoveryExecutionPolicy, single: Boolean,
        retention: RecoveryRetentionBudget = RecoveryRetentionBudget(),
        counter: RecoveryDiscoveryCounter = RecoveryDiscoveryCounter(), stopped: () -> Boolean = { false }) =
        RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format,
            maxKnownDataEccMismatches = 18, trustKnownPayloadCount = single,
            listDecodingAdditionalErrors = 0, knownBitErrorLimit = 0,
            executionPolicy = policy, retentionBudget = retention, discoveryCounter = counter, stopRequested = stopped)

    @Test fun singleSegmentCompletionReplacesEarlierRsFailureInBothWorkflows() {
        for (policy in listOf(RecoveryExecutionPolicy.DETAILED, RecoveryExecutionPolicy.AUTOMATIC_BASIC)) {
            val result = recover(missingSuffixAndEcc(), policy, true)
            assertEquals("STATE 42", result.candidates.single().text)
            assertFalse(result.searchDiagnostic.toString(), result.searchDiagnostic.unresolvedCalculations)
            assertEquals(RecoveryFormatState.CANDIDATE, result.searchDiagnostic.formatOutcomes.single().state)
            assertTrue(QuickAutoRecovery.canReport(result))
        }
    }

    @Test fun observedTerminatorCanResolveAnExactSpaceWithoutTheSingleSegmentAssumption() {
        val result = recover(missingSuffixAndEcc(), RecoveryExecutionPolicy.AUTOMATIC_BASIC, false)
        assertEquals("STATE 42", result.candidates.single().text)
        assertFalse(result.searchDiagnostic.unresolvedCalculations)
        assertTrue(QuickAutoRecovery.canReport(result))
    }

    @Test fun missingHeaderCannotBeMisreportedAsAnExcludedFormat() {
        val matrix = missingSuffixAndEcc()
        val header = CodewordExtractor.extract(matrix, 2, 6).codewords.first().coordinates.first()
        matrix.set(header.x, header.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        val result = recover(matrix, RecoveryExecutionPolicy.AUTOMATIC_BASIC, true)
        assertEquals(RecoveryFormatState.UNRESOLVED, result.searchDiagnostic.formatOutcomes.single().state)
        assertFalse(QuickAutoRecovery.canReport(result))
    }

    @Test fun interruptionAndMemoryLimitsCannotProduceAutomaticSuccess() {
        var stopped = false
        val interrupted = recover(missingSuffixAndEcc(), RecoveryExecutionPolicy.AUTOMATIC_BASIC, true,
            counter = RecoveryDiscoveryCounter(onChanged = {
                stopped = true
                throw RecoveryCancelledException()
            }), stopped = { stopped })
        assertFalse(QuickAutoRecovery.canReport(interrupted))
        assertTrue(interrupted.searchDiagnostic.incompleteReasons.contains(SearchIncompleteReason.USER_INTERRUPTED))
        val memoryLimited = recover(missingSuffixAndEcc(), RecoveryExecutionPolicy.AUTOMATIC_BASIC, true,
            retention = RecoveryRetentionBudget(workingLimitBytes = 0))
        assertFalse(QuickAutoRecovery.canReport(memoryLimited))
        assertTrue(memoryLimited.searchDiagnostic.incompleteReasons.contains(SearchIncompleteReason.WORKING_MEMORY_LIMIT))
    }
}
