package io.github.earthdiver.qrrepair.core

import kotlin.random.Random
import kotlin.test.*

class RsBitRestrictionTest {
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
