package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayloadConstraintEvidenceTest {
    @Test
    fun trialSuffixBitsDoNotBecomeObservedEvidenceOrChangePropagationEligibility() {
        val observed = bytePayload()
        val observedBlock = observed.blocks.single()
        observedBlock.unknownMasks[2] = 0x0F
        for (byte in 3 until observedBlock.numDataCodewords) observedBlock.unknownMasks[byte] = 0xFF
        val trial = observed.copyDeep()
        // Six matching trial bits meet the existing propagation threshold: four terminator
        // bits and the first two pad bits. None was known in the original observation.
        trial.blocks.single().unknownMasks[2] = 0
        trial.blocks.single().unknownMasks[3] = 0x3F
        val baselineInput = trial.copyDeep()
        val reportedInput = trial.copyDeep()

        val baseline = PayloadConstraintSolver.apply(baselineInput, version = 1)
        val reported = PayloadConstraintSolver.apply(reportedInput, version = 1, evidenceInput = observed)

        assertEquals(6, baseline.evidenceBits)
        assertTrue(baseline.filledBits > 0)
        assertEquals(baseline.copy(evidenceBits = 0), reported)
        assertBlocksEqual(baselineInput, reportedInput)
        assertEquals(0x0F, observedBlock.unknownMasks[2])
        assertEquals(0xFF, observedBlock.unknownMasks[3])
    }

    @Test
    fun originalMatchingSuffixBitsRemainEvidenceWhileUnknownAndConflictingBitsDoNot() {
        val observed = bytePayload()
        val observedBlock = observed.blocks.single()
        observedBlock.unknownMasks[2] = 0x08 // An unknown terminator bit.
        observedBlock.unknownMasks[3] = 0x80 // An unknown pad bit.
        observedBlock.codewords[4] = observedBlock.codewords[4] xor 0x80 // A known pad error.
        val trial = observed.copyDeep()
        trial.blocks.single().unknownMasks.fill(0)
        val baselineInput = trial.copyDeep()
        val reportedInput = trial.copyDeep()

        val baseline = PayloadConstraintSolver.apply(baselineInput, version = 1, trustKnownCount = true)
        val reported = PayloadConstraintSolver.apply(reportedInput, version = 1,
            trustKnownCount = true, evidenceInput = observed)

        // The one-byte payload ends at bit 20. Of the remaining 108 bits, two were
        // unknown and one disagreed; the other 105 remain original matching evidence.
        assertEquals(107, baseline.evidenceBits)
        assertEquals(baseline.copy(evidenceBits = 105), reported)
        assertEquals(1, reported.filledBits)
        assertTrue(reported.forcedByTrustedHeader)
        assertBlocksEqual(baselineInput, reportedInput)
        assertEquals(0x91, observedBlock.codewords[4])
    }

    @Test
    fun suffixErrorsUseTheBudgetWithoutBecomingTrustedConstraints() {
        val input = bytePayload()
        input.blocks.single().codewords[4] = input.blocks.single().codewords[4] xor 3
        val rejected = input.copyDeep()
        assertTrue(PayloadConstraintSolver.apply(rejected, 1, maxSuffixMismatches = 1).suffixBlocked)
        assertBlocksEqual(input, rejected)
        for (limit in listOf(2, null)) {
            val corrected = input.copyDeep()
            val result = PayloadConstraintSolver.apply(corrected, 1, maxSuffixMismatches = limit)
            assertEquals(2, result.filledBits)
            assertEquals(false, result.forcedByTrustedHeader)
            assertEquals(null, result.trustedStructure)
            assertBlocksEqual(bytePayload(), corrected)
        }
    }

    @Test
    fun boundedErrorsStillRequireSixMatchingSuffixBits() {
        for (matching in listOf(5, 6)) {
            val input = bytePayload()
            val block = input.blocks.single()
            block.codewords[2] = block.codewords[2] xor 8
            for (bit in 20 + matching + 1 until 128) {
                block.unknownMasks[bit / 8] = block.unknownMasks[bit / 8] or (1 shl (7 - bit % 8))
            }
            val result = PayloadConstraintSolver.apply(input, 1, maxSuffixMismatches = 1)
            assertEquals(matching, result.evidenceBits)
            assertEquals(matching < 6, result.suffixBlocked)
            assertEquals(matching >= 6, result.filledBits > 0)
        }
    }

    @Test
    fun unresolvedHeaderNeverAuthorizesReplacingKnownSuffixErrors() {
        val input = bytePayload()
        input.blocks.single().unknownMasks[0] = 0xF0
        input.blocks.single().codewords[4] = input.blocks.single().codewords[4] xor 3
        val result = PayloadConstraintSolver.apply(input, 1, maxSuffixMismatches = null)
        assertTrue(result.filled.none { it.localByteIndex == 4 })
        assertEquals(0x12, input.blocks.single().codewords[4])
    }

    private fun bytePayload(): DeinterleavedQr {
        // Version 1-M data for byte-mode "A": 4 mode bits, 8 count bits, 8 payload bits,
        // four terminator bits, then alternating pad bytes.
        val data = intArrayOf(0x40, 0x14, 0x10) + IntArray(13) { if (it % 2 == 0) 0xEC else 0x11 }
        val codewords = ReedSolomonErasures().encode(data, ecSymbols = 10)
        return DeinterleavedQr(listOf(RsBlockWork(data.size, codewords, IntArray(codewords.size))),
            emptyArray(), 10)
    }

    private fun assertBlocksEqual(expected: DeinterleavedQr, actual: DeinterleavedQr) {
        assertEquals(expected.blocks.size, actual.blocks.size)
        for (index in expected.blocks.indices) {
            assertContentEquals(expected.blocks[index].codewords, actual.blocks[index].codewords)
            assertContentEquals(expected.blocks[index].unknownMasks, actual.blocks[index].unknownMasks)
        }
    }
}
