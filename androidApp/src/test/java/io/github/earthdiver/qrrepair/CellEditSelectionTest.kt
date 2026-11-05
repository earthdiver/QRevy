package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ModuleValue
import org.junit.Assert.*
import org.junit.Test

class CellEditSelectionTest {
    private val targets = (2..4).flatMap { y -> (3..5).map { x -> Coord(x, y) } }.toSet()
    private val changed = Coord(4, 3)
    private fun state(validate: (Map<Coord, ModuleValue>) -> Boolean = { true }) =
        PreRecoveryCellEditState(emptyMap(), emptySet(), validateEdits = validate)
    private fun baseline(coord: Coord) = if (coord == changed) ModuleValue.ZERO else ModuleValue.ONE

    @Test fun partialChangesKeepTheWholeRectangleWithoutMakingMatchingCellsManual() {
        val state = state()
        state.select(targets, true, changed)
        assertEquals(setOf(changed), state.applyValues(targets, ModuleValue.ONE, ::baseline))
        assertEquals(mapOf(changed to ModuleValue.ONE), state.edits)
        assertEquals(targets, state.selectedCells)
        assertTrue(state.selection.isRectangle)
        assertEquals(changed, state.selection.focus)
        assertEquals(CellEditSource.IMAGE, state.sourceAt(targets.first()))
        state.setSource(state.selectedCells, CellEditSource.TENTATIVE, ::baseline)
        assertEquals(targets, state.softEdits)
        assertEquals(targets, state.selectedCells)
        state.restoreImage(state.selectedCells)
        assertTrue(state.edits.isEmpty())
        assertEquals(targets, state.selectedCells)
        assertTrue(state.selection.isRectangle)
    }

    @Test fun noOpSelectionDoesNotConsumeRedoOrCreateManualUnknowns() {
        val state = state()
        state.applyValues(setOf(changed), ModuleValue.ONE) { ModuleValue.ZERO }
        state.undo()
        state.select(targets, true)
        assertTrue(state.applyValues(targets, ModuleValue.ERASED) { ModuleValue.ERASED }.isEmpty())
        assertEquals(targets, state.selectedCells)
        assertTrue(state.selection.isRectangle)
        assertEquals(CellEditOutcome.UNCHANGED, state.lastEditOutcome)
        assertTrue(state.edits.isEmpty())
        assertEquals(0, state.undoCount)
        assertEquals(1, state.redoCount)
    }

    @Test fun undoRedoRestoreOnlyTheDifferenceButSelectTheWholeOperationTarget() {
        val state = state()
        state.select(targets, true, changed)
        state.applyValues(targets, ModuleValue.ONE, ::baseline)
        val operationSelection = state.selection
        state.select(setOf(Coord(15, 15)))
        assertEquals(setOf(changed), state.undo())
        assertSame(operationSelection, state.selection)
        assertTrue(state.edits.isEmpty())
        state.redo()
        assertSame(operationSelection, state.selection)
        assertEquals(mapOf(changed to ModuleValue.ONE), state.edits)
        state.applyValues(state.selectedCells, ModuleValue.ZERO, ::baseline)
        assertEquals(targets, state.edits.keys)
    }

    @Test fun rejectedEditsKeepTargetsButRejectedHistoryKeepsTheCurrentSelection() {
        var accept = false
        val state = state { accept }
        state.select(targets, true)
        assertTrue(state.applyValues(targets, ModuleValue.ONE, ::baseline).isEmpty())
        assertEquals(CellEditOutcome.REJECTED, state.lastEditOutcome)
        assertEquals(targets, state.selectedCells)
        assertEquals(0, state.undoCount)
        accept = true
        state.applyValues(targets, ModuleValue.ONE, ::baseline)
        state.restoreImage(targets)
        val other = setOf(Coord(15, 15))
        state.select(other)
        accept = false
        assertTrue(state.undo().isEmpty())
        assertEquals(other, state.selectedCells)
        assertEquals(2, state.undoCount)
        assertEquals(0, state.redoCount)
    }

    @Test fun mixedUnknownFixedAssignmentIsAtomicAndPreservesTheSelection() {
        val state = state()
        state.select(targets, true)
        assertTrue(state.setSource(targets, CellEditSource.FIXED) {
            if (it == changed) ModuleValue.ERASED else ModuleValue.ONE
        }.isEmpty())
        assertEquals(CellEditOutcome.REJECTED, state.lastEditOutcome)
        assertTrue(state.edits.isEmpty())
        assertEquals(targets, state.selectedCells)
        assertTrue(state.selection.isRectangle)
    }

    @Test fun copiedHistoryTransformsTheWholeSelectionAndFocusWithTheImage() {
        val state = state()
        state.select(targets, true, changed)
        state.applyValues(targets, ModuleValue.ONE, ::baseline)
        val copy = state.copyForEditing()
        assertTrue(copy.selectedCells.isEmpty())
        copy.rotateCoordinates(21, 1)
        copy.transposeCoordinates()
        copy.undo()
        assertEquals(targets.map { Coord(20 - it.x, it.y) }.toSet(), copy.selectedCells)
        assertEquals(Coord(16, 3), copy.selection.focus)
        assertTrue(copy.selection.isRectangle)
        copy.redo()
        assertEquals(setOf(Coord(16, 3)), copy.edits.keys)
        assertEquals(targets, state.selectedCells)
        assertEquals(mapOf(changed to ModuleValue.ONE), state.edits)
    }

    @Test fun partialPriorityAndImageRestorationDoNotShrinkSelection() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet(), priorityLimit = 14)
        state.setPrioritySearch(setOf(changed))
        state.select(targets, true)
        assertEquals(PriorityEditResult.CHANGED, state.setPrioritySearch(targets))
        assertEquals(targets - changed, state.lastChangedCells)
        assertEquals(targets, state.selectedCells)
        state.undo()
        assertEquals(setOf(changed), state.prioritySearch)
        assertEquals(targets, state.selectedCells)
        assertEquals(setOf(changed), state.restoreImage(targets))
        assertEquals(targets, state.selectedCells)
    }

    @Test fun selectionsOwnTheirInputsAndShareUnchangedTargetsAcrossTransactions() {
        val input = targets.toMutableSet()
        val state = state()
        state.select(input, true)
        input.clear()
        assertEquals(targets, state.selectedCells)
        val selection = state.selection
        state.applyValues(targets, ModuleValue.ONE, ::baseline)
        state.setSource(targets, CellEditSource.TENTATIVE, ::baseline)
        assertSame(selection, state.selection)
        state.undo()
        assertSame(selection, state.selection)
    }
}
