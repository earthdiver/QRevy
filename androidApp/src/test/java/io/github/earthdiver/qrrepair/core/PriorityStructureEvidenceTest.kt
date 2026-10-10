package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityStructureEvidenceTest {
    @Test
    fun priorityHeaderPayloadAndPaddingGuessesPreserveOriginalSuffixEvidence() {
        verifyPriorityEvidence(version = 1, ecLevel = ErrorCorrectionLevel.M, mismatchLimit = 0)
    }

    @Test
    fun originalSuffixEvidenceIsPreservedAcrossBlocksAndAlternativeDerivations() {
        verifyPriorityEvidence(version = 5, ecLevel = ErrorCorrectionLevel.Q, mismatchLimit = 1)
    }

    @Test
    fun suffixEvidenceWithoutPriorityTrialsStillCountsKnownImageAndManualCells() {
        val fixture = fixture(version = 1, ecLevel = ErrorCorrectionLevel.M)
        fixture.erase(fixture.totalDataBits - 16)
        fixture.erase(fixture.totalDataBits - 8)
        fixture.markManual(fixture.totalDataBits - 1)

        val candidate = fixture.recover(mismatchLimit = 0)

        assertEquals(0, candidate.priorityGuessedBits)
        assertEquals(fixture.totalDataBits - PAYLOAD_END - 2, candidate.constraintEvidenceBits)
        assertEquals(1, candidate.manualDataEccCompared)
        assertArrayEquals(fixture.expectedRaw, candidate.correctedInterleaved)
    }

    private fun verifyPriorityEvidence(version: Int, ecLevel: ErrorCorrectionLevel, mismatchLimit: Int) {
        val fixture = fixture(version, ecLevel)
        val priorityBits = listOf(0, 16, fixture.totalDataBits - 16)
        priorityBits.forEach { fixture.erase(it, priority = true) }
        fixture.erase(fixture.totalDataBits - 8)
        fixture.markManual(fixture.totalDataBits - 1)

        val candidate = fixture.recover(mismatchLimit)

        assertEquals(priorityBits.map(fixture::coordinate).toSet(), candidate.priorityAssignments.keys)
        assertTrue(candidate.constraintFilledBits > 0)
        if (version > 1) {
            assertTrue(fixture.blockCount > 1)
            assertTrue(candidate.alternativeDerivations.isNotEmpty())
        }
        val routes = listOf(candidate) + candidate.alternativeDerivations
        for (route in routes) {
            assertEquals(2, route.inferredMode)
            assertEquals(5, route.inferredCount)
            // Header and payload guesses are outside the suffix. Only the two suffix
            // erasures reduce its evidence; the correct manual observation still counts.
            assertEquals(fixture.totalDataBits - PAYLOAD_END - 2, route.constraintEvidenceBits)
            assertEquals(1, route.manualDataEccCompared)
            assertEquals(0, route.knownBitMismatchesAfterCorrection)
            assertArrayEquals(fixture.expectedRaw, route.correctedInterleaved)
        }
    }

    private fun fixture(version: Int, ecLevel: ErrorCorrectionLevel): Fixture {
        val encoded = Encoder.encode("HELLO", ecLevel, mapOf(EncodeHintType.QR_VERSION to version))
        val matrix = QrMatrix(encoded.matrix.width)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            matrix.set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, version, encoded.maskPattern)
        val de = QrBlockInterleaver.deinterleave(extraction, version, ecLevel)
        val dataRawIndices = buildList {
            for ((blockIndex, block) in de.blocks.withIndex()) {
                for (localIndex in 0 until block.numDataCodewords) {
                    add(de.rawLocations.indexOf(RawCodewordLocation(blockIndex, localIndex)))
                }
            }
        }
        assertTrue(dataRawIndices.all { it >= 0 })
        return Fixture(matrix, extraction, dataRawIndices, de.blocks.size,
            QrFormatConstraint(ecLevel, encoded.maskPattern))
    }

    private class Fixture(
        val matrix: QrMatrix,
        private val extraction: CodewordExtraction,
        private val dataRawIndices: List<Int>,
        val blockCount: Int,
        private val format: QrFormatConstraint,
    ) {
        val totalDataBits = dataRawIndices.size * 8
        val expectedRaw = extraction.codewords.map { it.value }.toIntArray()

        fun coordinate(dataBit: Int): Coord =
            extraction.codewords[dataRawIndices[dataBit / 8]].coordinates[dataBit % 8]

        fun erase(dataBit: Int, priority: Boolean = false) {
            val coord = coordinate(dataBit)
            matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER_SOFT)
            matrix.setPrioritySearch(coord.x, coord.y, priority)
        }

        fun markManual(dataBit: Int) {
            val coord = coordinate(dataBit)
            matrix.set(coord.x, coord.y, matrix[coord.x, coord.y].value, 1f, ModuleSource.USER)
        }

        fun recover(mismatchLimit: Int): RecoveryCandidate = RecoveryEngine().recoverWithDiagnostics(
            matrix, formatConstraint = format, displayLockManualBits = true,
            maxKnownDataEccMismatches = mismatchLimit, trustKnownPayloadCount = true,
            listDecodingBudget = ListDecodingBudget(clockMillis = { 0L }),
        ).candidates.first { it.text == "HELLO" }
    }

    companion object {
        // Version 1..9 alphanumeric header (13) + two pairs (22) + final character (6).
        private const val PAYLOAD_END = 41
    }
}
