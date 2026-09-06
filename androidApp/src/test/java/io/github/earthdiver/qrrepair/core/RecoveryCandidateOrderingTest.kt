package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecoveryCandidateOrderingTest {
    private val candidate = RecoveryCandidate(
        version = 1, ecLevel = ErrorCorrectionLevel.L, dataMask = 0, text = "validated",
        dataBytes = byteArrayOf(), formatMatches = 10, formatMismatches = 0,
        originalErasedCodewords = 0, erasedCodewords = 0, correctedErrataSymbols = 0,
        knownBitMismatchesAfterCorrection = 0, knownBitsCompared = 100,
        correctedInterleaved = intArrayOf(), extraction = CodewordExtraction(emptyList()),
    )

    @Test fun payloadValidationOutranksStrongerFormatEvidenceAcrossGeometries() {
        val unparsed = candidate.copy(text = null, formatMatches = 30)
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 0.0, 0.0, 1.0, 0.0)
        fun attempt(result: RecoveryCandidate, score: Double) = GeometryAttempt(
            GeometryHypothesis(emptyList(), geometry, score), QrMatrix(21),
            MatrixSampler.SamplingStats(128.0, 255.0, 0.0), listOf(result),
        )
        val parsedAttempt = attempt(candidate, 100.0)
        val result = AutoRecoveryResult(emptyList(), listOf(attempt(unparsed, 0.0), parsedAttempt))
        assertSame(candidate, listOf(unparsed, candidate).sortedWith(recoveryCandidateComparator).first())
        assertSame(parsedAttempt, result.bestAttempt)
        assertSame(candidate, result.rankedRecoveries.first().second)
    }

    @Test fun equivalentEvidencePreservesDiscoveryOrder() {
        val otherMask = candidate.copy(dataMask = 1)
        assertEquals(0, recoveryCandidateComparator.compare(candidate, otherMask))
        assertEquals(listOf(1, 0), listOf(otherMask, candidate)
            .sortedWith(recoveryCandidateComparator).map { it.dataMask })
    }
}
