package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RsShortfallTest {
    @Test fun countsSmallestBitDecisionsNeededToFitErasureCapacity() {
        val block = RsBlockWork(
            numDataCodewords = 3,
            codewords = IntArray(5),
            unknownMasks = intArrayOf(0b00000001, 0b00000011, 0b00001111, 0, 0),
        )

        assertEquals(0, minimumRsShortfallBits(listOf(block), ecSymbolsPerBlock = 3))
        assertEquals(1, minimumRsShortfallBits(listOf(block), ecSymbolsPerBlock = 2))
        assertEquals(3, minimumRsShortfallBits(listOf(block), ecSymbolsPerBlock = 1))
    }
}
