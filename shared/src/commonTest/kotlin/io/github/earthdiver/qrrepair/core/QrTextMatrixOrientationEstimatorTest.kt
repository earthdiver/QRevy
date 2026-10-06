package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QrTextMatrixOrientationEstimatorTest {
    @Test fun assignsThreeFindersForEveryVersionAndRotation() {
        for (version in 1..40) for (rotation in 0..3) {
            val n = 17 + 4 * version
            val input = rotated(structure(n), rotation)
            val estimate = assertNotNull(QrTextMatrixOrientationEstimator.estimate(input), "V$version rotation $rotation")
            assertEquals(rotation, estimate.quarterTurns)
            val expected = mapOf(FinderRole.TOP_LEFT to Coord(3, 3),
                FinderRole.TOP_RIGHT to Coord(n - 4, 3), FinderRole.BOTTOM_LEFT to Coord(3, n - 4))
                .mapValues { (_, center) ->
                    var point = center
                    repeat(rotation) { point = Coord(n - 1 - point.y, point.x) }
                    point
                }
            assertEquals(expected, estimate.finderCenters)
        }
    }

    @Test fun smallErasuresAndErrorsInEachPatternStillAllowAssignment() {
        for (n in listOf(21, 25, 45, 177)) {
            val input = structure(n)
            for ((sx, sy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                for (x in 0..6) input.set(sx + x, sy, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
                for (x in 2..3) input.set(sx + x, sy + 3, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            }
            for ((x, y) in listOf(7 to 4, n - 8 to 4, 4 to n - 8)) {
                input.set(x, y, ModuleValue.ONE, 1f, ModuleSource.IMAGE)
                val dx = if (y == n - 8) 1 else 0
                val dy = if (dx == 0) 1 else 0
                input.set(x + dx, y + dy, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
            input.set(8, 6, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            input.set(6, 8, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            input.set(9, 6, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            input.set(6, 9, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            for (rotation in 0..3) {
                val estimate = assertNotNull(QrTextMatrixOrientationEstimator.estimate(rotated(input, rotation)),
                    "Dimension $n, rotation $rotation")
                assertEquals(rotation, estimate.quarterTurns)
            }
        }
    }

    @Test fun insufficientEvidenceOrExcessiveErrorsRemainUnassigned() {
        val groups = listOf(
            List(3) { Coord(2 + it, 3) },
            listOf(Coord(7, 3), Coord(7, 4)),
            listOf(Coord(8, 6), Coord(9, 6)),
            listOf(Coord(6, 8), Coord(6, 9)),
        )
        val missing = listOf(
            List(13) { Coord(it % 7, it / 7) },
            List(6) { Coord(7, it) },
            List(4) { Coord(8 + it, 6) },
            List(4) { Coord(6, 8 + it) },
        )
        for (unknown in listOf(false, true)) for (cells in if (unknown) missing else groups) {
            for (rotation in 0..3) {
                val input = structure(25)
                for (cell in cells) {
                    val value = if (unknown) ModuleValue.ERASED else
                        if (input[cell.x, cell.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
                    input.set(cell.x, cell.y, value, 0f, ModuleSource.IMAGE)
                }
                assertNull(QrTextMatrixOrientationEstimator.estimate(rotated(input, rotation)),
                    "Cells $cells, unknown $unknown, rotation $rotation")
            }
        }
    }

    @Test fun unknownAndUniformMatricesRemainUnassigned() {
        for (value in ModuleValue.entries) {
            val matrix = QrMatrix(25)
            for (y in 0 until 25) for (x in 0 until 25) matrix.set(x, y, value, 0f, ModuleSource.IMAGE)
            assertNull(QrTextMatrixOrientationEstimator.estimate(matrix))
        }
    }

    @Test fun oneOrTwoFindersDoNotEstablishAnOrientation() {
        for (remaining in 1..2) {
            val matrix = structure(25)
            for ((sx, sy) in listOf(18 to 0, 0 to 18).take(3 - remaining)) {
                for (y in 0..6) for (x in 0..6) matrix.set(sx + x, sy + y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
            assertNull(QrTextMatrixOrientationEstimator.estimate(matrix))
        }
    }

    @Test fun symmetricStructureDoesNotSelectAnArbitraryOrientation() {
        assertNull(QrTextMatrixOrientationEstimator.estimate(symmetricStructure()))
    }

    @Test fun aSmallLeadOverAnInsufficientAlternativeDoesNotEstablishOrientation() {
        val input = symmetricStructure()
        // Only the rotation excluding this corner passes the per-Finder error gate, but
        // the other orientations remain too similar to establish a reliable assignment.
        for (x in 2..4) input.set(x, 3, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        assertNull(QrTextMatrixOrientationEstimator.estimate(input))
    }

    private fun symmetricStructure(): QrMatrix {
        val symmetric = QrMatrix(25)
        for (rotation in 0..3) {
            val part = rotated(structure(25), rotation)
            for (y in 0 until 25) for (x in 0 until 25) {
                val value = part[x, y].value
                if (value == ModuleValue.ERASED) continue
                if (symmetric[x, y].value != ModuleValue.ERASED) assertEquals(symmetric[x, y].value, value)
                symmetric.set(x, y, value, 1f, ModuleSource.IMAGE)
            }
        }
        return symmetric
    }

    @Test fun estimationPreservesCellValuesProvenanceAndPriorityMarks() {
        val input = structure(25)
        input.set(12, 10, ModuleValue.ONE, 1f, ModuleSource.USER)
        input.set(13, 10, ModuleValue.ZERO, .7f, ModuleSource.USER_SOFT)
        input.setPrioritySearch(10, 12, true)
        val before = input.copy()
        assertNotNull(QrTextMatrixOrientationEstimator.estimate(input))
        for (y in 0 until 25) for (x in 0 until 25) assertEquals(before[x, y], input[x, y])

        input.set(7, 4, ModuleValue.ERASED, 0f, ModuleSource.USER_SOFT)
        input.setPrioritySearch(7, 4, true)
        assertNotNull(QrTextMatrixOrientationEstimator.estimate(input))
        assertEquals(ModuleValue.ERASED, input[7, 4].value, "Assignment must not fill a priority cell")
    }

    private fun structure(n: Int): QrMatrix {
        val rows = Array(n) { CharArray(n) { '?' } }
        val finder = listOf("11111110", "10000010", "10111010", "10111010",
            "10111010", "10000010", "11111110", "00000000")
        for (y in finder.indices) for (x in finder.indices) {
            rows[y][x] = finder[y][x]
            rows[y][n - 1 - x] = finder[y][x]
            rows[n - 1 - y][x] = finder[y][x]
        }
        for (i in 8 until n - 8) {
            val bit = if (i % 2 == 0) '1' else '0'
            rows[6][i] = bit
            rows[i][6] = bit
        }
        return QrTextMatrixParser.parse(rows.joinToString("\n") { it.concatToString() }).matrix
    }

    private fun rotated(input: QrMatrix, quarterTurns: Int): QrMatrix {
        var result = input
        repeat(quarterTurns) {
            val before = result
            result = QrMatrix(input.dimension)
            for (y in 0 until input.dimension) for (x in 0 until input.dimension) {
                val cell = before[y, input.dimension - 1 - x]
                result.set(x, y, cell.value, cell.confidence, cell.source)
                result.setPrioritySearch(x, y, cell.prioritySearch)
            }
        }
        return result
    }
}
