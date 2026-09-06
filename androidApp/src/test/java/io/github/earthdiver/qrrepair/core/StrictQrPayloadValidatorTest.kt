package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictQrPayloadValidatorTest {
    @Test fun rejectsParsedPrefixWithNonCanonicalPadding() {
        val valid = stream {
            put(4, 4); put(1, 8); put(0x41, 8)
            terminateAndPad(6)
        }
        val damagedPadding = valid.copyOf().also { it[5] = 0x10 }

        assertTrue(StrictQrPayloadValidator.isValid(valid, version = 1))
        assertFalse(StrictQrPayloadValidator.isValid(damagedPadding, version = 1))
    }

    @Test fun acceptsPayloadThatExactlyConsumesCapacityWithoutTerminator() {
        // Version 1 numeric header (14 bits) followed by one 10-bit triplet. No bits remain.
        val full = stream { put(1, 4); put(3, 10); put(123, 10) }

        assertTrue(StrictQrPayloadValidator.isValid(full, version = 1))
    }

    @Test fun acceptsMultipleSegmentsBeforeCanonicalFiller() {
        // Numeric "1", then byte "A", terminator, alignment zeros, EC/11 padding.
        val data = stream {
            put(1, 4); put(1, 10); put(1, 4)
            put(4, 4); put(1, 8); put(0x41, 8)
            terminateAndPad(8)
        }

        assertTrue(StrictQrPayloadValidator.isValid(data, version = 1))
    }

    @Test fun prefixValidationRejectsOnlyProvenInvalidBranches() {
        val incompleteByteSegment = byteArrayOf(0x40, 0x24, 0x10)
        val invalidMode = byteArrayOf(0x60)

        assertTrue(StrictQrPayloadValidator.canBePrefix(incompleteByteSegment, version = 1))
        assertFalse(StrictQrPayloadValidator.canBePrefix(invalidMode, version = 1))
    }

    @Test fun rejectsStructuredAppendAfterPayloadData() {
        val data = stream {
            put(4, 4); put(1, 8); put(0x41, 8)
            put(3, 4); put(0x0123, 16)
            terminateAndPad(8)
        }

        assertFalse(StrictQrPayloadValidator.isValid(data, version = 1))
    }

    @Test fun rejectsRepeatedFnc1Modes() {
        val data = stream {
            put(5, 4)
            put(5, 4)
            put(4, 4); put(1, 8); put(0x41, 8)
            terminateAndPad(6)
        }

        assertFalse(StrictQrPayloadValidator.isValid(data, version = 1))
    }

    @Test fun acceptsStructuredAppendThenFnc1ThenData() {
        val data = stream {
            put(3, 4); put(0x0123, 16)
            put(5, 4)
            put(4, 4); put(1, 8); put(0x41, 8)
            terminateAndPad(8)
        }

        assertTrue(StrictQrPayloadValidator.isValid(data, version = 1))
    }

    private fun stream(block: BitWriter.() -> Unit): ByteArray = BitWriter().apply(block).bytes()

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun put(value: Int, width: Int) {
            for (bit in width - 1 downTo 0) bits += (value ushr bit) and 1
        }
        fun terminateAndPad(byteCount: Int) {
            repeat(minOf(4, byteCount * 8 - bits.size)) { bits += 0 }
            while (bits.size % 8 != 0) bits += 0
            var pad = 0
            while (bits.size < byteCount * 8) put(if ((pad++ and 1) == 0) 0xEC else 0x11, 8)
        }
        fun bytes(): ByteArray = ByteArray(bits.size / 8) { index ->
            var value = 0
            repeat(8) { value = (value shl 1) or bits[index * 8 + it] }
            value.toByte()
        }
    }
}
