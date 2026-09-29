package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ModuleValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRecoveryCellEditStateTest {
    @Test fun loweringPriorityLimitPreservesMarksAcrossCopies() {
        val coords = (0 until 14).map { Coord(it, 12) }
        val state = PreRecoveryCellEditState(emptyMap(), emptySet(), priorityLimit = 14)
        assertEquals(PriorityEditResult.CHANGED, state.setPrioritySearch(coords))
        state.priorityLimit = 4
        assertEquals(coords.toSet(), state.prioritySearch)
        val copy = state.copyForEditing()
        assertEquals(coords.toSet(), copy.prioritySearch)
        assertEquals(4, copy.priorityLimit)
        assertEquals(PriorityEditResult.LIMIT_EXCEEDED, copy.setPrioritySearch(listOf(Coord(14, 12))))
    }

    @Test fun mirrorTogglePreservesValuesSourcesSelectionAndUndoRedoOnImageCells() {
        val fixed = Coord(12, 9)
        val tentative = Coord(13, 10)
        val priority = Coord(15, 11)
        val state = PreRecoveryCellEditState(mapOf(fixed to ModuleValue.ONE, tentative to ModuleValue.ZERO),
            emptySet(), initialSoftEdits = setOf(tentative))
        state.setPrioritySearch(listOf(priority))
        state.undo()
        state.select(listOf(fixed, tentative))
        state.transposeCoordinates()
        assertEquals(mapOf(Coord(9, 12) to ModuleValue.ONE, Coord(10, 13) to ModuleValue.ZERO), state.edits)
        assertEquals(CellEditSource.FIXED, state.sourceAt(Coord(9, 12)))
        assertEquals(CellEditSource.TENTATIVE, state.sourceAt(Coord(10, 13)))
        assertEquals(setOf(Coord(9, 12), Coord(10, 13)), state.selectedCells)
        state.redo()
        assertEquals(setOf(Coord(11, 15)), state.prioritySearch)
        assertEquals(ModuleValue.ERASED, state.edits[Coord(11, 15)])
        state.transposeCoordinates()
        assertEquals(setOf(priority), state.prioritySearch)
        state.undo()
        state.undo()
        assertTrue(state.edits.isEmpty())
        state.redo()
        assertEquals(mapOf(fixed to ModuleValue.ONE, tentative to ModuleValue.ZERO), state.edits)
        assertEquals(setOf(tentative), state.softEdits)
    }

    private val a = Coord(2, 3)
    private val b = Coord(4, 5)

    @Test fun priorityValidationAlsoRemovesInvalidMarksFromRedoHistory() {
        val coord = Coord(24, 24)
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())
        state.setPrioritySearch(listOf(coord))
        state.undo()
        state.rotateCoordinates(25, 1)
        val allowed = io.github.earthdiver.qrrepair.core.QrCellRegions(2).codewordCoordinates
        assertTrue(state.revalidatePrioritySearch { it in allowed }.isEmpty())
        state.redo()
        assertTrue(state.prioritySearch.isEmpty())
        assertEquals(ModuleValue.ERASED, state.edits[Coord(24, 0)])
    }

    @Test
    fun returningToImageValueKeepsManualSourceUntilExplicitlyRestored() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())

        assertEquals(setOf(a), state.applyValues(listOf(a), ModuleValue.ONE) { ModuleValue.ZERO })
        assertEquals(ModuleValue.ONE, state.edits[a])
        assertEquals(1, state.undoCount)

        assertEquals(setOf(a), state.applyValues(listOf(a), ModuleValue.ZERO) { ModuleValue.ZERO })
        assertEquals(ModuleValue.ZERO, state.edits[a])
        assertEquals(setOf(a), state.restoreImage(listOf(a)))
        assertTrue(a !in state.edits)
        assertEquals(setOf(a), state.undo())
        assertEquals(ModuleValue.ZERO, state.edits[a])
        assertEquals(setOf(a), state.redo())
        assertTrue(a !in state.edits)
    }

    @Test
    fun equalValueTapsDoNotCreateOverridesOrConsumeRedo() {
        for (baseline in ModuleValue.entries) {
            val state = PreRecoveryCellEditState(emptyMap(), emptySet())
            state.setSource(listOf(b), if (baseline == ModuleValue.ERASED) CellEditSource.TENTATIVE else CellEditSource.FIXED) { baseline }
            state.undo()
            assertTrue(state.applyValues(listOf(a), baseline) { baseline }.isEmpty())
            assertTrue(state.edits.isEmpty())
            assertEquals(0, state.undoCount)
            assertEquals(1, state.redoCount)
        }
    }

    @Test
    fun allFourBinaryCasesHaveTheSameRulesForEveryCoordinate() {
        // A format bit and a data bit use exactly the same state transition.
        for (coord in listOf(Coord(8, 0), Coord(10, 10))) {
            for (baseline in listOf(ModuleValue.ZERO, ModuleValue.ONE)) {
                for (input in listOf(ModuleValue.ZERO, ModuleValue.ONE)) {
                    val state = PreRecoveryCellEditState(emptyMap(), emptySet())
                    state.applyValues(listOf(coord), input) { baseline }
                    if (input == baseline) {
                        assertTrue(state.edits.isEmpty())
                        assertEquals(0, state.undoCount)
                    } else {
                        assertEquals(input, state.edits[coord])
                        assertEquals(1, state.undoCount)
                    }
                }
            }
        }
    }

    @Test
    fun explicitPinAndItsSourceOnlyUndoRedoAreRetainedAcrossCheckpoints() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())
        assertEquals(setOf(a), state.makeManual(listOf(a)) { ModuleValue.ZERO })
        assertTrue(state.applyValues(listOf(a), ModuleValue.ZERO) { ModuleValue.ZERO }.isEmpty())
        assertEquals(ModuleValue.ZERO, state.edits[a])
        state.undo()
        assertTrue(state.edits.isEmpty())
        val reopened = state.copyForEditing()
        reopened.redo()
        assertEquals(ModuleValue.ZERO, reopened.edits[a])
        reopened.restoreImage(listOf(a))
        reopened.undo()
        assertEquals(ModuleValue.ZERO, reopened.edits[a])
        reopened.redo()
        assertTrue(reopened.edits.isEmpty())
    }

    @Test
    fun restoringMixedSelectionDropsPriorityWithoutChangingUnselectedCells() {
        val state = PreRecoveryCellEditState(mapOf(b to ModuleValue.ONE), emptySet())
        state.setPrioritySearch(listOf(a))
        val image = Coord(12, 12)
        state.select(listOf(a, image))
        assertTrue(state.selectionHasManualEdits)
        assertEquals(setOf(a), state.restoreImage(state.selectedCells))
        assertEquals(mapOf(b to ModuleValue.ONE), state.edits)
        assertTrue(state.prioritySearch.isEmpty())
        assertTrue(!state.selectionHasManualEdits)
        state.undo()
        assertEquals(ModuleValue.ERASED, state.edits[a])
        assertEquals(setOf(a), state.prioritySearch)
    }

    @Test
    fun restoringUsesNoOldImageValueAndNewPinUsesCurrentSampling() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())
        state.makeManual(listOf(a)) { ModuleValue.ONE }
        state.restoreImage(listOf(a))
        state.makeManual(listOf(a)) { ModuleValue.ZERO }
        assertEquals(ModuleValue.ZERO, state.edits[a])
    }

    @Test
    fun explicitPinIsAtomicWhenOneValueConflictsAndClearingRemainsPossible() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet(), validateEdits = { b !in it })
        state.select(listOf(a, b))
        assertTrue(state.makeManual(state.selectedCells) { ModuleValue.ONE }.isEmpty())
        assertTrue(state.edits.isEmpty())
        assertEquals(0, state.undoCount)
        assertEquals(setOf(a, b), state.selectedCells)
        val saved = PreRecoveryCellEditState(mapOf(a to ModuleValue.ONE, b to ModuleValue.ONE), emptySet(),
            validateEdits = { it.isEmpty() })
        assertEquals(setOf(a), saved.restoreImage(listOf(a)))
        assertEquals(mapOf(b to ModuleValue.ONE), saved.edits)
        assertTrue(saved.undo().isEmpty())
        assertEquals(mapOf(b to ModuleValue.ONE), saved.edits)
    }

    @Test
    fun prioritySearchIsBoundedAndUndoRestoresThePreviousEdit() {
        val state = PreRecoveryCellEditState(mapOf(a to ModuleValue.ONE), emptySet(), priorityLimit = 1)

        assertEquals(PriorityEditResult.CHANGED, state.setPrioritySearch(listOf(a)))
        assertEquals(ModuleValue.ERASED, state.edits[a])
        assertEquals(setOf(a), state.prioritySearch)
        assertEquals(PriorityEditResult.LIMIT_EXCEEDED, state.setPrioritySearch(listOf(b)))

        state.undo()
        assertEquals(ModuleValue.ONE, state.edits[a])
        assertTrue(state.prioritySearch.isEmpty())
        state.redo()
        assertEquals(ModuleValue.ERASED, state.edits[a])
        assertEquals(setOf(a), state.prioritySearch)
    }

    @Test
    fun persistedEditsStartAsOneUndoableTransaction() {
        val state = PreRecoveryCellEditState(mapOf(a to ModuleValue.ZERO, b to ModuleValue.ONE), emptySet())

        assertEquals("変更操作: 1回・変更セル: 2セル　やり直し: 0回　優先探索: 0/4", state.countLabel)
        assertEquals(setOf(a, b), state.undo())
        assertTrue(state.edits.isEmpty())
        assertEquals("変更操作: 0回・変更セル: 0セル　やり直し: 1回　優先探索: 0/4", state.countLabel)
    }

    @Test
    fun newEditClearsRedoBranch() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())
        state.applyValues(listOf(a), ModuleValue.ONE) { ModuleValue.ZERO }
        state.undo()
        assertEquals(1, state.redoCount)

        state.applyValues(listOf(b), ModuleValue.ONE) { ModuleValue.ZERO }

        assertEquals(0, state.redoCount)
        assertTrue(state.redo().isEmpty())
    }

    @Test
    fun checkpointRetainsBothStacksAndIsIndependentOfDiscardedDraft() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())
        state.applyValues(listOf(a), ModuleValue.ONE) { ModuleValue.ZERO }
        state.setPrioritySearch(listOf(b))
        state.undo()
        val saved = state.copyForEditing()
        state.clearAll()
        val reopened = saved.copyForEditing()
        assertEquals(1, reopened.undoCount)
        assertEquals(1, reopened.redoCount)
        assertEquals(setOf(b), reopened.redo())
        assertEquals(setOf(b), reopened.prioritySearch)
        reopened.undo()
        reopened.undo()
        assertTrue(reopened.edits.isEmpty())
        assertEquals(mapOf(a to ModuleValue.ONE), saved.edits)
        assertEquals(1, saved.undoCount)
        assertEquals(1, saved.redoCount)
    }

    @Test
    fun reopenedDraftUsesCurrentValidationAndCanRetryBlockedRedo() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())
        state.applyValues(listOf(a), ModuleValue.ONE) { ModuleValue.ZERO }
        state.undo()
        var allowed = false
        val reopened = state.copyForEditing { allowed }
        assertTrue(reopened.redo().isEmpty())
        assertEquals(1, reopened.redoCount)
        allowed = true
        assertEquals(setOf(a), reopened.redo())
    }

    @Test
    fun rotatingProvisionalGridKeepsEditsAndHistoryOnTheSamePhysicalCells() {
        val state = PreRecoveryCellEditState(emptyMap(), emptySet())
        state.applyValues(listOf(a), ModuleValue.ONE) { ModuleValue.ZERO }
        state.setPrioritySearch(listOf(b))

        state.rotateCoordinates(dimension = 21, quarterTurns = 1)

        val rotatedA = Coord(3, 18)
        val rotatedB = Coord(5, 16)
        assertEquals(ModuleValue.ONE, state.edits[rotatedA])
        assertEquals(ModuleValue.ERASED, state.edits[rotatedB])
        assertEquals(setOf(rotatedB), state.prioritySearch)
        assertEquals(setOf(rotatedB), state.undo())
        assertTrue(rotatedB !in state.edits)
        assertEquals(setOf(rotatedB), state.redo())
        assertEquals(ModuleValue.ERASED, state.edits[rotatedB])
    }
}
