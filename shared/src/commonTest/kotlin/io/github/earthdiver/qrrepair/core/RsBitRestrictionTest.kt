package io.github.earthdiver.qrrepair.core

import kotlin.random.Random
import kotlin.test.*

class RsBitRestrictionTest {
    @Test fun wideRestrictionsPreserveEncodedSolutionsAndAssignmentOrderAfterRollback() {
        val rs = ReedSolomonErasures()
        val data = IntArray(17) { (it * 37 + 19) and 255 }
        val words = rs.encode(data, 4)
        for (dimension in listOf(0, 1, 63, 64, 65, 127, 128, 129)) {
            val masks = IntArray(words.size) { if (it >= data.size) 255 else 0 }
            repeat(dimension) { masks[it / 8] = masks[it / 8] or (1 shl (it % 8)) }
            val space = assertNotNull(RsBitConstraintSolver(rs).analyze(RsBlockWork(data.size, words, masks), 4))
            assertEquals(dimension, space.dimension)
            val branch = space.restriction()
            val openBits = listOf(0, dimension / 2, dimension - 1).filter { it in 0 until dimension }.distinct()
            val expected = (0 until (1 shl openBits.size)).map { assignment ->
                val candidate = data.copyOf()
                openBits.forEachIndexed { index, bit ->
                    if (assignment and (1 shl index) != 0) candidate[bit / 8] = candidate[bit / 8] xor (1 shl (bit % 8))
                }
                rs.encode(candidate, 4).toList()
            }.toSet()
            // Install pivots in a mixed order, including either side of 64-bit boundaries.
            (0 until dimension).filter { it !in openBits }.shuffled(Random(793 + dimension)).forEach { bit ->
                assertTrue(branch.constrain(RsBitConstraintSolver.Bit(bit / 8, bit % 8), (words[bit / 8] shr (bit % 8)) and 1))
            }
            assertEquals(openBits.size, branch.remaining)
            val ordered = branch.assignments(12).map { it.toList() }.toList()
            assertEquals(expected, ordered.toSet(), "dimension=$dimension")
            for (position in words.indices) for (bit in 0..7) {
                val coordinate = RsBitConstraintSolver.Bit(position, bit)
                val values = expected.map { (it[position] shr bit) and 1 }.toSet()
                assertEquals(values.singleOrNull(), branch.value(coordinate))
            }
            val checkpoint = branch.checkpoint
            for (position in data.size until words.size) for (bit in 0..7) for (value in 0..1) {
                val coordinate = RsBitConstraintSolver.Bit(position, bit)
                val filtered = expected.filter { (it[position] shr bit) and 1 == value }.toSet()
                assertEquals(filtered.isNotEmpty(), branch.constrain(coordinate, value))
                assertEquals(if (filtered.isEmpty()) expected else filtered,
                    branch.assignments(12).map { it.toList() }.toSet())
                branch.restore(checkpoint)
                assertEquals(ordered, branch.assignments(12).map { it.toList() }.toList())
            }
            branch.restore(0)
            assertEquals(dimension, branch.remaining)
            val fresh = space.restriction()
            assertEquals(dimension, fresh.remaining)
            for (position in words.indices) for (bit in 0..7) {
                val coordinate = RsBitConstraintSolver.Bit(position, bit)
                assertEquals(fresh.value(coordinate), branch.value(coordinate))
            }
            assertContentEquals(words, rs.encode(data, 4))
        }
    }

    @Test fun additionalBitConditionsAgreeWithFilteringAndCanBeRolledBack() {
        val rs = ReedSolomonErasures()
        val words = rs.encode(intArrayOf(3, 7, 11), 4)
        val block = RsBlockWork(3, words, IntArray(7) { if (it >= 3) 255 else if (it == 0) 255 else 0 })
        val space = assertNotNull(RsBitConstraintSolver(rs).analyze(block, 4))
        val all = space.assignments().map { it.toList() }.toSet()
        val branch = space.restriction()
        val random = Random(71)
        repeat(30) {
            val bit = RsBitConstraintSolver.Bit(random.nextInt(7), random.nextInt(8))
            val checkpoint = branch.checkpoint
            for (value in 0..1) {
                val expected = all.filter { (it[bit.codeword] shr bit.bit) and 1 == value }.toSet()
                val consistent = branch.constrain(bit, value)
                assertEquals(expected.isNotEmpty(), consistent)
                if (consistent) {
                    assertEquals(value, branch.value(bit))
                    assertEquals(expected, branch.assignments(12).map { it.toList() }.toSet())
                }
                branch.restore(checkpoint)
                assertEquals(all, branch.assignments(12).map { it.toList() }.toSet())
            }
        }
        repeat(20) {
            val checkpoint = branch.checkpoint
            var expected = all
            repeat(12) {
                val bit = RsBitConstraintSolver.Bit(random.nextInt(7), random.nextInt(8))
                val value = random.nextInt(2)
                val filtered = expected.filter { (it[bit.codeword] shr bit.bit) and 1 == value }.toSet()
                assertEquals(filtered.isNotEmpty(), branch.constrain(bit, value))
                if (filtered.isNotEmpty()) expected = filtered
                assertEquals(expected, branch.assignments(12).map { it.toList() }.toSet())
            }
            branch.restore(checkpoint)
            assertEquals(all, branch.assignments(12).map { it.toList() }.toSet())
        }
        assertEquals(all, space.assignments().map { it.toList() }.toSet())
    }
}
