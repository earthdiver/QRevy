package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PayloadConstraintMemoryRegressionTest {
    @Test
    fun largeMostlyErasedPayloadUsesBoundedPlanMemory() {
        // Approximate a high-version QR data region. With the old implementation,
        // 4096 plans each materialized a large forced-bit map and could exhaust the heap.
        val dataBytes = 2_900
        val block = RsBlockWork(
            numDataCodewords = dataBytes,
            codewords = IntArray(dataBytes),
            unknownMasks = IntArray(dataBytes) { 0xFF },
        )
        val de = DeinterleavedQr(
            blocks = listOf(block),
            rawLocations = emptyArray(),
            ecSymbolsPerBlock = 0,
        )

        val result = PayloadConstraintSolver.apply(
            de = de,
            version = 40,
            maxPlans = 4_096,
        )

        assertEquals(4_096, result.viablePlans)
        assertEquals(0, result.filledBits)
        assertEquals(null, result.inferredMode)
        assertEquals(null, result.inferredCount)
        assertFalse(result.exhaustive)
        assertEquals(0xFF, block.unknownMasks.first())
    }
}
