package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class RsPayloadTraversalTest {
    private val rs = ReedSolomonErasures()
    private fun source(data: IntArray, unknown: IntArray, limit: Int = 12): RsCandidateSource {
        val words = rs.encode(data, 4)
        val block = RsBlockWork(data.size, words, unknown + IntArray(4) { 255 })
        return RsCandidateSource(block, emptyList(), assertNotNull(RsBitConstraintSolver(rs).analyze(block, 4)), limit)
    }
    private fun bytes(bits: String): IntArray = bits.padEnd((bits.length + 7) / 8 * 8, '0').chunked(8).map { it.toInt(2) }.toIntArray()

    @Test fun anAvailableConventionalCandidateIsNotDeferredToAnUnreachedExactBranch() {
        val source = source(intArrayOf(0x40, 0x10, 0), intArrayOf(0, 0, 0x10))
        val original = rs.encode(intArrayOf(0x40, 0x10, 0), 4)
        val alternatives = (0..200).map { value ->
            PartialCodewordSolver.Result(rs.encode(intArrayOf(0x40, 0x10 or (value shr 4), value shl 4 and 255), 4),
                intArrayOf(), 2, 1, intArrayOf(2), 0)
        }
        val traversal = RsPayloadTraversal(listOf(RsCandidateSource(source.block, alternatives, source.space, 12)), 1, 100)
        val found = traversal.combinations { true }.toList()
        assertTrue(traversal.limited)
        assertTrue(found.any { it.single().corrected.contentEquals(original) })
    }

    @Test fun firstCandidateDoesNotRequireMaterializingTheRemaining65535() {
        val source = source(intArrayOf(0x40, 0x20, 0, 0), intArrayOf(0, 15, 255, 240), 16)
        assertEquals(16, source.space!!.dimension)
        val traversal = RsPayloadTraversal(listOf(source), 1, 65_536)
        val first = traversal.combinations { true }.first().single()
        assertEquals(1, traversal.materialized)
        assertContentEquals(rs.encode(first.corrected.copyOf(4), 4), first.corrected)
    }

    @Test fun headerAndLengthConditionsReduceAnOtherwiseUnenumerableSpace() {
        // BYTE length is odd but otherwise unknown; this capacity admits only 1 byte.
        // Its arbitrary binary byte must survive; no character-decoding assumption applies.
        val source = source(intArrayOf(0x40, 0x1f, 0xf0), intArrayOf(15, 239, 255), 8)
        assertEquals(19, source.space!!.dimension)
        assertEquals(0, source.space.assignments(8).count())
        val traversal = RsPayloadTraversal(listOf(source), 1, 65_536)
        val found = traversal.combinations { true }.map { it.single().corrected.take(3) }.toSet()
        val expected = (0..255).map { listOf(0x40, 0x10 or (it shr 4), (it and 15) shl 4) }.toSet()
        assertEquals(expected, found)
        assertEquals(256, traversal.materialized)
        assertFalse(traversal.limited)
    }

    @Test fun separateByteSegmentsAreNotForcedIntoASingleSegment() {
        val data = bytes("0100" + "00000001" + "11111111" + "0100" + "00000001" + "10000000" + "0000")
        val source = source(data, IntArray(data.size))
        assertContentEquals(rs.encode(data, 4), RsPayloadTraversal(listOf(source), 1, 1000)
            .combinations { true }.single().single().corrected)
    }

    @Test fun controlPrefixesRetainTheGeneralValidationRoute() {
        val data = bytes("0111" + "00011010" + "0100" + "00000001" + "11111111" + "0000")
        val source = source(data, IntArray(data.size))
        assertContentEquals(rs.encode(data, 4), RsPayloadTraversal(listOf(source), 1, 1000)
            .combinations { true }.single().single().corrected)
    }

    @Test fun conventionalCorrectionSurvivesContradictoryKnownBitConstraints() {
        val source = source(intArrayOf(0xF0, 0x1f, 0xf0), intArrayOf(0, 15, 0))
        val valid = rs.encode(intArrayOf(0x40, 0x1f, 0xf0), 4)
        val ordinary = PartialCodewordSolver.Result(valid, intArrayOf(0), 0, 0, intArrayOf(), 0)
        val withCorrection = RsCandidateSource(source.block, listOf(ordinary), source.space, 12)
        val found = RsPayloadTraversal(listOf(withCorrection), 1, 1000).combinations { true }.toList()
        assertEquals(1, found.size)
        assertContentEquals(valid, found.single().single().corrected)
        assertFalse(found.single().single().bitConstraintSolved)
    }

    @Test fun numericAndAlphanumericRangesPruneCombinationsWithoutGloballyFixedBits() {
        for ((mode, count, width, maximum) in listOf(
            listOf(1, 3, 10, 999), listOf(1, 2, 7, 99), listOf(1, 1, 4, 9),
            listOf(2, 2, 11, 2024), listOf(2, 1, 6, 44),
        )) {
            val countWidth = if (mode == 1) 10 else 9
            val body = 4 + countWidth
            val data = bytes(mode.toString(2).padStart(4, '0') + count.toString(2).padStart(countWidth, '0') + "0".repeat(width) + "0000")
            val masks = IntArray(data.size)
            repeat(width) { offset ->
                val position = body + offset
                masks[position / 8] = masks[position / 8] or (1 shl (7 - position % 8))
            }
            val source = source(data, masks)
            assertTrue(source.space!!.fixedBits.keys.none { it.codeword < masks.size && masks[it.codeword] and (1 shl it.bit) != 0 })
            val traversal = RsPayloadTraversal(listOf(source), 1, 65_536)
            val found = traversal.combinations { true }.map { combination ->
                val word = combination.single().corrected
                (0 until width).fold(0) { acc, i -> acc * 2 + ((word[(body + i) / 8] shr (7 - (body + i) % 8)) and 1) }
            }.toList()
            assertEquals((0..maximum).toSet(), found.toSet())
            assertEquals(maximum + 1, found.size)
            assertEquals(maximum + 1, traversal.materialized)
            assertFalse(traversal.limited)
        }
    }

    @Test fun valueRangesDoNotSelectOnlySmallBoundarySlicesOfAnOversizedSpace() {
        val data = bytes("0001" + "0000000011" + "0".repeat(10) + "0000")
        val masks = IntArray(data.size)
        for (position in 14 until 24) masks[position / 8] = masks[position / 8] or (1 shl (7 - position % 8))
        val traversal = RsPayloadTraversal(listOf(source(data, masks, 9)), 1, 65_536)
        assertEquals(0, traversal.combinations { true }.count())
        assertEquals(0, traversal.materialized)
        assertEquals(0, traversal.dimensionLimitedBlock)
    }

    @Test fun rangeGroupsBacktrackIndependently() {
        val data = bytes("0001" + "0000000100" + "0".repeat(14) + "0000")
        val masks = IntArray(data.size)
        // Keep all but the last two bits of the triple known (0..3); last digit is unknown.
        for (position in 22 until 28) masks[position / 8] = masks[position / 8] or (1 shl (7 - position % 8))
        val source = source(data, masks)
        val traversal = RsPayloadTraversal(listOf(source), 1, 65_536)
        val found = traversal.combinations { true }.toList()
        assertEquals(40, found.size)
        assertEquals(40, found.map { it.single().corrected.toList() }.toSet().size)
        assertEquals(40, traversal.materialized)
    }

    @Test fun grammarExplorationHasOneSharedNodeBudgetAndHonorsCancellation() {
        val source = source(intArrayOf(0x40, 0, 0), intArrayOf(15, 255, 255), 8)
        val traversal = RsPayloadTraversal(listOf(source), 1, 4)
        traversal.combinations { true }.toList()
        assertTrue(traversal.limited)
        assertEquals(4, traversal.nodes)
        var cancelled = false
        val iterator = RsPayloadTraversal(listOf(source), 1, 65_536, RecoveryCancellation { cancelled }).combinations { true }.iterator()
        iterator.next()
        cancelled = true
        assertFailsWith<RecoveryCancelledException> { iterator.next() }
    }
}
