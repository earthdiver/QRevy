package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test

class TentativeCellEditStateTest {
    private val a = Coord(8, 0)
    private val b = Coord(10, 12)

    @Test fun binaryEditsStartFixedAndReturningToImageValueKeepsThePin() {
        for (original in listOf(ModuleValue.ZERO, ModuleValue.ONE)) {
            val opposite = if (original == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
            val state = PreRecoveryCellEditState(emptyMap(), emptySet())
            state.applyValues(listOf(a), opposite) { original }
            assertEquals(CellEditSource.FIXED, state.sourceAt(a))
            state.applyValues(listOf(a), original) { original }
            assertEquals(CellEditSource.FIXED, state.sourceAt(a))
            state.setSource(listOf(a), CellEditSource.TENTATIVE) { opposite }
            assertEquals(original, state.edits[a])
            assertTrue(state.fixedEdits.isEmpty())
            state.applyValues(listOf(a), opposite) { original }
            assertEquals(CellEditSource.TENTATIVE, state.sourceAt(a))
            state.setSource(listOf(a), CellEditSource.IMAGE) { original }
            assertTrue(state.edits.isEmpty())
            assertTrue(state.softEdits.isEmpty())
        }
    }

    @Test fun unchangedImageValueCanBeFixedAndTentativeModeSurvivesHistoryAndRotation() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())
        state.makeManual(listOf(b)) { ModuleValue.ZERO }
        assertEquals(ModuleValue.ZERO, state.fixedEdits[b])
        state.setSource(listOf(b), CellEditSource.TENTATIVE) { ModuleValue.ONE }
        assertEquals(ModuleValue.ZERO, state.edits[b])
        val reopened = state.copyForEditing()
        reopened.rotateCoordinates(21, 1)
        val rotated = Coord(12, 10)
        assertEquals(setOf(rotated), reopened.softEdits)
        reopened.undo()
        assertEquals(CellEditSource.FIXED, reopened.sourceAt(rotated))
        reopened.redo()
        assertEquals(CellEditSource.TENTATIVE, reopened.sourceAt(rotated))
        reopened.restoreImage(listOf(rotated))
        reopened.undo()
        assertEquals(ModuleValue.ZERO, reopened.edits[rotated])
        assertEquals(CellEditSource.TENTATIVE, reopened.sourceAt(rotated))
        assertEquals(setOf(b), state.softEdits)
    }

    @Test fun tentativeFormatValueIsAllowedButFixingItMustValidateAtomically() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet(), validateEdits = { a !in it })
        state.setSource(listOf(a, b), CellEditSource.TENTATIVE) { ModuleValue.ONE }
        assertEquals(setOf(a, b), state.softEdits)
        assertTrue(state.makeManual(listOf(a, b)) { ModuleValue.ZERO }.isEmpty())
        assertEquals(setOf(a, b), state.softEdits)
        assertEquals(1, state.undoCount)
        state.restoreImage(listOf(a))
        assertEquals(setOf(b), state.softEdits)
        state.undo()
        assertEquals(setOf(a, b), state.softEdits)
    }

    @Test fun undoCannotReintroduceAFixedConflictAfterItWasMadeTentative() {
        var permitted = true
        val state = PreRecoveryCellEditState(emptyMap(), emptySet(), validateEdits = { permitted || a !in it })
        state.makeManual(listOf(a)) { ModuleValue.ONE }
        state.setSource(listOf(a), CellEditSource.TENTATIVE) { ModuleValue.ZERO }
        permitted = false
        assertTrue(state.undo().isEmpty())
        assertEquals(CellEditSource.TENTATIVE, state.sourceAt(a))
        assertEquals(ModuleValue.ONE, state.edits[a])
    }

    @Test fun unknownsAndPrioritySearchAreAlwaysTentativeAndKeepTheirHistory() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())
        assertTrue(state.makeManual(listOf(b)) { ModuleValue.ERASED }.isEmpty())
        state.makeManual(listOf(b)) { ModuleValue.ONE }
        state.applyValues(listOf(b), ModuleValue.ERASED) { ModuleValue.ONE }
        assertEquals(CellEditSource.TENTATIVE, state.sourceAt(b))
        assertTrue(state.fixedEdits.isEmpty())
        state.setPrioritySearch(listOf(b))
        assertEquals(setOf(b), state.softEdits)
        assertTrue(state.setSource(listOf(b), CellEditSource.TENTATIVE) { ModuleValue.ONE }.isEmpty())
        assertEquals(setOf(b), state.prioritySearch)
        assertTrue(state.makeManual(listOf(b)) { ModuleValue.ONE }.isEmpty())
        state.undo()
        assertTrue(state.prioritySearch.isEmpty())
        assertEquals(CellEditSource.TENTATIVE, state.sourceAt(b))
        state.undo()
        assertEquals(CellEditSource.FIXED, state.sourceAt(b))
        state.redo()
        state.redo()
        assertEquals(setOf(b), state.prioritySearch)
        assertEquals(setOf(b), state.softEdits)
        state.applyValues(listOf(b), ModuleValue.ZERO) { ModuleValue.ONE }
        assertEquals(CellEditSource.FIXED, state.sourceAt(b))
        assertTrue(state.prioritySearch.isEmpty())
        state.undo()
        assertEquals(setOf(b), state.prioritySearch)
        assertEquals(CellEditSource.TENTATIVE, state.sourceAt(b))
    }

    @Test fun mixedSelectionWithUnknownCannotPartiallyBecomeFixed() {
        val state = PreRecoveryCellEditState(mapOf(a to ModuleValue.ZERO, b to ModuleValue.ERASED), emptySet(),
            initialSoftEdits = setOf(a))
        assertEquals(setOf(a, b), state.softEdits)
        val history = state.undoCount
        assertTrue(state.makeManual(listOf(a, b)) { ModuleValue.ONE }.isEmpty())
        assertEquals(history, state.undoCount)
        assertEquals(setOf(a, b), state.softEdits)
    }
}
