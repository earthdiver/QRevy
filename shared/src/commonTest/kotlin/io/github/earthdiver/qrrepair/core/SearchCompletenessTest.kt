package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SearchCompletenessTest {
    private val rs = ReedSolomonErasures()
    private val solver = PartialCodewordSolver(rs)

    @Test fun conventionalSuccessCompletesSpecifiedSearch() {
        val words = rs.encode(intArrayOf(1, 2, 3), 4)
        val block = RsBlockWork(3, words, IntArray(words.size))
        var report: SearchCompleteness? = null
        assertTrue(solver.decodeWithBitConstraints(block, 4, RsBitConstraintSolver(rs).analyze(block, 4),
            exactOnly = false, reportSearch = { report = it }).isNotEmpty())
        assertEquals(SearchCompleteness.COMPLETE, report)
    }

    @Test fun exactSearchCompletesSpecifiedSearch() {
        val words = rs.encode(intArrayOf(1, 2, 3), 4)
        val block = RsBlockWork(3, words, IntArray(words.size) { if (it == 0) 255 else 0 })
        var report: SearchCompleteness? = null
        val results = solver.decodeWithBitConstraints(block, 4, RsBitConstraintSolver(rs).analyze(block, 4),
            exactOnly = true, reportSearch = { report = it })
        assertEquals(1, results.size)
        assertEquals(SearchCompleteness.COMPLETE, report)
    }

    @Test fun exactContradictionCompletesWithNoResult() {
        val words = rs.encode(intArrayOf(1, 2, 3), 4).apply { this[0] = this[0] xor 1 }
        val block = RsBlockWork(3, words, IntArray(words.size))
        var report: SearchCompleteness? = null
        assertFailsWith<ReedSolomonErasures.DecodeException> {
            solver.decodeWithBitConstraints(block, 4, RsBitConstraintSolver(rs).analyze(block, 4),
                exactOnly = true, reportSearch = { report = it })
        }
        assertEquals(SearchCompleteness.COMPLETE, report)
    }

    @Test fun symbolicSpaceAboveDimensionLimitCompletesPolicy() {
        val block = RsBlockWork(3, IntArray(7), IntArray(7) { 255 })
        var report: SearchCompleteness? = null
        assertFailsWith<ReedSolomonErasures.DecodeException> {
            solver.decodeWithBitConstraints(block, 4, RsBitConstraintSolver(rs).analyze(block, 4),
                exactOnly = true, reportSearch = { report = it })
        }
        assertEquals(SearchCompleteness.COMPLETE, report)
    }

    @Test fun excludedPartialBitsDoNotLeaveSpecifiedSearchUnfinished() {
        val words = rs.encode(IntArray(30) { it * 7 }, 8)
        val block = RsBlockWork(30, words, IntArray(words.size) { if (it < 24) 1 else 0 })
        var report: SearchCompleteness? = null
        val results = solver.decodeWithBitConstraints(block, 8, RsBitConstraintSolver(rs).analyze(block, 8),
            exactOnly = false, reportSearch = { report = it })
        assertTrue(results.any { it.corrected.contentEquals(words) })
        assertEquals(SearchCompleteness.COMPLETE, report)
    }
    @Test fun interruptionNeverReportsACompletedEnumeration() {
        val block = RsBlockWork(3, IntArray(7), IntArray(7) { if (it < 3) 15 else 255 })
        var checks = 0
        var report: SearchCompleteness? = null
        assertFailsWith<RecoveryCancelledException> {
            solver.decodeCandidates(block, 4,
                cancellation = RecoveryCancellation { ++checks > 3 }, reportSearch = { report = it })
        }
        assertEquals(SearchCompleteness.UNSEARCHED, report)
    }

    @Test fun configuredTrialLimitIsANormalPolicyBoundary() {
        val block = RsBlockWork(3, IntArray(7), IntArray(7) { if (it < 3) 15 else 255 })
        var report: SearchCompleteness? = null
        solver.decodeCandidates(block, 4, maxTrials = 2, reportSearch = { report = it })
        assertEquals(SearchCompleteness.COMPLETE, report)
    }

}
