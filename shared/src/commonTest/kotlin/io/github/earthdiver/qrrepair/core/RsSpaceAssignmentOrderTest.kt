package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class RsSpaceAssignmentOrderTest {
    @Test fun binaryBasisTraversalMatchesAllCodewordsInTheOriginalFreeByteOrder() {
        val rs = ReedSolomonErasures()
        for (k in 1..2) {
            val n = k + 5
            val anchor = rs.encode(IntArray(k) { 91 + it }, 5)
            val block = RsBlockWork(k, anchor, IntArray(n) { if (it < 6) 255 else 0 })
            val space = assertNotNull(RsBitConstraintSolver().analyze(block, 5))
            assertEquals(8, space.dimension)
            // The first five full symbols form an invertible parity-check submatrix.
            // Symbol five is the free byte, enumerated in binary order by the original API.
            val expected = buildList {
                for (a in 0..255) for (b in if (k == 1) 0..0 else 0..255) {
                    val word = rs.encode(if (k == 1) intArrayOf(a) else intArrayOf(a, b), 5)
                    if (k == 1 || word[6] == anchor[6]) add(word.toList())
                }
            }.sortedBy { it[5] }
            assertEquals(expected, space.assignments(8).map { it.toList() }.toList())
            assertTrue(space.assignments(7).none())
            val iterator = space.assignments(8).iterator()
            iterator.next().fill(0)
            assertEquals(expected[1], iterator.next().toList())
        }
    }
}
