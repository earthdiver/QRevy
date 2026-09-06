package io.github.earthdiver.qrrepair.core

import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.ZxingPayloadBridge
import org.junit.Assert.*
import org.junit.Test

class PayloadPrefixRecoveryRegressionTest {
    @Test fun everyBytePrefixOfValidMultiSegmentStreamsRemainsPossible() {
        // Vary segment endings to cover mode indicators split by byte boundaries.
        for (count in 1..36) {
            val data = numericThenByte(count)
            assertTrue(StrictQrPayloadValidator.isValid(data, 3))
            for (length in 0..data.size) {
                assertTrue("count=$count prefix=$length",
                    StrictQrPayloadValidator.canBePrefix(data.copyOf(length), 3))
            }
        }
    }

    @Test fun completeStreamsStillRejectNonzeroShortenedTerminatorsAndBadPadding() {
        // Numeric count=2, value=12 ends at bit 21. The last three bits must be zero.
        val shortened = byteArrayOf(0x10, 0x08, 0x60)
        assertTrue(StrictQrPayloadValidator.isValid(shortened, 1))
        assertFalse(StrictQrPayloadValidator.isValid(shortened.copyOf().also { it[2] = 0x61 }, 1))
        val badPadding = numericThenByte(36).also { it[it.lastIndex] = 0 }
        assertFalse(StrictQrPayloadValidator.isValid(badPadding, 3))
        assertFalse(StrictQrPayloadValidator.canBePrefix(badPadding, 3))
        assertFalse(StrictQrPayloadValidator.canBePrefix(byteArrayOf(0x60), 3))
    }

    @Test fun undamagedV3QWithModeSplitAcrossRsBlocksRecovers() {
        val data = numericThenByte(36)
        val expected = "0".repeat(36) + "A"
        assertEquals(expected, ZxingPayloadBridge.decodeData(data, 3, ErrorCorrectionLevel.Q, null).text)
        val matrix = QrMatrix(29)
        StructuralRepair.apply(matrix, ErrorCorrectionLevel.Q, 0)
        val extraction = CodewordExtractor.extract(matrix, 3, 0)
        val de = QrBlockInterleaver.deinterleave(extraction, 3, ErrorCorrectionLevel.Q)
        assertEquals(listOf(17, 17), de.blocks.map { it.numDataCodewords })
        var offset = 0
        val blocks = de.blocks.map { block ->
            IntArray(block.codewords.size).also { words ->
                repeat(block.numDataCodewords) { words[it] = data[offset++].toInt() and 255 }
                ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256).encode(words, de.ecSymbolsPerBlock)
            }
        }
        val raw = QrBlockInterleaver.reinterleave(de, blocks)
        raw.forEachIndexed { index, value ->
            extraction.codewords[index].coordinates.forEachIndexed { bit, coord ->
                val masked = ((value ushr (7 - bit)) and 1) xor
                    (if (CodewordExtractor.maskApplies(0, coord.x, coord.y)) 1 else 0)
                matrix.set(coord.x, coord.y, if (masked == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
            }
        }
        val candidates = RecoveryEngine().recover(matrix)
        assertTrue(candidates.any { it.text == expected && it.dataBytes.contentEquals(data) })
    }

    private fun numericThenByte(count: Int): ByteArray {
        val bits = StringBuilder()
        fun put(value: Int, width: Int) { bits.append(value.toString(2).padStart(width, '0')) }
        put(1, 4); put(count, 10)
        repeat(count / 3) { put(0, 10) }
        when (count % 3) { 1 -> put(0, 4); 2 -> put(0, 7) }
        put(4, 4); put(1, 8); put(0x41, 8); put(0, 4)
        while (bits.length % 8 != 0) bits.append('0')
        var pad = 0
        while (bits.length < 34 * 8) put(if (pad++ % 2 == 0) 0xEC else 0x11, 8)
        return ByteArray(34) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
    }
}
