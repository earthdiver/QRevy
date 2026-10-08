package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class EciAssignmentValidationTest {
    private fun payload(assignment: Int, width: Int = 24): ByteArray = PayloadBits().apply {
        put(7, 4)
        put(assignment or when (width) { 16 -> 0x8000; 24 -> 0xC00000; else -> 0 }, width)
        put(4, 4); put(1, 8); put(65, 8)
    }.finish()

    @Test fun acceptsEveryEciWidthWithinTheAssignmentRange() {
        for ((value, width) in listOf(0 to 8, 127 to 8, 128 to 16, 16383 to 16,
            16384 to 24, 999999 to 24)) {
            assertTrue("ECI $value", StrictQrPayloadValidator.isValid(payload(value, width), 1))
        }
    }

    @Test fun rejectsOutOfRangeEciInCompleteStreamsAndPrefixes() {
        for (value in listOf(1000000, 2097151)) {
            val data = payload(value)
            assertFalse(StrictQrPayloadValidator.isValid(data, 1))
            assertFalse(StrictQrPayloadValidator.canBePrefix(data, 1))
            val inspection = StrictQrPayloadValidator.inspectSegments(data, 1, IntArray(data.size), data.size * 8)
            assertFalse(inspection.valid)
            assertEquals(StrictQrPayloadValidator.Issue.INVALID_ECI, inspection.segments.single().issue)
            assertEquals(28, inspection.segments.single().endBit)
        }
    }

    @Test fun waitsForACompleteEciFieldBeforeRejectingItsRange() {
        val data = payload(1000000)
        for (length in 1..3) {
            val prefix = data.copyOf(length)
            assertTrue(StrictQrPayloadValidator.canBePrefix(prefix, 1))
            assertFalse(StrictQrPayloadValidator.isValid(prefix, 1))
            val inspection = StrictQrPayloadValidator.inspectSegments(prefix, 1, IntArray(length), length * 8)
            assertEquals(StrictQrPayloadValidator.Issue.INCOMPLETE, inspection.segments.single().issue)
        }
    }

    @Test fun recoveryRetainsUnsupportedValidEciButRejectsOutOfRangeAssignments() {
        val valid = payload(999999)
        val candidate = recoverPayload(valid).single()
        assertNull(candidate.text)
        assertTrue(candidate.isSelectableRecovery)
        assertArrayEquals(valid, candidate.dataBytes)
        for (value in listOf(1000000, 2097151)) {
            assertTrue("ECI $value must not become a recovery candidate", recoverPayload(payload(value)).isEmpty())
        }
    }
}
