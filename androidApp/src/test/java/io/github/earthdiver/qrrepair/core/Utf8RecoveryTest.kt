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

    @Test fun explicitUtf8RejectsMalformedBytesWithEitherAssumptionSetting() {
        for (bytes in listOf(intArrayOf(0xff), intArrayOf(0x80), intArrayOf(0xc0, 0x80),
            intArrayOf(0xed, 0xa0, 0x80), intArrayOf(0xf4, 0x90, 0x80, 0x80), intArrayOf(0xe3, 0x81))) {
            val data = PayloadBits().apply { put(7, 4); put(26, 8); byteSegment(*bytes) }.finish()
            assertFalse(StrictQrPayloadValidator.isValid(data, 1))
            for (assume in listOf(false, true)) {
                assertNotNull(RecoveryPayloadChecks(assume).rejection(data, 1))
                assertTrue(recoverPayload(data, assumeUtf8 = assume).isEmpty())
            }
        }
    }

    @Test fun explicitUtf8PrunesInvalidPrefixesAndPreservesValidSegmentBytes() {
        for (version in listOf(1, 10, 27)) {
            fun stream(second: Int) = PayloadBits().apply {
                put(7, 4); put(26, 8)
                put(4, 4); put(3, if (version < 10) 8 else 16)
                put(0xe3, 8); put(second, 8); put(0x82, 8)
            }.finish()
            val valid = stream(0x81)
            for (length in 0..valid.size) assertTrue(StrictQrPayloadValidator.canBePrefix(valid.copyOf(length), version))
            assertArrayEquals(byteArrayOf(0xe3.toByte(), 0x81.toByte(), 0x82.toByte()),
                StrictQrPayloadValidator.byteSegments(valid, version)!!.single().second)
            val invalidPrefix = stream(0x41).copyOf(if (version < 10) 5 else 6)
            for (assume in listOf(false, true)) assertFalse(RecoveryPayloadChecks(assume).acceptPrefix(invalidPrefix, version))
        }
    }

    @Test fun explicitUtf8TracksEncodingChangesWithoutClaimingAnAssumption() {
        val data = PayloadBits().apply {
            put(7, 4); put(3, 8); byteSegment(0xff)
            put(7, 4); put(26, 8); byteSegment(0xe3, 0x81, 0x82)
            put(7, 4); put(3, 8); byteSegment(0xff)
        }.finish()
        for (assume in listOf(false, true)) {
            val candidate = recoverPayload(data, assumeUtf8 = assume).single()
            assertEquals("\u00ff\u3042\u00ff", candidate.text)
            assertFalse(candidate.utf8AssumptionApplied)
        }
    }


    @Test fun explicitUtf8SpansCompactionSegmentsButEndsBeforeAnotherEncoding() {
        val split = PayloadBits().apply {
            put(7, 4); put(26, 8); byteSegment(0xc2); byteSegment(0xa2)
        }.finish()
        assertTrue(StrictQrPayloadValidator.isValid(split, 1))
        val interrupted = PayloadBits().apply {
            put(7, 4); put(26, 8); byteSegment(0xc2)
            put(7, 4); put(3, 8); byteSegment(0xa2)
        }.finish()
        assertFalse(StrictQrPayloadValidator.isValid(interrupted, 1))
        val numericContinuation = PayloadBits().apply {
            put(7, 4); put(26, 8); byteSegment(0xc2)
            put(1, 4); put(1, 10); put(7, 4)
        }.finish()
        assertFalse(StrictQrPayloadValidator.isValid(numericContinuation, 1))
        // E7 9C is compacted in Kanji mode; 8B completes the UTF-8 character in Byte mode.
        val mixed = PayloadBits().apply {
            put(7, 4); put(26, 8); put(8, 4); put(1, 8); put(0x1cdc, 13)
            byteSegment(0x8b)
        }.finish()
        assertTrue(StrictQrPayloadValidator.isValid(mixed, 1))
    }


    @Test fun explicitUtf8FragmentsKeepPossibleCharactersAcrossStructuredAppendSymbols() {
        fun fragment(sequence: Int, vararg bytes: Int) = PayloadBits().apply {
            put(3, 4); put(sequence, 8); put(0x60, 8)
            put(7, 4); put(26, 8); byteSegment(*bytes)
        }.finish()
        assertTrue(StrictQrPayloadValidator.isValid(fragment(0x01, 0xc2), 1))
        assertTrue(StrictQrPayloadValidator.isValid(fragment(0x11, 0xa2), 1))
        assertFalse(StrictQrPayloadValidator.isValid(fragment(0x01, 0xff), 1))
        assertFalse(StrictQrPayloadValidator.isValid(fragment(0x11, 0xff), 1))
        assertFalse(StrictQrPayloadValidator.isValid(fragment(0x01, 0xa2), 1))
        assertFalse(StrictQrPayloadValidator.isValid(fragment(0x11, 0xc2), 1))
    }

}
