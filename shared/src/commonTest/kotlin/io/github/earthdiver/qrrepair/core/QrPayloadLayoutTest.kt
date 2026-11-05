package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrPayloadLayoutTest {
    @Test fun characterCountsUseTheThreeQrVersionGroups() {
        val versions = listOf(1, 9, 10, 26, 27, 40)
        val widths = mapOf(
            1 to listOf(10, 10, 12, 12, 14, 14),
            2 to listOf(9, 9, 11, 11, 13, 13),
            4 to listOf(8, 8, 16, 16, 16, 16),
            8 to listOf(8, 8, 10, 10, 12, 12),
            13 to listOf(8, 8, 10, 10, 12, 12),
        )
        for ((mode, expected) in widths) {
            assertEquals(expected, versions.map { QrPayloadLayout.characterCountBits(mode, it) }, "mode=$mode")
        }
    }

    @Test fun bodyLengthsExcludeHeadersAndIncludePartialCharacterGroups() {
        assertEquals(listOf(0, 4, 7, 10, 14, 17, 20), (0..6).map { QrPayloadLayout.payloadBits(1, it) })
        assertEquals(listOf(0, 6, 11, 17, 22), (0..4).map { QrPayloadLayout.payloadBits(2, it) })
        assertEquals(listOf(0, 8, 16, 24), (0..3).map { QrPayloadLayout.payloadBits(4, it) })
        for (mode in listOf(8, 13)) {
            assertEquals(listOf(0, 13, 26, 39), (0..3).map { QrPayloadLayout.payloadBits(mode, it) })
        }
        assertEquals(524280, QrPayloadLayout.payloadBits(4, 65535))
    }

    @Test fun controlsAndReservedModesHaveNoCharacterCountOrBodyLength() {
        for (mode in listOf(-1, 0, 3, 5, 6, 7, 9, 10, 11, 12, 14, 15, 16)) {
            for (version in listOf(1, 10, 27)) assertNull(QrPayloadLayout.characterCountBits(mode, version))
            assertNull(QrPayloadLayout.payloadBits(mode, 0))
            assertNull(QrPayloadLayout.payloadBits(mode, 1))
        }
    }
}
