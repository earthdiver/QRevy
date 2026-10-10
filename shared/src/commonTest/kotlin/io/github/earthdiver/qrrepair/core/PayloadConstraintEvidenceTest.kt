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
