package io.github.earthdiver.qrrepair.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RsBitConstraintSolverTest {
    private val rs = ReedSolomonErasures()
    private val solver = RsBitConstraintSolver(rs)

    @Test fun dispersedTwentyBitErasuresRecoverBeyondOldEnumerationLimit() {
        val encoded = rs.encode(IntArray(19) { it * 11 }, 7)
        val masks = IntArray(26) { if (it < 20) 1 shl (it % 8) else 0 }
        val block = RsBlockWork(19, encoded.copyOf(), masks)
        assertFailsWith<ReedSolomonErasures.DecodeException> { PartialCodewordSolver(rs).decodeCandidates(block, 7) }
        val space = assertNotNull(solver.analyze(block, 7))
        assertEquals(20, space.rank)
        assertEquals(0, space.dimension)
        assertEquals(20, space.fixedBits.size)
        assertContentEquals(encoded, space.assignments().single())
        assertTrue(space.independentBits().isEmpty())
        assertContentEquals(encoded, block.codewords) // no mutation, including stale values at '?' bits
    }

    @Test fun allEccErasedCannotConstrainUnknownDataButKnownEccCan() {
        val encoded = rs.encode(IntArray(19) { it * 13 }, 7)
        val masks = IntArray(26) { if (it >= 19) 255 else if (it == 3) 15 else 0 }
        val block = RsBlockWork(19, encoded, masks)
        val space = assertNotNull(solver.analyze(block, 7))
        assertEquals(60, space.unknownBits.size)
        assertEquals(56, space.rank)
        assertEquals(4, space.dimension)
        val assignments = space.assignments().toList()
        assertEquals(16, assignments.size)
        assertTrue(assignments.any { it.contentEquals(encoded) })
        assignments.forEach { assertContentEquals(rs.encode(it.copyOfRange(0, 19), 7), it) }
        assertEquals(0, space.fixedBits.keys.count { it.codeword < 19 })
        val independent = space.independentBits(space.unknownBits.sortedByDescending { it.codeword })
        assertEquals(4, independent.size)
        assertTrue(independent.first().codeword >= 19) // ECC cells are useful measurements too
        // Each successive independent measurement halves the remaining assignments.
        var remaining = assignments
        independent.forEach { bit ->
            val half = remaining.filter { ((it[bit.codeword] xor encoded[bit.codeword]) and (1 shl bit.bit)) == 0 }
            assertEquals(remaining.size / 2, half.size)
            remaining = half
        }
        assertContentEquals(encoded, remaining.single())
        masks[19] = 0
        assertTrue(assertNotNull(solver.analyze(block, 7)).dimension < 4)
    }

    @Test fun inconsistentKnownEccDoesNotDisableConventionalCorrection() {
        val encoded = rs.encode(intArrayOf(3, 7, 11, 15), 6)
        val received = encoded.copyOf().also { it[8] = it[8] xor 1 }
        val block = RsBlockWork(4, received, IntArray(10))
        assertNull(solver.analyze(block, 6))
        val partial = PartialCodewordSolver(rs)
        assertFailsWith<ReedSolomonErasures.DecodeException> {
            partial.decodeWithBitConstraints(block, 6, null, exactOnly = true)
        }
        assertTrue(partial.decodeWithBitConstraints(block, 6, null, exactOnly = false)
            .any { it.corrected.contentEquals(encoded) })
    }

    @Test fun consistencyIsConditionalNotProofOfCorrectData() {
        val received = rs.encode(intArrayOf(3, 7, 11), 4)
        received[0] = received[0] xor 1 // an erroneous known bit, with every ECC bit erased
        val space = assertNotNull(solver.analyze(RsBlockWork(3, received, IntArray(7) { if (it >= 3) 255 else 0 }), 4))
        assertEquals(0, space.dimension)
        assertEquals(received[0], space.assignments().single()[0])
    }

    @Test fun hugeSpaceStaysSymbolicWithoutOverflowOrArbitraryCandidateSampling() {
        val space = assertNotNull(solver.analyze(RsBlockWork(19, IntArray(26), IntArray(26) { 255 }), 7))
        assertEquals(152, space.dimension)
        assertEquals(56, space.rank)
        assertTrue(space.assignments().none())
        assertTrue(space.fixedBits.isEmpty())
        assertEquals(152, space.independentBits().size)
    }

    @Test fun equationsAndAssignmentsCrossMultipleMachineWords() {
        val encoded = rs.encode(IntArray(19) { it * 9 }, 30)
        val block = RsBlockWork(19, encoded, IntArray(encoded.size) { if (it >= 19) 255 else if (it == 5) 15 else 0 })
        val space = assertNotNull(solver.analyze(block, 30))
        assertEquals(244, space.unknownBits.size)
        assertEquals(240, space.rank)
        assertEquals(4, space.dimension)
        val assignments = space.assignments().toList()
        assertEquals(16, assignments.size)
        assertTrue(assignments.any { it.contentEquals(encoded) })
        assignments.forEach { assertContentEquals(rs.encode(it.copyOfRange(0, 19), 30), it) }
    }

    @Test fun agreesWithBruteForceIncludingInconsistentSystemsAndFixedBits() {
        val random = Random(98452)
        repeat(50) {
            val dataCount = 3
            val nsym = 2
            val received = rs.encode(IntArray(dataCount) { random.nextInt(256) }, nsym)
            val masks = IntArray(received.size)
            val bits = (0 until received.size * 8).shuffled(random).take(8)
            bits.forEach { masks[it / 8] = masks[it / 8] or (1 shl (it % 8)) }
            if (it % 2 == 0) received[random.nextInt(received.size)] = random.nextInt(256)
            val block = RsBlockWork(dataCount, received, masks)
            val brute = (0 until 256).map { assignment ->
                IntArray(received.size) { received[it] and masks[it].inv() }.also { candidate ->
                    bits.forEachIndexed { j, bit ->
                        if ((assignment and (1 shl j)) != 0) candidate[bit / 8] = candidate[bit / 8] or (1 shl (bit % 8))
                    }
                }
            }.filter { candidate -> rs.encode(candidate.copyOfRange(0, dataCount), nsym).contentEquals(candidate) }
            val space = solver.analyze(block, nsym)
            if (brute.isEmpty()) assertNull(space) else {
                assertNotNull(space)
                assertEquals(brute.map { it.toList() }.toSet(), space.assignments().map { it.toList() }.toSet())
                assertEquals(1 shl space.dimension, brute.size)
                space.unknownBits.forEach { bit ->
                    val values = brute.map { (it[bit.codeword] shr bit.bit) and 1 }.toSet()
                    assertEquals(values.size == 1, bit in space.fixedBits)
                    if (values.size == 1) assertEquals(values.single(), space.fixedBits[bit])
                }
            }
        }
    }
}
