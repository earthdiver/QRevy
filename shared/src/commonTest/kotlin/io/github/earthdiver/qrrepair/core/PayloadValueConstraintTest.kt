package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayloadValueConstraintTest {
    @Test
    fun numericLastGroupExcludesValuesAboveNine() {
        val fixture = singleSegment(mode = 1, countBits = 10, count = 1, payloadValue = 5, payloadBits = 4)
        // ?1?? permits 4..7 when the QR numeric range 0..9 is applied; values 12..15 are invalid.
        fixture.erasePayloadOffsets(0, 2, 3)

        val result = PayloadConstraintSolver.apply(fixture.deinterleaved, version = 1)

        assertEquals(0, fixture.bit(14))
        assertTrue(result.filled.any { it.dataBitIndex == 14 && it.kind == PayloadConstraintSolver.ConstraintKind.DATA_VALUE })
    }

    @Test
    fun alphanumericLastGroupExcludesValuesAboveFortyFour() {
        val fixture = singleSegment(mode = 2, countBits = 9, count = 1, payloadValue = 20, payloadBits = 6)
        // ?1???? permits 16..31; values 48..63 are outside the 45-character alphabet.
        fixture.erasePayloadOffsets(0, 2, 3, 4, 5)

        val result = PayloadConstraintSolver.apply(fixture.deinterleaved, version = 1)

        assertEquals(0, fixture.bit(13))
        assertTrue(result.filled.any { it.dataBitIndex == 13 && it.kind == PayloadConstraintSolver.ConstraintKind.DATA_VALUE })
    }

    @Test
    fun propagatedValueBitsMatchExhaustiveCompatibleValues() {
        for ((mode, width, maximum) in listOf(Triple(1, 4, 9), Triple(2, 6, 44))) {
            val header = if (mode == 1) 14 else 13
            for (original in 0..maximum) for (unknown in 0 until (1 shl width)) {
                val fixture = singleSegment(mode, header - 4, 1, original, width)
                fixture.erasePayloadOffsets(*(0 until width).filter {
                    unknown and (1 shl (width - 1 - it)) != 0
                }.toIntArray())
                val compatible = (0..maximum).filter { (it xor original) and unknown.inv() == 0 }
                val expected = (0 until width).mapNotNull { offset ->
                    val bit = 1 shl (width - 1 - offset)
                    val values = compatible.map { if (it and bit == 0) 0 else 1 }.distinct()
                    if (unknown and bit != 0 && values.size == 1) (header + offset) to values.single() else null
                }
                val result = PayloadConstraintSolver.apply(fixture.deinterleaved, version = 1)
                assertEquals(expected, result.filled.filter {
                    it.kind == PayloadConstraintSolver.ConstraintKind.DATA_VALUE
                }.map { it.dataBitIndex to it.value })
            }
        }
    }

    private fun singleSegment(
        mode: Int,
        countBits: Int,
        count: Int,
        payloadValue: Int,
        payloadBits: Int,
    ): Fixture {
        val bits = mutableListOf<Int>()
        fun put(value: Int, width: Int) {
            for (i in width - 1 downTo 0) bits += (value shr i) and 1
        }
        put(mode, 4)
        put(count, countBits)
        val payloadStart = bits.size
        put(payloadValue, payloadBits)
        put(0, 4)
        while (bits.size % 8 != 0) bits += 0
        var padIndex = 0
        while (bits.size < 80) put(if ((padIndex++ and 1) == 0) 0xEC else 0x11, 8)

        val bytes = IntArray(10)
        for (byteIndex in bytes.indices) for (offset in 0..7) {
            bytes[byteIndex] = (bytes[byteIndex] shl 1) or bits[byteIndex * 8 + offset]
        }
        val block = RsBlockWork(bytes.size, bytes, IntArray(bytes.size))
        return Fixture(DeinterleavedQr(listOf(block), emptyArray(), 0), block, payloadStart)
    }

    private class Fixture(
        val deinterleaved: DeinterleavedQr,
        private val block: RsBlockWork,
        private val payloadStart: Int,
    ) {
        fun erasePayloadOffsets(vararg offsets: Int) {
            for (offset in offsets) {
                val bitIndex = payloadStart + offset
                val byteIndex = bitIndex / 8
                val mask = 1 shl (7 - bitIndex % 8)
                block.codewords[byteIndex] = block.codewords[byteIndex] and mask.inv()
                block.unknownMasks[byteIndex] = block.unknownMasks[byteIndex] or mask
            }
        }

        fun bit(bitIndex: Int): Int =
            (block.codewords[bitIndex / 8] shr (7 - bitIndex % 8)) and 1
    }
}
