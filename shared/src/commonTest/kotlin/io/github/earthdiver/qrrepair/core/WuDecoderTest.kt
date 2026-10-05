package io.github.earthdiver.qrrepair.core

import kotlin.random.Random
import kotlin.test.*

class WuDecoderTest {
    private val rs = ReedSolomonErasures()
    private val memory = 32L * 1024 * 1024

    @Test fun matchesExhaustiveListsWithOddAndEvenParityAndErasures() {
        for ((n, erased) in listOf(3 to intArrayOf(), 5 to intArrayOf(), 6 to intArrayOf(), 7 to intArrayOf(1))) {
            val first = rs.encode(intArrayOf(17, 83), n - 2)
            val second = rs.encode(intArrayOf(18, 83), n - 2)
            val radius = (n - 2 - erased.size) / 2 + 1
            val received = first.copyOf()
            first.indices.filter { it !in erased && first[it] != second[it] }.take(radius).forEach { received[it] = second[it] }
            erased.forEach { received[it] = 231 }
            val expected = buildSet {
                for (a in 0..255) for (b in 0..255) {
                    val word = rs.encode(intArrayOf(a, b), n - 2)
                    if (word.indices.count { it !in erased && word[it] != received[it] } <= radius) add(word.toList())
                }
            }
            val actual = linkedSetOf<List<Int>>()
            WuDecoder(n, 2).decode(received, erased, radius, memory) { actual += it.toList() }
            assertEquals(expected, actual, "length $n")
            assertTrue(actual.size >= 2)
        }
    }

    @Test fun agreesWithGsAcrossShorteningsAndErrorPatterns() {
        val random = Random(937)
        for ((n, k, erasedCount) in listOf(Triple(26, 19, 0), Triple(26, 19, 2), Triple(20, 10, 0),
            Triple(44, 16, 0), Triple(32, 19, 3))) {
            val listDecoding = GuruswamiSudanDecoder(n, k)
            val parameters = assertNotNull(listDecoding.parameters(erasedCount, memory))
            repeat(5) { trial ->
                val encoded = rs.encode(IntArray(k) { random.nextInt(256) }, n - k)
                val positions = encoded.indices.shuffled(random)
                val erased = positions.take(erasedCount).toIntArray()
                val received = encoded.copyOf()
                erased.forEach { received[it] = random.nextInt(256) }
                val errors = if (trial == 0) 0 else parameters.radius
                positions.drop(erasedCount).take(errors).forEach { received[it] = received[it] xor random.nextInt(1, 256) }
                val actual = linkedSetOf<List<Int>>()
                WuDecoder(n, k).decode(received, erased, parameters.radius, memory) { actual += it.toList() }
                assertTrue(encoded.toList() in actual, "$n/$k erasures=$erasedCount trial=$trial")
                assertEquals(listDecoding.decode(received, erased, parameters, memory).map { it.toList() }.toSet(), actual)
            }
        }
    }

    @Test fun honorsMemoryAndCancellationBeforeReturningAnyCandidate() {
        val decoder = WuDecoder(26, 19)
        assertFailsWith<ListDecodingWorkingMemoryException> { decoder.decode(IntArray(26), intArrayOf(), 4, 0) {} }
        var checks = 0
        assertFailsWith<RecoveryCancelledException> {
            decoder.decode(IntArray(26) { if (it < 4) 1 else 0 }, intArrayOf(), 4, memory,
                check = { if (++checks > 5) throw RecoveryCancelledException() }) {}
        }
    }
}
