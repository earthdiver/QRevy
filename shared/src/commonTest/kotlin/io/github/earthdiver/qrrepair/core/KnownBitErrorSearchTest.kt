package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class KnownBitErrorSearchTest {
    private fun snapshot(h: KnownBitErrorSearch.Hypothesis) = Triple(h.errors, h.space.rank,
        h.space.assignments(14).map { it.toList() }.toList())

    @Test fun cachedEliminationPreservesHypothesesSolutionsOrderAndTrialCounts() {
        val rs = ReedSolomonErasures()
        for (unknown in listOf(0, 15, 255)) for (errors in listOf(0, 1, 3)) {
            val words = rs.encode(intArrayOf(37), 3).also { it[2] = it[2] xor errors }
            val block = RsBlockWork(1, words.copyOf(), intArrayOf(unknown, 255, 0, 0))
            for (limit in 0..2) for (pins in listOf(emptySet(), setOf(RsBitConstraintSolver.Bit(2, 0)))) {
                val uncachedBudget = KnownBitErrorBudget()
                val expected = KnownBitErrorSearch(block, 3).hypotheses(limit, pins, uncachedBudget).map(::snapshot).toList()
                val cachedBudget = KnownBitErrorBudget()
                var reservations = 0
                val actual = KnownBitErrorSearch(block, 3).hypotheses(limit, pins, cachedBudget,
                    reserveEliminationCache = { bytes ->
                        reservations++
                        assertEquals(RsBitConstraintSolver.estimatedCacheBytes(4,
                            block.unknownMasks.sumOf { it.countOneBits() }, 3), bytes)
                        true
                    }).map(::snapshot).toList()
                assertEquals(expected, actual)
                assertEquals(uncachedBudget.trials, cachedBudget.trials)
                assertEquals(if (limit > 0 && expected.isNotEmpty()) 1 else 0, reservations)
                assertContentEquals(words, block.codewords)
            }
        }
    }

    @Test fun deniedCacheAndTrialLimitsPreserveTheSamePartialResults() {
        val block = RsBlockWork(1, intArrayOf(0, 0, 0, 0), intArrayOf(255, 255, 255, 0))
        for (trialLimit in listOf(0L, 1L, 4L, 100L)) {
            val results = listOf(false, true).map { cacheAllowed ->
                val budget = KnownBitErrorBudget(maxTrials = trialLimit)
                val prefix = mutableListOf<Triple<List<RsBitConstraintSolver.Bit>, Int, List<List<Int>>>>()
                var reservations = 0
                var limited = false
                try {
                    KnownBitErrorSearch(block, 3).hypotheses(2, emptySet(), budget,
                        reserveEliminationCache = { reservations++; cacheAllowed }).forEach { prefix += snapshot(it) }
                } catch (limit: KnownBitSearchLimit) {
                    assertFalse(limit.timedOut)
                    limited = true
                }
                assertEquals(if (trialLimit == 0L) 0 else 1, reservations)
                Triple(prefix, budget.trials, limited)
            }
            assertEquals(results[0], results[1])
        }
    }

    @Test fun cachedSearchStillHonorsCancellationBetweenHypotheses() {
        val block = RsBlockWork(1, intArrayOf(0, 0, 0, 0), intArrayOf(255, 255, 255, 0))
        var stopped = false
        val search = KnownBitErrorSearch(block, 3, RecoveryCancellation { stopped })
        val iterator = search.hypotheses(2, emptySet(), KnownBitErrorBudget(),
            reserveEliminationCache = { true }).iterator()
        assertTrue(iterator.hasNext())
        iterator.next()
        stopped = true
        assertFailsWith<RecoveryCancelledException> { iterator.hasNext() }
    }

    @Test fun agreesWithExhaustiveDataEncodingIncludingProtectedBits() {
        val rs = ReedSolomonErasures()
        for (errorMask in listOf(0,1,3)) {
            val original = rs.encode(intArrayOf(37),3)
            val block = RsBlockWork(1,original.copyOf().also { it[2] = it[2] xor errorMask },intArrayOf(15,255,0,0))
            for (pins in listOf(emptySet(),setOf(RsBitConstraintSolver.Bit(2,0)))) {
                val expected = (0..255).map { rs.encode(intArrayOf(it),3) }.filter { w ->
                    w.indices.sumOf { i -> ((w[i] xor block.codewords[i]) and block.unknownMasks[i].inv() and 255).countOneBits() } <= 2 &&
                        pins.all { (w[it.codeword] xor block.codewords[it.codeword]) and (1 shl it.bit) == 0 }
                }.map { it.toList() }.toSet()
                val actual = KnownBitErrorSearch(block,3).hypotheses(2,pins,KnownBitErrorBudget())
                    .flatMap { it.space.assignments(14) }.map { it.toList() }.toSet()
                assertEquals(expected,actual)
            }
        }
    }
    @Test fun indexedPairsMatchSequentialOrderAndOnlyReserveMemoryForTwoErrors() {
        val rs = ReedSolomonErasures()
        for (unknown in listOf(0, 15, 255)) {
            val words = rs.encode(intArrayOf(37), 3)
            words[2] = words[2] xor 3
            val block = RsBlockWork(1, words, intArrayOf(unknown, 255, 0, 0))
            val search = KnownBitErrorSearch(block, 3)
            val pins = setOf(RsBitConstraintSolver.Bit(3, 1))
            val sequentialBudget = KnownBitErrorBudget()
            val sequential = search.hypotheses(2, pins, sequentialBudget) { false }.map { it.errors }.toList()
            val indexedBudget = KnownBitErrorBudget()
            val indexed = search.hypotheses(2, pins, indexedBudget).map { it.errors }.toList()
            assertEquals(sequential, indexed)
            assertTrue(indexed.any { it.size == 2 })
            assertTrue(indexedBudget.trials < sequentialBudget.trials)
            search.hypotheses(1, pins, KnownBitErrorBudget()) { fail("Unexpected pair index") }.toList()
        }
    }

    @Test fun retainsAffineFamiliesAndHonorsTrialAndCancellationLimits() {
        val block = RsBlockWork(1,intArrayOf(0,0,0,0),intArrayOf(255,255,255,255))
        val h = KnownBitErrorSearch(block,3).hypotheses(2,emptySet(),KnownBitErrorBudget()).single()
        assertEquals(8,h.space.dimension)
        assertEquals(256,h.space.assignments().count())
        assertFailsWith<KnownBitSearchLimit> { KnownBitErrorSearch(block,3).hypotheses(2,emptySet(),KnownBitErrorBudget(0)).toList() }
        assertFailsWith<RecoveryCancelledException> { KnownBitErrorSearch(block,3,RecoveryCancellation { true }) }
    }
}
