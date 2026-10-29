package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class KnownBitErrorSearchTest {
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
    @Test fun retainsAffineFamiliesAndHonorsTrialAndCancellationLimits() {
        val block = RsBlockWork(1,intArrayOf(0,0,0,0),intArrayOf(255,255,255,255))
        val h = KnownBitErrorSearch(block,3).hypotheses(2,emptySet(),KnownBitErrorBudget()).single()
        assertEquals(8,h.space.dimension)
        assertEquals(256,h.space.assignments().count())
        assertFailsWith<KnownBitSearchLimit> { KnownBitErrorSearch(block,3).hypotheses(2,emptySet(),KnownBitErrorBudget(0)).toList() }
        assertFailsWith<RecoveryCancelledException> { KnownBitErrorSearch(block,3,RecoveryCancellation { true }) }
    }
}
