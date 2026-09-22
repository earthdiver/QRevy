package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class PriorityObservationRegressionTest {
    @Test fun explicitPriorityTrialsKeepOriginalEvidence() {
        val (matrix, originalExtraction, expectedRaw) = damagedMatrix()
        var progressCalls = 0
        val engine = RecoveryEngine()
        val candidates = engine.recover(matrix, maxFormatCandidates = 1) { _, _ -> progressCalls++ }
        assertEquals(17, progressCalls) // All 16 assignments, followed by completion.
        assertEquals(setOf("STAR-SEARCH"), candidates.map { it.text }.toSet())
        val candidate = candidates.first()
        assertEquals(9, candidate.originalErasedCodewords)
        assertEquals(164, candidate.knownBitsCompared)
        assertEquals(1, candidate.knownBitMismatchesAfterCorrection)
        assertEquals(originalExtraction.codewords.map { it.unknownBits },
            candidate.extraction.codewords.map { it.unknownBits })
        assertEquals(originalExtraction.codewords.map { it.value },
            candidate.extraction.codewords.map { it.value })
        assertEquals(128, candidate.repairInfo[0].originalUnknownMask)
        assertEquals(4, matrix.prioritySearchCoordinates().size)
        assertEquals(44, matrix.erasureCount())
        assertTrue(candidates.all { it.correctedInterleaved.contentEquals(expectedRaw) })
        assertEquals(4, candidate.priorityGuessedBits)
        assertEquals(0, candidate.partialGuessedBits)
        val after = RepairTraceBuilder.candidateMatrix(matrix, candidate, engine)
        val traces = RepairTraceBuilder.build(matrix, after, candidate)
        for ((coord, value) in candidate.priorityAssignments) {
            assertEquals(ModuleValue.ERASED, matrix[coord.x, coord.y].value)
            assertTrue(matrix[coord.x, coord.y].prioritySearch)
            assertEquals(value, after[coord.x, coord.y].value)
            assertEquals(ModuleSource.RECOVERED, after[coord.x, coord.y].source)
            val trace = requireNotNull(traces[coord])
            assertEquals(value, trace.priorityAssignment)
            assertEquals(RepairReasonKind.PARTIAL_BIT_SEARCH, trace.primaryReason)
            assertTrue(trace.matches(RepairFilter.PAYLOAD_CONSTRAINT))
        }
        val repeated = engine.recover(matrix.copy(), maxFormatCandidates = 1).first()
        assertTrue(candidate.correctedInterleaved.contentEquals(repeated.correctedInterleaved))
        assertEquals(candidate.priorityAssignments, repeated.priorityAssignments)
        assertEquals(candidate.knownBitsCompared, repeated.knownBitsCompared)
    }

    @Test fun ordinarySuccessDoesNotClaimExplicitPrioritySearch() {
        val encoded = Encoder.encode("STAR-SEARCH", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(encoded.matrix.width)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            matrix.set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)
        val expectedRaw = extraction.codewords.map { it.value }.toIntArray()
        val coord = extraction.codewords.first().coordinates.first()
        matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        matrix.setPrioritySearch(coord.x, coord.y, true)
        var calls = 0
        val candidate = RecoveryEngine().recover(matrix, maxFormatCandidates = 1) { _, _ -> calls++ }.first()
        assertEquals(0, calls)
        assertEquals(0, candidate.priorityGuessedBits)
        assertTrue(candidate.priorityAssignments.isEmpty())
        assertTrue(candidate.correctedInterleaved.contentEquals(expectedRaw))
    }

    @Test fun ordinaryErasureInSameCodewordDoesNotInheritPriorityAssignment() {
        val (matrix, extraction, _) = damagedMatrix(extraUnknownInFirstWord = true)
        val engine = RecoveryEngine()
        val candidate = engine.recover(matrix, maxFormatCandidates = 1).first { it.text == "STAR-SEARCH" }
        assertEquals(4, candidate.priorityGuessedBits)
        val after = RepairTraceBuilder.candidateMatrix(matrix, candidate, engine)
        val traces = RepairTraceBuilder.build(matrix, after, candidate)
        val priorityCoord = extraction.codewords[0].coordinates[0]
        val ordinaryCoord = extraction.codewords[0].coordinates[1]
        assertNotNull(traces[priorityCoord]?.priorityAssignment)
        val ordinaryTrace = requireNotNull(traces[ordinaryCoord])
        assertNull(ordinaryTrace.priorityAssignment)
        assertEquals(ModuleValue.ERASED, ordinaryTrace.originalValue)
        assertNotEquals(ModuleValue.ERASED, ordinaryTrace.finalValue)
    }

    @Test fun nestedRecoveryInProgressCallbackKeepsOuterEvidenceAndDiagnostics() {
        val (matrix, _, _) = damagedMatrix()
        val baselineEngine = RecoveryEngine()
        val expectedOutcome = baselineEngine.recoverWithDiagnostics(matrix, maxFormatCandidates = 1)
        val expected = expectedOutcome.candidates
        val expectedAttempts = expectedOutcome.formatAttempts
        val expectedFailure = expectedOutcome.failure
        val engine = RecoveryEngine()
        val encoded = Encoder.encode("NESTED", ErrorCorrectionLevel.L)
        val inner = QrMatrix(encoded.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) {
                set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
            }
        }
        var nested = false
        val actualOutcome = engine.recoverWithDiagnostics(matrix, maxFormatCandidates = 1) { _, _ ->
            if (!nested) {
                nested = true
                assertTrue(engine.recover(inner, maxFormatCandidates = 1).any { it.text == "NESTED" })
            }
        }
        val actual = actualOutcome.candidates
        assertTrue(nested)
        assertEquals(expected.map { it.correctedInterleaved.toList() }, actual.map { it.correctedInterleaved.toList() })
        assertEquals(expected.map { it.rsBitDiagnostic }, actual.map { it.rsBitDiagnostic })
        assertEquals(expected.map { it.knownBitsCompared }, actual.map { it.knownBitsCompared })
        assertEquals(expected.map { it.repairInfo }, actual.map { it.repairInfo })
        assertEquals(expected.map { it.priorityAssignments }, actual.map { it.priorityAssignments })
        assertEquals(expectedAttempts, actualOutcome.formatAttempts)
        assertEquals(expectedFailure, actualOutcome.failure)
    }

    private fun damagedMatrix(
        extraUnknownInFirstWord: Boolean = false,
    ): Triple<QrMatrix, CodewordExtraction, IntArray> {
        val encoded = Encoder.encode("STAR-SEARCH", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(encoded.matrix.width)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            matrix.set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)
        extraction.codewords.take(4).forEach { word ->
            val c = word.coordinates.first()
            matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
            matrix.setPrioritySearch(c.x, c.y, true)
        }
        extraction.codewords.slice(4..8).forEach { word ->
            word.coordinates.forEach { c -> matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        }
        if (extraUnknownInFirstWord) {
            val coord = extraction.codewords[0].coordinates[1]
            matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        val error = extraction.codewords[12].coordinates.first()
        val flipped = if (matrix[error.x, error.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        matrix.set(error.x, error.y, flipped, 1f, ModuleSource.IMAGE)
        return Triple(matrix, CodewordExtractor.extract(matrix, 1, encoded.maskPattern),
            extraction.codewords.map { it.value }.toIntArray())
    }
}
