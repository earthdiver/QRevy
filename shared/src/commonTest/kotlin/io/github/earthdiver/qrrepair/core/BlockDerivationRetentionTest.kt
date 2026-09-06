package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class BlockDerivationRetentionTest {
    @Test fun equivalentErasureAndEnumerationRoutesRetainBestMarginAndAllEvidence() {
        val rs = ReedSolomonErasures()
        val block = RsBlockWork(19, rs.encode(IntArray(19), 7), IntArray(26) { if (it < 7) 1 else 0 })
        val solver = PartialCodewordSolver(rs)
        val result = solver.decodeCandidates(block, 7).single()
        assertEquals(7, result.rsMargin)
        assertEquals(7, result.guessedBits)
        assertEquals(-7, result.orderingPenalty)
        assertTrue(result.alternativeDerivations.any { it.guessedBits == 0 && it.rsMargin == 0 })
        assertTrue(result.alternativeDerivations.any { it.guessedBits == 7 && it.rsMargin < 7 })
        val combined = solver.decodeWithBitConstraints(block, 7, RsBitConstraintSolver(rs).analyze(block, 7), false).single()
        assertEquals(7, combined.rsMargin)
        assertTrue(combined.alternativeDerivations.any { it.bitConstraintSolved })
        assertTrue(combined.alternativeDerivations.containsAll(result.alternativeDerivations))
    }

    @Test fun representativeAndTraversalPenaltyAreIndependentOfDuplicateDiscoveryOrder() {
        val first = PartialCodewordSolver.Result(intArrayOf(1, 2), intArrayOf(0), 1, 1, intArrayOf(0), 2, -20)
        val betterMargin = first.copy(corrected = intArrayOf(1, 2), rsMargin = 5, guessedBits = 3, searchPenalty = 10)
        for (order in listOf(listOf(first, betterMargin), listOf(betterMargin, first))) {
            val result = BlockCandidateAccumulator().apply { order.forEach(::add) }.results().single()
            assertEquals(5, result.rsMargin)
            assertEquals(10, result.searchPenalty)
            assertEquals(-20, result.orderingPenalty)
            assertEquals(listOf(first.derivation()), result.alternativeDerivations)
        }
    }

    @Test fun equivalentExactAndConventionalSolutionsRetainBothRoutes() {
        val rs = ReedSolomonErasures()
        val encoded = rs.encode(intArrayOf(1, 2, 3, 4, 5), 7)
        val block = RsBlockWork(5, encoded.copyOf().also { it[0] = it[0] and 254 },
            IntArray(encoded.size).also { it[0] = 1 })
        val solver = PartialCodewordSolver(rs)
        val space = assertNotNull(RsBitConstraintSolver(rs).analyze(block, 7))
        val result = solver.decodeWithBitConstraints(block, 7, space, exactOnly = false).single()
        assertContentEquals(encoded, result.corrected)
        assertFalse(result.bitConstraintSolved)
        assertEquals(6, result.rsMargin)
        val other = result.alternativeDerivations.single()
        assertTrue(other.bitConstraintSolved)
        assertEquals(listOf(0), other.errataPositions)
        assertEquals(0, other.rsMargin)
        assertTrue(solver.decodeWithBitConstraints(block, 7, space, exactOnly = true).single().alternativeDerivations.isEmpty())
    }

    @Test fun distinctExactSolutionsStayDistinct() {
        val rs = ReedSolomonErasures()
        val encoded = rs.encode(intArrayOf(1, 2, 3), 2)
        val block = RsBlockWork(3, encoded, intArrayOf(1, 0, 0, 255, 255))
        val space = assertNotNull(RsBitConstraintSolver(rs).analyze(block, 2))
        val results = PartialCodewordSolver(rs).decodeWithBitConstraints(block, 2, space, exactOnly = false)
        assertEquals(2, results.size)
        assertEquals(2, results.map { it.corrected.toList() }.toSet().size)
        assertTrue(results.all { it.bitConstraintSolved || it.alternativeDerivations.any { route -> route.bitConstraintSolved } })
    }
}
