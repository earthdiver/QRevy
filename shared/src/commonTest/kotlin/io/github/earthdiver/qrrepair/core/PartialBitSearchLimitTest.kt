package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PartialBitSearchLimitTest {
    private val rs = ReedSolomonErasures()
    private val solver = PartialCodewordSolver(rs)

    // Erased ECC leaves one free low bit per data byte; the original needs the last assignment.
    private fun block(bits: Int): RsBlockWork {
        val encoded = rs.encode(IntArray(bits) { 1 }, 4)
        return RsBlockWork(bits, encoded, IntArray(encoded.size) { if (it < bits) 1 else 255 })
    }

    @Test fun conventionalSearchIncludesAssignmentsBeyond4096AtTheSelectedLimit() {
        for (bits in 12..14) {
            val block = block(bits)
            val original = block.codewords.copyOf()
            val results = solver.decodeWithBitConstraints(block, 4, space = null, exactOnly = false,
                maxEnumeratedBits = bits)
            assertEquals(1 shl bits, results.size)
            assertTrue(results.any { it.corrected.contentEquals(original) })
            assertTrue(results.all { it.guessedBits == bits && !it.bitConstraintSolved })
            assertContentEquals(original, block.codewords)
            if (bits > 12) assertFailsWith<ReedSolomonErasures.DecodeException> {
                solver.decodeWithBitConstraints(block, 4, space = null, exactOnly = false,
                    maxEnumeratedBits = bits - 1)
            }
        }
    }

    @Test fun exactSpaceUsesTheSameLimitAndPreservesAllKnownBits() {
        for (bits in 12..14) {
            val block = block(bits)
            val space = assertNotNull(RsBitConstraintSolver(rs).analyze(block, 4))
            assertEquals(bits, space.dimension)
            val results = solver.decodeWithBitConstraints(block, 4, space, exactOnly = true,
                maxEnumeratedBits = bits)
            assertEquals(1 shl bits, results.size)
            assertTrue(results.any { it.corrected.contentEquals(block.codewords) })
            results.forEach { result ->
                assertTrue(result.bitConstraintSolved)
                block.codewords.indices.forEach { i ->
                    assertEquals(0, (result.corrected[i] xor block.codewords[i]) and block.unknownMasks[i].inv())
                }
            }
            if (bits > 12) assertFailsWith<ReedSolomonErasures.DecodeException> {
                solver.decodeWithBitConstraints(block, 4, space, exactOnly = true, maxEnumeratedBits = bits - 1)
            }
        }
    }

    @Test fun defaultLimitAndDisabledEnumerationRemainBounded() {
        val block = block(13)
        val space = assertNotNull(RsBitConstraintSolver(rs).analyze(block, 4))
        for (exactOnly in listOf(false, true)) {
            assertFailsWith<ReedSolomonErasures.DecodeException> {
                solver.decodeWithBitConstraints(block, 4, if (exactOnly) space else null, exactOnly)
            }
            assertFailsWith<ReedSolomonErasures.DecodeException> {
                solver.decodeWithBitConstraints(block, 4, space, exactOnly,
                    allowBitEnumeration = false, maxEnumeratedBits = 14)
            }
        }
    }

    @Test fun enlargedEnumerationStillHonorsCancellation() {
        val block = block(14)
        val space = assertNotNull(RsBitConstraintSolver(rs).analyze(block, 4))
        for (exactOnly in listOf(false, true)) {
            var checks = 0
            assertFailsWith<RecoveryCancelledException> {
                solver.decodeWithBitConstraints(block, 4, if (exactOnly) space else null, exactOnly,
                    cancellation = RecoveryCancellation { ++checks > 100 }, maxEnumeratedBits = 14)
            }
        }
    }
}
