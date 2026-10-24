package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class SuffixRecoveryBudgetTest {
    private val text = "qrevy-a"
    private val format = QrFormatConstraint(ErrorCorrectionLevel.L, 0)
    private fun fixture(known: Int, errors: Int): QrMatrix {
        val matrix = postTerminatorMatrix(PayloadBits().apply { byteSegment(text) }.finish(19))
        val coords = CodewordExtractor.extract(matrix, 1, 0).codewords.take(19).flatMap { it.coordinates }
        coords.drop(68 + known).forEach { matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        coords.drop(68).take(errors).forEach { flip(matrix, it) }
        return matrix
    }
    private fun flip(matrix: QrMatrix, c: Coord) {
        matrix.set(c.x, c.y, if (matrix[c.x, c.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
            1f, ModuleSource.IMAGE)
    }
    private fun recover(matrix: QrMatrix, limit: Int, forced: Boolean = false, selection: QrFormatConstraint = format) =
        RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = selection,
            trustKnownPayloadCount = forced, maxKnownDataEccMismatches = limit, listDecodingBudget = ListDecodingBudget(0))

    @Test fun sixMatchingBitsAllowOrdinarySuffixErrorsAndFiveOnlyRecommendForcing() {
        val rescued = recover(fixture(7, 1), 1)
        val candidate = rescued.candidates.first { it.text == text }
        assertEquals(1, candidate.knownBitMismatchesAfterCorrection)
        assertFalse(candidate.constraintForcedByUserSetting)
        assertTrue(rescued.searchDiagnostic.decodedCandidate)
        val blocked = recover(fixture(6, 1), 1)
        assertTrue(blocked.candidates.none { it.text == text })
        assertTrue(blocked.searchDiagnostic.singleSegmentRetryAvailable)
        assertFalse(blocked.searchDiagnostic.decodedCandidate)
        assertTrue(recover(fixture(6, 1), 1, forced = true).candidates.any { it.text == text })
    }

    @Test fun finalLimitIncludesSuffixBodyAndParityAgainstOriginalObservations() {
        val matrix = fixture(8, 2)
        val extraction = CodewordExtractor.extract(matrix, 1, 0)
        for (raw in listOf(3, 20)) extraction.codewords[raw].coordinates.take(2).forEach { flip(matrix, it) }
        val accepted = recover(matrix, 6).candidates.first { it.text == text }
        assertEquals(6, accepted.knownBitMismatchesAfterCorrection)
        assertTrue(recover(matrix, 5).candidates.none { it.text == text })
        assertTrue(recover(matrix, 1).candidates.none { it.text == text })
    }

    @Test fun fixedSuffixAndSelectedFormatRemainHardConstraints() {
        val matrix = fixture(6, 1)
        val pinned = CodewordExtractor.extract(matrix, 1, 0).codewords[8].coordinates[4]
        matrix[pinned.x, pinned.y].source = ModuleSource.USER
        val result = recover(matrix, 10)
        assertFalse(result.searchDiagnostic.singleSegmentRetryAvailable)
        assertTrue(recover(matrix, 10, forced = true).candidates.none { it.text == text })
        assertTrue(recover(fixture(7, 1), 10, selection = QrFormatConstraint(ErrorCorrectionLevel.L, 1))
            .candidates.none { it.text == text })
    }

    @Test fun probeRejectsChangedHeadersAndUnknownHeaders() {
        val matrix = fixture(6, 1)
        val extraction = CodewordExtractor.extract(matrix, 1, 0)
        // Wrong count changes the assumed suffix. RS can recover the original, but the
        // corrected header must still match the forced hypothesis before recommending it.
        flip(matrix, extraction.codewords[1].coordinates[3])
        assertFalse(recover(matrix, 8).searchDiagnostic.singleSegmentRetryAvailable)
        val c = extraction.codewords[0].coordinates[0]
        matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        assertFalse(recover(matrix, 8).searchDiagnostic.singleSegmentRetryAvailable)
    }

    @Test fun probeSupportsMixedBlockMethodsAndRejectsUnresolvedSpaces() {
        val rs = ReedSolomonErasures()
        fun block(unknown: IntArray, error: Boolean = false): RsBlockWork {
            val words = rs.encode(intArrayOf(1, 2, 3, 4), 4)
            if (error) words[0] = words[0] xor 1
            return RsBlockWork(4, words, unknown)
        }
        val conventional = block(IntArray(8), error = true)
        // Five partially unknown symbols exceed ordinary RS erasure capacity but have
        // a unique bit solution. No assignment enumeration beyond dimension zero is used.
        val unique = block(IntArray(8) { if (it < 5) 1 else 0 })
        assertNotNull(SingleSegmentRetryProbe.solveBlocks(DeinterleavedQr(listOf(conventional, unique), emptyArray(), 4), RecoveryCancellation.NONE))
        val unresolved = block(IntArray(8) { 255 })
        assertNull(SingleSegmentRetryProbe.solveBlocks(DeinterleavedQr(listOf(conventional, unresolved), emptyArray(), 4), RecoveryCancellation.NONE))
        // Two errors consume all conventional margin and contradict the fully known bit system.
        val noMargin = block(IntArray(8), error = true)
        noMargin.codewords[1] = noMargin.codewords[1] xor 1
        assertNull(SingleSegmentRetryProbe.solveBlocks(DeinterleavedQr(listOf(noMargin), emptyArray(), 4), RecoveryCancellation.NONE))
    }
}
