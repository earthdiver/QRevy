package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class ListDecodingParameterReuseTest {
    @Test fun gsReuseAndEvictionRecheckMemoryAndCancellation() {
        val decoder = GuruswamiSudanDecoder(26, 9)
        for (erasures in (0..5).toList() + (0..5).toList()) {
            val expected = GuruswamiSudanDecoder(26, 9).parameters(erasures, Long.MAX_VALUE)
            assertEquals(expected, decoder.parameters(erasures, Long.MAX_VALUE))
            if (expected != null) {
                assertEquals(expected, decoder.parameters(erasures, expected.workingBytes))
                assertFailsWith<ListDecodingWorkingMemoryException> {
                    decoder.parameters(erasures, expected.workingBytes - 1)
                }
                assertFailsWith<RecoveryCancelledException> {
                    decoder.parameters(erasures, Long.MAX_VALUE) { throw RecoveryCancelledException() }
                }
            }
        }
    }

    @Test fun kvReuseRechecksMasksBudgetsAndCancellation() {
        val decoder = KoetterVardyDecoder(8, 2)
        val masks = intArrayOf(1, 3, 1, 0, 0, 0, 0, 255)
        val expected = assertNotNull(decoder.parameters(masks, 1, Long.MAX_VALUE))
        assertNull(decoder.parameters(masks, 1, expected.workingBytes - 1))
        assertNull(decoder.parameters(masks, 1, Long.MAX_VALUE, expected.estimatedWork - 1))
        assertEquals(expected, decoder.parameters(masks, 1, expected.workingBytes, expected.estimatedWork))
        assertFailsWith<RecoveryCancelledException> {
            decoder.parameters(masks, 1, Long.MAX_VALUE, check = { throw RecoveryCancelledException() })
        }
        masks[1] = 255
        assertEquals(KoetterVardyDecoder(8, 2).parameters(masks, 1, Long.MAX_VALUE),
            decoder.parameters(masks, 1, Long.MAX_VALUE))
        masks[1] = 3
        assertEquals(expected, decoder.parameters(masks, 1, Long.MAX_VALUE))
    }
}
