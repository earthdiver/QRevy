package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class RecoveryCancellationTest {
    @Test fun conventionalEnumerationStopsWithoutReturningPartialSuccess() {
        val rs = ReedSolomonErasures()
        val block = RsBlockWork(19, rs.encode(IntArray(19), 7), IntArray(26) { if (it < 7) 1 else 0 })
        var checks = 0
        assertFailsWith<RecoveryCancelledException> {
            PartialCodewordSolver(rs).decodeCandidates(block, 7,
                cancellation = RecoveryCancellation { ++checks == 5 })
        }
        assertEquals(5, checks)
        assertTrue(block.codewords.all { it == 0 })
    }

    @Test fun exactAssignmentsAndEliminationCanBeCancelled() {
        val block = RsBlockWork(19, IntArray(26), IntArray(26) { if (it >= 19) 255 else if (it < 3) 15 else 0 })
        val solver = RsBitConstraintSolver()
        var checks = 0
        assertFailsWith<RecoveryCancelledException> {
            solver.analyze(block, 7, RecoveryCancellation { ++checks == 3 })
        }
        val space = assertNotNull(solver.analyze(block, 7))
        assertEquals(12, space.dimension)
        checks = 0
        assertFailsWith<RecoveryCancelledException> {
            space.assignments(12, RecoveryCancellation { ++checks == 5 }).toList()
        }
        assertEquals(5, checks)
    }

    @Test fun payloadPlanEnumerationHonorsCancellation() {
        val block = RsBlockWork(19, IntArray(26), IntArray(26) { 255 })
        val de = DeinterleavedQr(listOf(block), emptyArray(), 7)
        var checks = 0
        assertFailsWith<RecoveryCancelledException> {
            PayloadConstraintSolver.apply(de, 1, cancellation = RecoveryCancellation { ++checks == 3 })
        }
        assertTrue(block.unknownMasks.all { it == 255 })
    }
}
