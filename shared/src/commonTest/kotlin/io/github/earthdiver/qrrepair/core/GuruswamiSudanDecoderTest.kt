package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class GuruswamiSudanDecoderTest {
    private val rs = ReedSolomonErasures()
    private val memory = 32L * 1024 * 1024

    @Test fun listsExactlyTheNearbyCodewordsBeyondUniqueDecoding() {
        val first = rs.encode(intArrayOf(17, 83), 3)
        val second = rs.encode(intArrayOf(18, 83), 3)
        val changed = first.indices.filter { first[it] != second[it] }
        assertEquals(4, changed.size)
        val received = first.copyOf().also { word -> changed.take(2).forEach { word[it] = second[it] } }
        val decoder = GuruswamiSudanDecoder(5, 2)
        val params = assertNotNull(decoder.parameters(0, memory))
        assertEquals(2, params.radius)
        val actual = decoder.decode(received, intArrayOf(), params, memory).map { it.toList() }.toSet()
        val expected = buildSet {
            for (a in 0..255) for (b in 0..255) {
                val word = rs.encode(intArrayOf(a, b), 3)
                if (word.indices.count { word[it] != received[it] } <= 2) add(word.toList())
            }
        }
        assertEquals(expected, actual)
        assertTrue(actual.size >= 2)
        assertTrue(first.toList() in actual && second.toList() in actual)
    }

    @Test fun restoresShortenedQrBlockWithErrorsAndErasures() {
        val encoded = rs.encode(IntArray(19) { (it * 13 + 41) and 255 }, 7)
        val received = encoded.copyOf().also {
            it[2] = 0; it[24] = 0
            for (i in listOf(3, 11, 20)) it[i] = it[i] xor 1
        }
        val decoder = GuruswamiSudanDecoder(26, 19)
        val params = assertNotNull(decoder.parameters(2, memory))
        assertEquals(3, params.radius)
        val candidates = decoder.decode(received, intArrayOf(2, 24), params, memory)
        assertTrue(candidates.any { it.contentEquals(encoded) })
        assertTrue(candidates.all { candidate ->
            candidate.indices.count { it !in listOf(2, 24) && candidate[it] != received[it] } <= 3
        })
    }

    @Test fun coordinatesAndMultipliersRemainCorrectForDifferentShortenings() {
        for ((n, k) in listOf(12 to 5, 26 to 19, 44 to 16, 80 to 50)) {
            val word = rs.encode(IntArray(k) { (it * 57 + 13) and 255 }, n - k)
            val decoder = GuruswamiSudanDecoder(n, k)
            val params = assertNotNull(decoder.parameters(0, memory))
            assertTrue(decoder.decode(word, intArrayOf(), params, memory).any { it.contentEquals(word) })
        }
    }

    @Test fun skipsTrialsWithoutEnoughPointsOrAnAdditionalIntegerRadius() {
        val decoder = GuruswamiSudanDecoder(26, 19)
        assertNull(decoder.parameters(8, memory))
        assertNull(decoder.parameters(7, memory))
        assertNull(decoder.parameters(1, memory))
    }

    @Test fun memoryAndCancellationAreCheckedBeforeAndDuringAlgebra() {
        val decoder = GuruswamiSudanDecoder(26, 19)
        assertFailsWith<ListDecodingWorkingMemoryException> { decoder.parameters(0, 0) }
        val params = assertNotNull(decoder.parameters(0, memory))
        val word = rs.encode(IntArray(19), 7)
        assertFailsWith<ListDecodingWorkingMemoryException> { decoder.decode(word, intArrayOf(), params, 0) }
        var checks = 0
        assertFailsWith<RecoveryCancelledException> {
            decoder.decode(word, intArrayOf(), params, memory) {
                if (++checks > 100) throw RecoveryCancelledException()
            }
        }
    }
}
