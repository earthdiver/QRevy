package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedCoreSmokeTest {
    @Test
    fun reedSolomonMixedErrorsAndErasuresRoundTrip() {
        val rs = ReedSolomonErasures()
        val data = IntArray(24) { (it * 37 + 11) and 0xFF }
        val encoded = rs.encode(data, 12)
        val damaged = encoded.copyOf()
        val erasures = intArrayOf(1, 7, 13, 18)
        erasures.forEachIndexed { i, p -> damaged[p] = damaged[p] xor (0x31 + i) }
        damaged[28] = damaged[28] xor 0x55
        damaged[31] = damaged[31] xor 0xA3

        val result = rs.decode(damaged, 12, erasures)
        assertContentEquals(encoded, result.corrected)
    }

    @Test
    fun projectiveGeometryRoundTrip() {
        val base = GridGeometry(
            version = 4,
            role = FinderRole.TOP_LEFT,
            finderCenterX = 80.0,
            finderCenterY = 70.0,
            modulePitch = 6.0,
            angleRad = 0.0,
        )
        val last = (base.dimension - 1).toDouble()
        val projected = GridGeometry.fromFourCorrespondences(
            base,
            listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last)),
            listOf(PointD(20.0, 30.0), PointD(260.0, 18.0), PointD(275.0, 278.0), PointD(8.0, 250.0)),
        )

        val original = PointD(13.25, 19.75)
        val image = projected.moduleToImage(original.x, original.y)
        val recovered = projected.imageToModule(image.x, image.y)
        assertTrue(original.distanceTo(recovered) < 1e-7)
    }

    @Test
    fun payloadConstraintCanFillKnownPadding() {
        val bits = mutableListOf<Int>()
        fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += (value shr i) and 1 }
        put(0b0100, 4) // byte mode
        put(1, 8)      // one byte
        put(0x41, 8)   // A
        put(0, 4)      // terminator
        while (bits.size % 8 != 0) bits += 0
        var pad = 0
        while (bits.size < 80) put(if ((pad++ and 1) == 0) 0xEC else 0x11, 8)
        val bytes = IntArray(10)
        for (b in bytes.indices) for (i in 0..7) bytes[b] = (bytes[b] shl 1) or bits[b * 8 + i]
        val expected = bytes.copyOf()
        val masks = IntArray(bytes.size)
        for (i in 4 until bytes.size) { bytes[i] = 0; masks[i] = 0xFF }
        val block = RsBlockWork(bytes.size, bytes, masks)
        val de = DeinterleavedQr(listOf(block), emptyArray(), 0)
        val result = PayloadConstraintSolver.apply(de, version = 1)
        assertEquals(4, result.inferredMode)
        assertEquals(1, result.inferredCount)
        assertTrue(result.filledBits > 0)
        assertContentEquals(expected, block.codewords)
        assertTrue(block.unknownMasks.all { it == 0 })
    }

    @Test
    fun trustedCompleteCountCanFillSuffixWithoutPaddingEvidence() {
        val bytes = intArrayOf(0x40, 0x14, 0x10, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC)
        val expected = bytes.copyOf()
        val masks = IntArray(bytes.size)
        bytes[2] = bytes[2] and 0xF0
        masks[2] = 0x0F
        for (i in 3 until bytes.size) { bytes[i] = 0; masks[i] = 0xFF }

        val conservativeBlock = RsBlockWork(bytes.size, bytes.copyOf(), masks.copyOf())
        val conservative = PayloadConstraintSolver.apply(
            DeinterleavedQr(listOf(conservativeBlock), emptyArray(), 0),
            version = 1,
        )
        assertEquals(0, conservative.filledBits)

        val trustedBlock = RsBlockWork(bytes.size, bytes.copyOf(), masks.copyOf())
        val trusted = PayloadConstraintSolver.apply(
            DeinterleavedQr(listOf(trustedBlock), emptyArray(), 0),
            version = 1,
            trustKnownCount = true,
        )
        assertEquals(4, trusted.inferredMode)
        assertEquals(1, trusted.inferredCount)
        assertEquals(0, trusted.evidenceBits)
        assertTrue(trusted.forcedByTrustedHeader)
        assertTrue(trusted.filledBits > 0)
        assertContentEquals(expected, trustedBlock.codewords)
        assertTrue(trustedBlock.unknownMasks.all { it == 0 })
    }

    @Test
    fun trustedCompleteCountOverridesContradictoryKnownSuffixOnly() {
        // Version 1 byte mode, count=1, payload='A', then terminator/alignment and pad bytes.
        val expected = intArrayOf(0x40, 0x14, 0x10, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC)
        val contradictory = expected.copyOf().also {
            it[2] = it[2] or 0x08 // Known 1 in the first terminator bit; expected value is 0.
            it[3] = it[3] xor 0x01 // Known contradictory padding bit.
        }

        val conservativeBlock = RsBlockWork(contradictory.size, contradictory.copyOf(), IntArray(contradictory.size))
        val conservative = PayloadConstraintSolver.apply(
            DeinterleavedQr(listOf(conservativeBlock), emptyArray(), 0),
            version = 1,
        )
        assertEquals(0, conservative.filledBits)
        assertContentEquals(contradictory, conservativeBlock.codewords)

        val trustedBlock = RsBlockWork(contradictory.size, contradictory.copyOf(), IntArray(contradictory.size))
        val trusted = PayloadConstraintSolver.apply(
            DeinterleavedQr(listOf(trustedBlock), emptyArray(), 0),
            version = 1,
            trustKnownCount = true,
        )
        assertEquals(4, trusted.inferredMode)
        assertEquals(1, trusted.inferredCount)
        assertContentEquals(expected, trustedBlock.codewords)
        assertTrue(trusted.filled.any {
            it.dataBitIndex == 20 && it.kind == PayloadConstraintSolver.ConstraintKind.TERMINATOR
        })
        assertTrue(trusted.filled.any { it.kind == PayloadConstraintSolver.ConstraintKind.PAD })
    }

    @Test
    fun partialSearchPreservesRsSolutionsAcrossHeuristicRanks() {
        val rs = ReedSolomonErasures()
        var preservedDifferentRanks = false
        search@ for (ecSymbols in 2..6) for (dataSize in 2..8) {
            val data = IntArray(dataSize) { (it * 37 + dataSize * 11 + ecSymbols) and 0xFF }
            val encoded = rs.encode(data, ecSymbols)
            for (unknownBits in 1..3) {
                val erasedCount = ecSymbols + 1
                if (erasedCount > encoded.size || erasedCount * unknownBits > 12) continue
                val unknownMask = (1 shl unknownBits) - 1
                val partial = encoded.copyOf()
                val unknown = IntArray(encoded.size)
                for (position in 0 until erasedCount) {
                    unknown[position] = unknownMask
                    partial[position] = partial[position] and unknownMask.inv()
                }
                val results = runCatching {
                    PartialCodewordSolver(rs).decodeCandidates(
                        RsBlockWork(dataSize, partial, unknown),
                        nsym = ecSymbols,
                    )
                }.getOrNull() ?: continue
                if (results.any { it.corrected.contentEquals(encoded) } &&
                    results.map { it.searchPenalty }.distinct().size > 1
                ) {
                    preservedDifferentRanks = true
                    break@search
                }
            }
        }

        assertTrue(preservedDifferentRanks)
    }
}
