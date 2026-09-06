package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class FormatEditPolicyTest {
    @Test fun fixedBitsMatchAllPermittedFormatsInBothCopies() {
        val constraints = ErrorCorrectionLevel.entries.map { QrFormatConstraint(ecLevel = it) } +
            (0..7).map { QrFormatConstraint(dataMask = it) } +
            ErrorCorrectionLevel.entries.flatMap { ec -> (0..7).map { QrFormatConstraint(ec, it) } }
        for (version in listOf(1, 7, 40)) for (constraint in constraints) {
            val formats = ErrorCorrectionLevel.entries.flatMap { ec -> (0..7).mapNotNull { mask ->
                if (!constraint.permits(ec, mask)) null else QrMatrix(17 + 4 * version).also {
                    StructuralRepair.apply(it, ec, mask)
                }
            } }
            val fixed = FormatEditPolicy.fixedValues(version, constraint)
            for (coord in FormatEditPolicy.coordinates(version)) {
                val values = formats.map { it[coord.x, coord.y].value }.distinct()
                assertEquals(values.singleOrNull(), fixed[coord])
                if (values.size == 1) {
                    val opposite = if (values.single() == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
                    val conflict = FormatEditPolicy.conflict(version, constraint, mapOf(coord to opposite))!!
                    assertEquals(values.single(), conflict.cells.single().expected)
                    assertNull(FormatEditPolicy.conflict(version, constraint, mapOf(coord to values.single())))
                }
            }
        }
    }

    @Test fun collectiveConflictIsNotMisreportedAsAnIndividuallyWrongBit() {
        val constraint = QrFormatConstraint(ecLevel = ErrorCorrectionLevel.L)
        val fixed = FormatEditPolicy.fixedValues(1, constraint)
        val coord = FormatEditPolicy.coordinates(1).first { it !in fixed }
        val copies = FormatSolver.copies(21)
        val index = copies[0].indexOf(coord.x to coord.y)
        val other = copies[1][index].let { Coord(it.first, it.second) }
        assertNull(FormatEditPolicy.conflict(1, constraint, mapOf(coord to ModuleValue.ZERO)))
        assertNull(FormatEditPolicy.conflict(1, constraint, mapOf(other to ModuleValue.ONE)))
        val conflict = FormatEditPolicy.conflict(1, constraint,
            mapOf(coord to ModuleValue.ZERO, other to ModuleValue.ONE))!!
        assertEquals(setOf(coord, other), conflict.cells.map { it.coord }.toSet())
        assertTrue(conflict.cells.all { it.expected == null })
        assertNull(FormatEditPolicy.conflict(1, null, mapOf(coord to ModuleValue.ZERO, other to ModuleValue.ONE)))
    }

    @Test fun unknownAndNonFormatValuesDoNotRestrictFormatSelections() {
        val selection = QrFormatConstraint(ErrorCorrectionLevel.L, 0)
        val edits = FormatEditPolicy.coordinates(1).associateWith { ModuleValue.ERASED } +
            mapOf(Coord(0, 0) to ModuleValue.ZERO, Coord(20, 20) to ModuleValue.ONE)
        assertNull(FormatEditPolicy.conflict(1, selection, edits))
        assertTrue(FormatEditPolicy.ignoredComparisonCoords(1, selection, edits).isEmpty())
        assertTrue(FormatEditPolicy.returnsToAuto(selection, QrFormatConstraint(dataMask = 0)))
        assertTrue(FormatEditPolicy.returnsToAuto(selection, null))
        assertFalse(FormatEditPolicy.returnsToAuto(selection, QrFormatConstraint(dataMask = 1)))
    }
}
