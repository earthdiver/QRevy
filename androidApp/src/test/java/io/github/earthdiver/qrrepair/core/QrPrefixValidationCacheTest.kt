package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class QrPrefixValidationCacheTest {
    @Test fun copiedKeysAndBothOutcomesAreReused() {
        var calls = 0
        val cache = QrPrefixValidationCache(7, 1024) { data, version ->
            calls++
            assertEquals(7, version)
            data[0].toInt() == 1
        }
        val input = byteArrayOf(1)
        assertTrue(cache.canBePrefix(input))
        input[0] = 2
        assertFalse(cache.canBePrefix(input))
        assertTrue(cache.canBePrefix(byteArrayOf(1)))
        assertFalse(cache.canBePrefix(byteArrayOf(2)))
        assertEquals(2, calls)
        assertTrue(cache.workingBytes <= 1024)
    }

    @Test fun evictionAndOversizedInputsRecomputeWithoutChangingThePredicate() {
        var calls = 0
        val cache = QrPrefixValidationCache(1, 260) { data, _ -> calls++; data[0].toInt() % 2 == 0 }
        repeat(1000) { index ->
            val data = byteArrayOf((index % 127).toByte())
            assertEquals(data[0].toInt() % 2 == 0, cache.canBePrefix(data))
            assertTrue(cache.workingBytes <= 260)
        }
        assertEquals(1000, calls)
        repeat(2) { assertTrue(cache.canBePrefix(ByteArray(1000))) }
        assertEquals(1002, calls)
        assertTrue(cache.workingBytes <= 260)
    }

    @Test fun cachedValidatorMatchesFreshValidationAcrossVersions() {
        for (version in listOf(1, 10, 27, 40)) {
            val cache = QrPrefixValidationCache(version, 4096)
            for (length in 0..8) for (first in 0..255) {
                val data = ByteArray(length) { if (it == 0) first.toByte() else 0 }
                val expected = StrictQrPayloadValidator.canBePrefix(data, version)
                assertEquals(expected, cache.canBePrefix(data))
                assertEquals(expected, cache.canBePrefix(data))
            }
        }
    }
}
