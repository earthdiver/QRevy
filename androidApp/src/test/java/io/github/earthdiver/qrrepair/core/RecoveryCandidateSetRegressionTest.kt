package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryCandidateSetRegressionTest {
    private fun fixture(): Pair<QrMatrix, QrFormatConstraint> {
        val qr = Encoder.encode("RECOVERY EVIDENCE", ErrorCorrectionLevel.H,
            mapOf(EncodeHintType.QR_VERSION to 2))
        return QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        } to QrFormatConstraint(qr.ecLevel, qr.maskPattern)
    }

    @Test fun paddingRecoveredThroughTwoRoutesIsOneResult() {
        val (matrix, format) = fixture()
        val extraction = CodewordExtractor.extract(matrix, 2, format.dataMask!!)
        val de = QrBlockInterleaver.deinterleave(extraction, 2, format.ecLevel!!)
        val raw = de.rawLocations.indexOfLast { it != null &&
            it.localIndex == de.blocks[it.blockIndex].numDataCodewords - 1 }
        val coord = extraction.codewords[raw].coordinates[7]
        matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        val candidates = RecoveryEngine().recover(matrix, formatConstraint = format)
        assertEquals(1, candidates.size)
        assertEquals("RECOVERY EVIDENCE", candidates.single().text)
        val routes = listOf(candidates.single()) + candidates.single().alternativeDerivations
        assertTrue(routes.any { it.constraintFilledBits > 0 })
        assertTrue(routes.any { it.constraintFilledBits == 0 })
    }

    @Test fun guidanceExcludesUnvalidatedDuplicatesAndIncludesSelectedResultBeyondTheLimit() {
        val (matrix, format) = fixture()
        val candidate = RecoveryEngine().recover(matrix, formatConstraint = format).first()
        val results = (0..16).map { candidate.copy(correctedInterleaved = intArrayOf(it)) }
        val sample = guidanceCandidates(listOf(candidate.copy(text = null)) + results + results, results.last())
        assertEquals(17, sample.total)
        assertEquals(16, sample.candidates.size)
        assertTrue(sample.candidates.all { it.payloadParsed })
        assertTrue(sample.candidates.any { it === results.last() })
        assertEquals(0, guidanceCandidates(listOf(candidate.copy(text = null)), null).total)
        assertTrue(guidanceCandidates(listOf(candidate.copy(text = null)), null).candidates.isEmpty())
    }

    @Test fun equalTextDoesNotMergeDifferentMasksOrBinaryCodewords() {
        val (matrix, format) = fixture()
        val candidate = RecoveryEngine().recover(matrix, formatConstraint = format).first()
        val otherMask = candidate.copy(dataMask = (candidate.dataMask + 1) % 8)
        val otherBytes = candidate.copy(correctedInterleaved = candidate.correctedInterleaved.copyOf().also { it[0] = it[0] xor 1 })
        val retained = BoundedRecoveryCandidates(RecoveryRetentionBudget())
        listOf(candidate, candidate.copy(), otherMask, otherBytes).forEach(retained::add)
        val results = retained.results()
        assertEquals(3, results.size)
        assertEquals(1, results.first().alternativeDerivations.size)
        assertEquals(4, results.sumOf { 1 + it.alternativeDerivations.size })
    }

    @Test fun limitedConsensusAndRecommendationStateTheirScope() {
        val source = QrMatrix(21)
        val coord = CodewordExtractor.extract(source, 1, 0).codewords.first().coordinates.first()
        val matrices = List(16) { i -> source.copy().apply {
            set(coord.x, coord.y, if (i == 15) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.RECOVERED)
        } }
        val confidence = RepairGuidance.confidenceMap(source, matrices.first(), matrices, emptyMap(), 17)
        assertTrue(confidence.getValue(coord).detailJa.contains("比較対象16候補（検証済み全17候補）"))
        val rec = RepairGuidance.recommendNextCell(source, matrices, confidence, 17)!!
        assertEquals(coord, rec.coord)
        assertTrue(rec.reasonEn.contains("16 compared candidates out of 17"))
    }


}
