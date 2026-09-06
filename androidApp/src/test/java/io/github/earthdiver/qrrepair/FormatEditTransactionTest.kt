package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class FormatEditTransactionTest {
    @Test fun rejectedBatchChangesNeitherEditsNorUndoRedoOrSelection() {
        val constraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0)
        val fixed = FormatEditPolicy.fixedValues(1, constraint)
        val a = fixed.keys.first { fixed[it] == ModuleValue.ZERO }
        val b = fixed.keys.first { fixed[it] == ModuleValue.ONE }
        var errors = 0
        val state = PreRecoveryCellEditState(emptyMap(), emptySet(), validateEdits = {
            val conflict = FormatEditPolicy.conflict(1, constraint, it)
            if (conflict != null) errors++
            conflict == null
        })
        state.select(setOf(a))
        assertTrue(state.applyValues(listOf(a, b), ModuleValue.ONE) { ModuleValue.ERASED }.isEmpty())
        assertEquals(1, errors)
        assertTrue(state.edits.isEmpty())
        assertEquals(0, state.undoCount)
        assertEquals(0, state.redoCount)
        assertEquals(setOf(a), state.selectedCells)
    }

    @Test fun redoIsValidatedAgainstNewSelectionAndCanBeRetriedAfterReturningToAuto() {
        val selected = QrFormatConstraint(ErrorCorrectionLevel.L, 0)
        val fixed = FormatEditPolicy.fixedValues(1, selected)
        val coord = fixed.keys.first()
        val wrong = if (fixed[coord] == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        var constraint: QrFormatConstraint? = null
        val state = PreRecoveryCellEditState(emptyMap(), emptySet(), validateEdits = {
            FormatEditPolicy.conflict(1, constraint, it) == null
        })
        state.makeManual(listOf(coord)) { wrong }
        assertEquals(wrong, state.edits[coord]) // Explicit confirmation is retained, even if sampling agrees.
        state.undo()
        constraint = selected
        assertTrue(state.redo().isEmpty())
        assertEquals(1, state.redoCount)
        assertEquals(0, state.undoCount)
        constraint = null
        assertEquals(setOf(coord), state.redo())
    }

    @Test fun legacyConflictsCanBeClearedOneCellAtATime() {
        val selected = QrFormatConstraint(ErrorCorrectionLevel.L, 0)
        val edits = FormatEditPolicy.fixedValues(1, selected).entries.take(2).associate {
            it.key to if (it.value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        }
        val state = PreRecoveryCellEditState(edits, emptySet(), validateEdits = {
            FormatEditPolicy.conflict(1, selected, it) == null
        })
        for (coord in edits.keys) assertEquals(setOf(coord),
            state.applyValues(listOf(coord), ModuleValue.ERASED) { ModuleValue.ONE })
        assertNull(FormatEditPolicy.conflict(1, selected, state.edits))
        assertTrue(state.undo().isEmpty()) // Would reintroduce a conflicting value.
    }
}
