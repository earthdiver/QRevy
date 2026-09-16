package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class StructuredAppendRecoveryTest {
    private fun data(sequence: Int, repeated: Boolean = false) = PayloadBits().apply {
        put(3, 4); put(sequence, 8); put(0xA5, 8)
        if (repeated) { put(3, 4); put(sequence, 8); put(0xA5, 8) }
        put(4, 4); put(1, 8); put(65, 8)
    }.finish()

    @Test fun rejectsImpossibleIndexOrCountBeforeRsCandidatesAreAccepted() {
        for (sequence in listOf(0xF1, 0x21, 0x00)) {
            val data = data(sequence)
            assertFalse(StrictQrPayloadValidator.isValid(data, 1))
            assertTrue(recoverPayload(data).isEmpty())
            assertTrue(StrictQrPayloadValidator.canBePrefix(data.copyOf(1), 1))
            assertFalse(StrictQrPayloadValidator.canBePrefix(data.copyOf(2), 1))
        }
        assertFalse(StrictQrPayloadValidator.isValid(data(1, repeated = true), 1))
    }

    @Test fun retainsMetadataForFirstLastAndMaximumParts() {
        for ((sequence, index, total) in listOf(Triple(0x01, 0, 2), Triple(0x11, 1, 2), Triple(0xFF, 15, 16))) {
            val data = data(sequence)
            for (length in 0..data.size) {
                assertTrue(StrictQrPayloadValidator.canBePrefix(data.copyOf(length), 1))
            }
            val candidate = recoverPayload(data).single()
            assertEquals("A", candidate.text)
            assertEquals(StructuredAppendInfo(index, total, 0xA5), candidate.structuredAppend)
            val preview = QrContentPreviewParser.parse(candidate)!!
            assertEquals(candidate.structuredAppend, preview.structuredAppend)
            val display = preview.toDisplayText()
            assertTrue(display.contains("${index + 1}/$total"))
            assertTrue(display.contains("165"))
            assertTrue(display.contains("全体の連結・パリティ照合は未実施"))
        }
    }

    @Test fun fragmentPreviewKeepsRecoveredTextExact() {
        val base = recoverPayload(data(1)).single()
        val candidate = base.copy(text = "CONFIG:T:TYPE;S:Example;P:secret;;")
        val display = QrContentPreviewParser.parse(candidate)!!.toDisplayText()
        assertTrue(display.contains("連結QRの一部"))
        assertFalse(display.contains("••••"))
        assertTrue(display.contains(candidate.text!!))
        assertFalse(display.contains("パスワード: "))
    }
}
