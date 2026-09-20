package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class RecoveryResultSetTest {
    private fun candidate(id: Int, matches: Int = 10) = RecoveryCandidate(
        version = 1, ecLevel = ErrorCorrectionLevel.L, dataMask = 0, text = "result-$id",
        dataBytes = byteArrayOf(0), formatMatches = matches, formatMismatches = 0,
        originalErasedCodewords = 0, erasedCodewords = 0, correctedErrataSymbols = 0,
        knownBitMismatchesAfterCorrection = 0, knownBitsCompared = 100,
        correctedInterleaved = intArrayOf(id), extraction = CodewordExtraction(emptyList()),
        searchDiagnostic = RecoverySearchDiagnostic(acceptedCandidate = true),
    )

    private fun attempt(candidates: List<RecoveryCandidate>, score: Double = 0.0,
        matrix: QrMatrix = QrMatrix(21), failure: RecoveryFailure? = null) = GeometryAttempt(
        GeometryHypothesis(emptyList(), GridGeometry(1, FinderRole.TOP_LEFT, score, 0.0, 1.0, 0.0), score),
        matrix, MatrixSampler.SamplingStats(128.0, 255.0, 0.0), candidates, failure,
        RecoverySearchDiagnostic(acceptedCandidate = candidates.isNotEmpty()),
    )

    @Test fun differentResultsKeepTheirOwnInputGeometryAndPolarity() {
        val first = attempt(listOf(candidate(1)))
        val second = attempt(listOf(candidate(2)), score = 2.0)
        val normal = SamplingProfile(PreprocessSettings(), false)
        val inverted = normal.copy(inverted = true)
        val accumulator = RecoveryResultAccumulator()
        accumulator.add(first, normal)
        accumulator.add(second, inverted)

        val result = accumulator.result()
        assertEquals(2, result.entries.size)
        assertEquals(2, result.attemptedGeometries)
        assertSame(first.matrix, result.entries[0].attempt.matrix)
        assertSame(second.matrix, result.entries[1].attempt.matrix)
        assertSame(second.hypothesis, result.entries[1].attempt.hypothesis)
        assertTrue(result.entries[1].profile!!.inverted)
        assertEquals(result.entries.map { it.candidate }, result.rankedRecoveries.map { it.second })
    }

    @Test fun duplicateQrKeepsBestOriginAndBoundedAlternativeEvidence() {
        val accumulator = RecoveryResultAccumulator(RecoveryRetentionBudget(maxAlternativeDerivations = 1))
        val original = candidate(7)
        val better = original.copy(formatMatches = 20, correctedInterleaved = intArrayOf(7))
        val betterAttempt = attempt(listOf(better), score = 1.0)
        accumulator.add(attempt(listOf(original)), null)
        accumulator.add(betterAttempt, null)
        accumulator.add(attempt(listOf(original.copy(correctedInterleaved = intArrayOf(7)))), null)

        val result = accumulator.result()
        val entry = result.entries.single()
        assertSame(better, entry.candidate)
        assertSame(betterAttempt.matrix, entry.attempt.matrix)
        assertSame(original, entry.alternativeDerivations.single().candidate)
        assertTrue(entry.derivationsOmitted)
        assertTrue(result.searchDiagnostic!!.derivationsOmitted)
        assertFalse(result.searchDiagnostic.unsearched)
        assertEquals(1, result.attempts.size)
    }

    @Test fun laterBetterResultDisplacesEarlierResultAndTiesKeepDiscoveryOrder() {
        val accumulator = RecoveryResultAccumulator(RecoveryRetentionBudget(maxCandidates = 2))
        accumulator.add(attempt(listOf(candidate(1), candidate(2), candidate(3))), null)
        assertEquals(listOf(1, 2), accumulator.result().entries.map { it.candidate.correctedInterleaved[0] })
        accumulator.add(attempt(listOf(candidate(4, matches = 30))), null)

        val result = accumulator.result()
        assertEquals(listOf(4, 1), result.entries.map { it.candidate.correctedInterleaved[0] })
        assertTrue(result.searchDiagnostic!!.candidatesOmitted)
        assertFalse(result.searchDiagnostic.unsearched)
        assertTrue(result.searchDiagnostic.coverageComplete)
    }

    @Test fun byteLimitIncludesEachDistinctOriginMatrixAndStillAllowsLaterBetterResults() {
        val accumulator = RecoveryResultAccumulator(RecoveryRetentionBudget(limitBytes = 30_000))
        accumulator.add(attempt(listOf(candidate(1), candidate(2))), null)
        assertEquals(2, accumulator.result().entries.size)
        accumulator.add(attempt(listOf(candidate(3, matches = 30))), null)

        val result = accumulator.result()
        assertEquals(listOf(3), result.entries.map { it.candidate.correctedInterleaved[0] })
        assertTrue(result.searchDiagnostic!!.candidatesOmitted)
        assertFalse(result.searchDiagnostic.unsearched)
    }

    @Test fun failedAttemptsKeepOnlyBestReferenceWithoutLosingSearchEvidence() {
        val accumulator = RecoveryResultAccumulator()
        repeat(4) { index ->
            val failure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 10 + index, 0, 30,
                RecoveryFailureStage.REED_SOLOMON)
            accumulator.add(attempt(emptyList(), failure = failure), null)
        }
        val result = accumulator.result()
        assertEquals(4, result.attemptedGeometries)
        assertEquals(4, result.testedHypotheses.size)
        assertEquals(1, result.attempts.size)
        assertEquals(13, result.bestFailure!!.formatMatches)
        assertFalse(result.searchDiagnostic!!.unsearched)
        assertFalse(result.searchDiagnostic.candidatesOmitted)
    }

    @Test fun validNonTextQrIsSelectableAndSameTextDifferentQrStaysDistinct() {
        val accumulator = RecoveryResultAccumulator()
        val text = candidate(1)
        val binary = candidate(2).copy(text = null, dataBytes = byteArrayOf(0x40, 0x16, 0x10))
        assertTrue(StrictQrPayloadValidator.isValid(binary.dataBytes, binary.version))
        accumulator.add(attempt(listOf(text, text.copy(dataMask = 1), binary)), null)
        assertEquals(3, accumulator.result().entries.size)
        assertTrue(accumulator.result().entries.any { it.candidate.text == null })
    }

    @Test fun omittedResultsRemainVisibleWhenOlderAttemptsHaveNoSearchSummary() {
        val accumulator = RecoveryResultAccumulator(RecoveryRetentionBudget(maxCandidates = 1))
        accumulator.add(attempt(listOf(candidate(1), candidate(2))).copy(searchDiagnostic = null), null)
        val result = accumulator.result()
        assertTrue(result.candidatesTruncated)
        assertFalse(result.searchDiagnostic!!.coverageComplete)
        assertFalse(result.searchDiagnostic.unsearched)
        assertEquals(1, result.entries.single().attempt.recoveries.size)
    }
}
