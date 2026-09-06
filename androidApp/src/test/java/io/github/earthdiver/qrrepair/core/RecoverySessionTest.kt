package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySessionTest {
    private fun snapshot(value: ModuleValue): RecoverySnapshot {
        val matrix = QrMatrix(21)
        matrix.set(10, 10, value, 1f, ModuleSource.USER)
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 20.0, 20.0, 3.0, 0.0)
        return RecoverySnapshot(geometry, matrix.copy(), matrix, 0)
    }

    @Test
    fun historySupportsUndoRedoAndArbitraryJump() {
        val history = RecoverySessionHistory(maxEntries = 10)
        history.record(RecoveryHistoryEntry(kind = HistoryEventKind.AUTO_RECOVERY, label = "auto", snapshot = snapshot(ModuleValue.ERASED)))
        history.record(RecoveryHistoryEntry(kind = HistoryEventKind.USER_EDIT, label = "zero", snapshot = snapshot(ModuleValue.ZERO)))
        history.record(RecoveryHistoryEntry(kind = HistoryEventKind.USER_EDIT, label = "one", snapshot = snapshot(ModuleValue.ONE)))

        assertTrue(history.canUndo)
        assertEquals("zero", history.undo()?.label)
        assertEquals("one", history.redo()?.label)
        assertEquals("auto", history.jump(0)?.label)
        assertFalse(history.canUndo)
    }

    @Test
    fun replaceKeepsCursorAttachedToSameRetainedEntryWhenTrimming() {
        val entries = (0 until 8).map { i ->
            RecoveryHistoryEntry(kind = HistoryEventKind.USER_EDIT, label = "e$i", snapshot = snapshot(ModuleValue.ZERO))
        }
        val history = RecoverySessionHistory(maxEntries = 4)
        history.replace(entries, cursor = 5)
        assertEquals(listOf("e4", "e5", "e6", "e7"), history.entries.map { it.label })
        assertEquals("e5", history.current?.label)
    }
}
