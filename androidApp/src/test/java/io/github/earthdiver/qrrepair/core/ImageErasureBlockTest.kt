package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class ImageErasureBlockTest {
    @Test fun recoversWithOneTemporaryErasureWhenAnotherWeakWordIsCorrect() {
        val text = "abcdefghijklm"
        val matrix = postTerminatorMatrix(PayloadBits().apply { byteSegment(text) }.finish())
        val words = CodewordExtractor.extract(matrix, 1, 0).codewords
        val expected = words.map { it.value }.toIntArray()

        // V1-L has seven parity words. Four erasures leave room for one erasure plus one error.
        for (index in listOf(5, 6, 19, 20)) for (cell in words[index].coordinates) {
            matrix.set(cell.x, cell.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        for ((index, bit) in listOf(22 to 0, 23 to 1)) {
            val cell = words[index].coordinates[bit]
            val flipped = if (matrix[cell.x, cell.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
            matrix.set(cell.x, cell.y, flipped, 1f, ModuleSource.IMAGE)
        }
        for ((index, word) in words.withIndex()) {
            val margin = when (index) {
                24 -> 1 // Correct despite the weakest image reading.
                22 -> 2
                else -> 90
            }
            for (cell in word.coordinates) if (matrix[cell.x, cell.y].value != ModuleValue.ERASED) {
                matrix.setImageMargin(cell.x, cell.y, margin)
            }
        }

        val candidate = RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0),
            maxKnownDataEccMismatches = 2, listDecodingAdditionalErrors = 0, knownBitErrorLimit = 0).single { it.text == text }
        assertArrayEquals(expected, candidate.correctedInterleaved)
        assertEquals(listOf(22), candidate.actualBlockMethods.single().imageErasures)
        assertEquals(listOf(22), candidate.repairInfo.filter { it.temporaryImageErasure }.map { it.rawIndex })
    }

    @Test fun combinesRecoveredShortAndLongBlocksWithIntactBlocks() {
        val format = ErrorCorrectionLevel.Q
        val qr = Encoder.encode("Interleaved image evidence", format,
            mapOf(EncodeHintType.QR_VERSION to 5, EncodeHintType.QR_MASK_PATTERN to 3)).matrix
        val matrix = QrMatrix(qr.width)
        for (y in 0 until qr.height) for (x in 0 until qr.width) {
            matrix.set(x, y, if (qr[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            matrix.setImageMargin(x, y, 90)
        }
        val original = CodewordExtractor.extract(matrix, 5, 3)
        val de = QrBlockInterleaver.deinterleave(original, 5, format)
        val damaged = setOf(de.blocks.indices.minBy { de.blocks[it].numDataCodewords },
            de.blocks.indices.maxBy { de.blocks[it].numDataCodewords })
        assertEquals(2, damaged.size)
        for ((raw, word) in original.codewords.withIndex()) {
            val loc = requireNotNull(de.rawLocations[raw])
            if (loc.blockIndex !in damaged) continue
            if (loc.localIndex < de.ecSymbolsPerBlock - 2) {
                for (c in word.coordinates) matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            } else if (loc.localIndex < de.ecSymbolsPerBlock) {
                val c = word.coordinates.last()
                val flipped = if (matrix[c.x, c.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
                matrix.set(c.x, c.y, flipped, 1f, ModuleSource.IMAGE)
                matrix.setImageMargin(c.x, c.y, 2)
            }
        }
        val candidate = RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(format, 3),
            maxKnownDataEccMismatches = 4, listDecodingAdditionalErrors = 0).single {
            it.correctedInterleaved.contentEquals(original.codewords.map { word -> word.value }.toIntArray())
        }
        assertEquals(4, candidate.knownBitMismatchesAfterCorrection)
        assertEquals(damaged, candidate.actualBlockMethods.filter { it.imageErasures.size == 2 }.map { it.blockIndex }.toSet())
    }
}
