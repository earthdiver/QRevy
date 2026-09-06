package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodewordHexTest {
    @Test fun formatsMaskedAndUnmaskedBytesWithUnknownMarker() {
        val matrix = QrMatrix(21)
        val coords = (0 until 8).map { Coord(it, 9) }
        val bits = listOf(1, 0, 1, 0, 0, 1, 0, 1)
        coords.zip(bits).forEach { (c, bit) ->
            matrix.set(c.x, c.y, if (bit == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtraction(listOf(PartialCodeword(0x3C, 0, coords)))
        assertEquals("0000  A5", CodewordHex.masked(matrix, extraction))
        assertEquals("0000  3C", CodewordHex.unmasked(extraction))

        matrix.set(coords[2].x, coords[2].y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        assertTrue(CodewordHex.masked(matrix, extraction).endsWith("??"))
    }

    @Test fun rendersCorrectedBytesInBothMaskStates() {
        val coords = (0 until 8).map { Coord(it, 9) }
        val extraction = CodewordExtraction(listOf(PartialCodeword(0, 0xFF, coords)))
        val corrected = intArrayOf(0x3C)

        assertEquals("0000  3C", CodewordHex.corrected(corrected))
        assertEquals("0000  69", CodewordHex.correctedMasked(corrected, extraction, 0))
        assertTrue(CodewordHex.correctedMaskedBinary(corrected, extraction, 0).endsWith("01101001"))
    }

    @Test fun recoveredContentUsesValidatedTextWithoutReDecodingItsBytes() {
        // Shift_JIS bytes decoded as UTF-8 would contain replacement characters. The data view
        // must use the text already decoded by the QR parser instead.
        val shiftJisPayload = byteArrayOf(0x40, 0x48, 0x93.toByte(), 0xFA.toByte())
        val rendered = CodewordHex.interpretedRecovered(shiftJisPayload, 1, "日本語")

        assertTrue(rendered.contains("日本語"))
        assertTrue(!rendered.contains('\uFFFD'))
    }

    @Test fun recoveredContentFallsBackToDeinterleavedDataWhenDecodeIsUnavailable() {
        // Byte mode, count 1, payload 'A'.
        val data = byteArrayOf(0x40, 0x14, 0x10)

        assertTrue(CodewordHex.interpretedRecovered(data, 1, null).endsWith("A"))
    }
}
