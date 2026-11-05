package io.github.earthdiver.qrrepair.core

import kotlin.random.Random
import kotlin.test.*

class PartialBitTrialCursorTest {
    @Test fun emptyAssignmentAndReturnedArraysRemainIndependent() {
        val rs = ReedSolomonErasures()
        val input = IntArray(12)
        val cursor = rs.BitTrials(input, 5, intArrayOf(9, 2), emptyList())
        assertContentEquals(input, cursor.assignment(0))
        val first = cursor.decode()
        assertContentEquals(intArrayOf(2, 9), first.errataPositions)
        first.corrected.fill(23)
        first.errataPositions.fill(0)
        assertContentEquals(input, cursor.decode().corrected)
        assertContentEquals(intArrayOf(2, 9), cursor.decode().errataPositions)
        assertFailsWith<IllegalArgumentException> { cursor.assignment(1) }
    }

    @Test fun binaryCarriesRepeatedAndSkippedAssignmentsMatchFreshDecoding() {
        val rs = ReedSolomonErasures()
        val random = Random(68231)
        repeat(6) { seed ->
            val input = rs.encode(IntArray(9) { random.nextInt(256) }, 6)
            if (seed % 2 == 0) input[12] = input[12] xor 83
            val masks = IntArray(15) { if (it < 4) 3 else if (it == 7) 255 else 0 }
            input[7] = 231 // Erased placeholders must not enter the syndrome state.
            val block = RsBlockWork(9, input, masks)
            val plan = PartialBitTrialPlan(block, 8)
            val cursor = plan.cursor(rs, 6)
            val indices = (0..255).toList() + listOf(255, 0, 127, 2, 2, 254)
            for (index in indices) {
                val expectedInput = plan.assignment(index)
                val exposed = cursor.assignment(index)
                assertContentEquals(expectedInput, exposed)
                exposed.fill(0) // The caller's temporary word must not own cursor storage.
                val expected = runCatching { rs.decode(expectedInput, 6, plan.remainingErasures) }
                val actual = runCatching { cursor.decode() }
                if (expected.isSuccess) {
                    assertTrue(actual.isSuccess)
                    assertContentEquals(expected.getOrThrow().corrected, actual.getOrThrow().corrected)
                    assertContentEquals(expected.getOrThrow().errataPositions, actual.getOrThrow().errataPositions)
                    actual.getOrThrow().corrected.fill(0)
                    actual.getOrThrow().errataPositions.fill(0)
                } else {
                    assertEquals((expected.exceptionOrNull() as ReedSolomonErasures.DecodeException).reason,
                        (actual.exceptionOrNull() as ReedSolomonErasures.DecodeException).reason)
                }
            }
        }
    }
}
