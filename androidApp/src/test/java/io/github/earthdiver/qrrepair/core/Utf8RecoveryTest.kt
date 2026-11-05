package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class Utf8RecoveryTest {
    private val checks = RecoveryPayloadChecks(assumeUtf8 = true)

    private fun PayloadBits.byteSegment(vararg bytes: Int) {
        put(4, 4)
        put(bytes.size, 8)
        bytes.forEach { put(it, 8) }
    }

    @Test fun prunesInvalidPrefixesBeforeTheByteSegmentOrBlockCombinationIsComplete() {
        // V1 Byte headers occupy 12 bits. The third codeword ends midway through
        // the second payload byte, enough to distinguish a continuation from ASCII.
        fun prefix(second: Int) = PayloadBits().apply { byteSegment(0xe3, second, 0x82, 0x41) }.finish().copyOf(3)
        assertFalse(checks.acceptPrefix(prefix(0x41), 1))
        assertTrue(checks.acceptPrefix(prefix(0x81), 1))
        assertTrue(RecoveryPayloadChecks().acceptPrefix(prefix(0x41), 1))
    }

    @Test fun rejectsMalformedSequencesAndNeverJoinsIncompleteCharactersAcrossSegments() {
        for (bytes in listOf(intArrayOf(0x80), intArrayOf(0xc0, 0x80), intArrayOf(0xe0, 0x9f, 0xbf),
            intArrayOf(0xed, 0xa0, 0x80), intArrayOf(0xf4, 0x90, 0x80, 0x80), intArrayOf(0xe3, 0x81))) {
            val data = PayloadBits().apply { byteSegment(*bytes) }.finish()
            assertEquals(RecoveryCandidateRejection.UTF8_BYTE_SEGMENT, checks.rejection(data, 1))
        }
        val split = PayloadBits().apply { byteSegment(0xc2); byteSegment(0xa2) }.finish()
        assertEquals(RecoveryCandidateRejection.UTF8_BYTE_SEGMENT, checks.rejection(split, 1))
        assertTrue(recoverPayload(split).isNotEmpty())
        assertTrue(recoverPayload(split, assumeUtf8 = true).isEmpty())
    }

    @Test fun defaultByteEncodingAndExplicitEciCanDifferWithinOneCandidate() {
        val data = PayloadBits().apply {
            put(1, 4); put(1, 10); put(7, 4)
            byteSegment(0xe3, 0x81, 0x82, 0xf0, 0x9f, 0x98, 0x80)
            put(7, 4); put(20, 8)
            byteSegment(0x82, 0xa0)
        }.finish()
        val candidate = recoverPayload(data, assumeUtf8 = true).single()
        assertEquals("7\u3042\uD83D\uDE00\u3042", candidate.text)
        assertTrue(candidate.utf8AssumptionApplied)
        val explicitOnly = PayloadBits().apply { put(7, 4); put(3, 8); byteSegment(0xff) }.finish()
        val latin = recoverPayload(explicitOnly, assumeUtf8 = true).single()
        assertFalse(latin.utf8AssumptionApplied)
        assertEquals("\u00ff", latin.text)
    }
}
