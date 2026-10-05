package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class KoetterVardyDecoderTest {
    private val rs = ReedSolomonErasures()
    private val memory = 32L * 1024 * 1024

    @Test fun returnsExactlyTheCodewordsAboveTheMultiplicityScoreThreshold() {
        val n = 8
        val word = rs.encode(intArrayOf(17, 83), n - 2)
        val masks = intArrayOf(1, 3, 1, 0, 0, 0, 0, 255)
        val received = word.copyOf().also { it[4] = it[4] xor 2; it[0] = it[0] xor 1; it[7] = 227 }
        val decoder = KoetterVardyDecoder(n, 2)
        val parameters = assertNotNull(decoder.parameters(masks, 1, memory))
        fun score(candidate: IntArray) = candidate.indices.sumOf {
            if (masks[it] != 255 && (candidate[it] xor received[it]) and masks[it].inv() and 255 == 0)
                parameters.multiplicity shr masks[it].countOneBits() else 0
        }
        val expected = buildSet {
            for (a in 0..255) for (b in 0..255) {
                val candidate = rs.encode(intArrayOf(a, b), n - 2)
                if (score(candidate) > parameters.degreeBound) add(candidate.toList())
            }
        }
        val actual = linkedSetOf<List<Int>>()
        decoder.decode(received, masks, parameters, memory) { candidate, actualScore ->
            assertEquals(score(candidate), actualScore)
            actual += candidate.toList()
        }
        assertTrue(word.toList() in actual)
        assertEquals(expected, actual)
        val alternate = received.copyOf().also { values -> values.indices.forEach { values[it] = values[it] xor masks[it] } }
        val alternateList = linkedSetOf<List<Int>>()
        decoder.decode(alternate, masks, parameters, memory) { candidate, _ -> alternateList += candidate.toList() }
        assertEquals(actual, alternateList, "Unknown placeholders must not affect multiplicities or results")
    }

    @Test fun usesManyPartialBytesWithoutEnumeratingTheirUnknownBitAssignments() {
        val word = rs.encode(IntArray(9) { (it * 17 + 23) and 255 }, 17)
        val masks = IntArray(26) { if (it < 18) 1 else 0 }
        val received = word.copyOf().also { values ->
            for (i in 0 until 18) values[i] = values[i] and 254
            values[20] = values[20] xor 2
        }
        assertFailsWith<ReedSolomonErasures.DecodeException> { rs.decode(received, 17, (0 until 18).toList().toIntArray()) }
        val decoder = KoetterVardyDecoder(26, 9)
        val parameters = assertNotNull(decoder.parameters(masks, 1, memory))
        val actual = mutableListOf<IntArray>()
        decoder.decode(received, masks, parameters, memory) { candidate, _ -> actual += candidate }
        assertTrue(actual.any { it.contentEquals(word) })
    }

    @Test fun declinesUnhelpfulOrUnaffordableSoftInterpolationAndHonorsCancellation() {
        val decoder = KoetterVardyDecoder(26, 19)
        assertNull(decoder.parameters(IntArray(26), 1, memory))
        assertNull(decoder.parameters(IntArray(26) { 255 }, 1, memory))
        assertNull(decoder.parameters(IntArray(26) { 1 }, 4, memory))
        val useful = KoetterVardyDecoder(26, 9)
        val masks = IntArray(26) { if (it < 18) 1 else 0 }
        assertNull(useful.parameters(masks, 1, 0))
        assertNull(useful.parameters(masks, 1, memory, maximumWork = 0))
        val parameters = assertNotNull(useful.parameters(masks, 1, memory))
        assertFailsWith<ListDecodingWorkingMemoryException> { useful.decode(IntArray(26), masks, parameters, 0) { _, _ -> } }
        var checks = 0
        assertFailsWith<RecoveryCancelledException> {
            useful.decode(IntArray(26), masks, parameters, memory,
                check = { if (++checks > 100) throw RecoveryCancelledException() }) { _, _ -> }
        }
    }
}
