package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class ImageErasureSuccessTest {
    @Test fun recoversSourceWhenOriginalRsReturnsDifferentCodewords() {
        val matrix = imageQr("Q180651138-8099", 1, ErrorCorrectionLevel.L, 2)
        val original = CodewordExtractor.extract(matrix, 1, 2)
        val expected = original.codewords.map { it.value }.toIntArray()
        erase(matrix, original, listOf(11, 12, 13, 20, 22))
        flip(matrix, original, 0, 3)
        flip(matrix, original, 10, 0)

        val extraction = CodewordExtractor.extract(matrix, 1, 2)
        val blocks = QrBlockInterleaver.deinterleave(extraction, 1, ErrorCorrectionLevel.L)
        val diagnostic = ConventionalRsDiagnostic.analyze(blocks)
        assertTrue(diagnostic.succeeded)
        assertFalse(expected.contentEquals(diagnostic.correctedInterleaved))
        val retried = retryImageErasures(blocks.blocks.single(), blocks.ecSymbolsPerBlock,
            imageWordMargins(blocks, extraction, matrix).single(), diagnostic.blocks.single(),
            ReedSolomonErasures(), RecoveryCancellation.NONE)
        assertTrue(retried.any { it.corrected.contentEquals(diagnostic.correctedInterleaved) && it.imageErasures.isEmpty() })
        assertTrue(retried.any { it.corrected.contentEquals(expected) && it.imageErasures.toSet() == setOf(0, 10) })

        val candidate = RecoveryEngine().recover(matrix,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 2),
            maxKnownDataEccMismatches = 2, listDecodingAdditionalErrors = 0, knownBitErrorLimit = 0)
            .single { it.correctedInterleaved.contentEquals(expected) }
        assertEquals(setOf(0, 10), candidate.repairInfo.filter { it.temporaryImageErasure }.map { it.rawIndex }.toSet())
        assertEquals(2, candidate.knownBitMismatchesAfterCorrection)
    }

    @Test fun combinesRetriedSuccessfulBlockWithFailedAndIntactBlocks() {
        val matrix = imageQr("Q510214024-4474", 5, ErrorCorrectionLevel.Q, 1)
        val original = CodewordExtractor.extract(matrix, 5, 1)
        val expected = original.codewords.map { it.value }.toIntArray()
        val originalBlocks = QrBlockInterleaver.deinterleave(original, 5, ErrorCorrectionLevel.Q)
        erase(matrix, original, listOf(0, 2, 4, 6, 10, 14, 16, 20, 28, 34, 38, 48, 50, 52, 56, 58,
            60, 68, 80, 84, 86, 96, 98, 100, 102, 104, 106, 108, 110, 118, 126, 130))
        for ((word, bit) in listOf(26 to 3, 62 to 2, 70 to 3, 116 to 4)) flip(matrix, original, word, bit)

        val extraction = CodewordExtractor.extract(matrix, 5, 1)
        val blocks = QrBlockInterleaver.deinterleave(extraction, 5, ErrorCorrectionLevel.Q)
        val diagnostic = ConventionalRsDiagnostic.analyze(blocks)
        assertEquals(ConventionalRsStatus.SUCCESS, diagnostic.blocks[0].status)
        assertEquals(ConventionalRsStatus.CORRECTION_FAILED, diagnostic.blocks[2].status)
        val erased = blocks.blocks[0].unknownMasks.indices.filter { blocks.blocks[0].unknownMasks[it] != 0 }.toIntArray()
        val originalResult = ReedSolomonErasures().decode(blocks.blocks[0].codewords, blocks.ecSymbolsPerBlock, erased)
        assertFalse(originalResult.corrected.contentEquals(originalBlocks.blocks[0].codewords))

        val candidate = RecoveryEngine().recover(matrix,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.Q, 1),
            maxKnownDataEccMismatches = 4, listDecodingAdditionalErrors = 0, knownBitErrorLimit = 0)
            .single { it.correctedInterleaved.contentEquals(expected) }
        assertEquals(setOf(0, 2), candidate.actualBlockMethods.filter { it.imageErasures.size == 2 }.map { it.blockIndex }.toSet())
        assertTrue(candidate.actualBlockMethods.filter { it.blockIndex in setOf(1, 3) }.all { it.imageErasures.isEmpty() })
        assertEquals(4, candidate.knownBitMismatchesAfterCorrection)
    }

    @Test fun retainsOriginalBlockWhenNoImageRetryCanRun() {
        val rs = ReedSolomonErasures()
        val expected = rs.encode(IntArray(19) { it }, 7)
        for (erasureCount in listOf(0, 7)) {
            val observed = expected.copyOf()
            val unknown = IntArray(expected.size)
            repeat(erasureCount) { observed[it] = 0; unknown[it] = 255 }
            val block = RsBlockWork(19, observed, unknown)
            val diagnostic = ConventionalRsBlockDiagnostic(0, expected.size, 19, 7, erasureCount,
                0, 7 - erasureCount, ConventionalRsStatus.SUCCESS)
            val result = retryImageErasures(block, 7, IntArray(expected.size) { 90 }, diagnostic,
                rs, RecoveryCancellation.NONE).single()
            assertArrayEquals(expected, result.corrected)
            assertTrue(result.imageErasures.isEmpty())
            assertTrue(result.alternativeDerivations.isEmpty())
        }
    }

    @Test fun mergesOriginalAndImageErasureDerivationsForTheSameBlock() {
        val rs = ReedSolomonErasures()
        val expected = rs.encode(IntArray(19) { it }, 7)
        val block = RsBlockWork(19, expected, IntArray(expected.size))
        val diagnostic = ConventionalRsBlockDiagnostic(0, expected.size, 19, 7, 0, 0, 7, ConventionalRsStatus.SUCCESS)
        val margins = IntArray(expected.size) { if (it < 2) 1 else 90 }
        val cached = rs.decode(expected, 7, intArrayOf())
        val result = retryImageErasures(block, 7, margins, diagnostic, rs, RecoveryCancellation.NONE, cached).single()
        assertArrayEquals(expected, result.corrected)
        assertTrue(result.imageErasures.isEmpty())
        assertEquals(setOf(listOf(0), listOf(1)), result.alternativeDerivations.map { it.imageErasures }.toSet())
    }

    private fun imageQr(text: String, version: Int, ecc: ErrorCorrectionLevel, mask: Int): QrMatrix {
        val qr = Encoder.encode(text, ecc,
            mapOf(EncodeHintType.QR_VERSION to version, EncodeHintType.QR_MASK_PATTERN to mask)).matrix
        return QrMatrix(qr.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) {
                set(x, y, if (qr[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
                setImageMargin(x, y, 90)
            }
        }
    }

    private fun erase(matrix: QrMatrix, extraction: CodewordExtraction, words: List<Int>) {
        for (word in words) for (cell in extraction.codewords[word].coordinates) {
            matrix.set(cell.x, cell.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
    }

    private fun flip(matrix: QrMatrix, extraction: CodewordExtraction, word: Int, bit: Int) {
        val cell = extraction.codewords[word].coordinates[bit]
        val value = if (matrix[cell.x, cell.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        matrix.set(cell.x, cell.y, value, 1f, ModuleSource.IMAGE)
        matrix.setImageMargin(cell.x, cell.y, 2)
    }
}
