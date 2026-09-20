package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun byteLimitPreservesActiveAndLatestSnapshotsAndCursor() {
        val history = RecoverySessionHistory()
        repeat(6) { history.record(RecoveryHistoryEntry(kind = HistoryEventKind.CANDIDATE,
            label = "candidate $it", snapshot = snapshot(ModuleValue.ZERO))) }
        history.jump(1)

        assertEquals(3, history.trimToByteLimit(30) { 10L })
        assertEquals(listOf("candidate 1", "candidate 4", "candidate 5"), history.entries.map { it.label })
        assertEquals(0, history.cursor)
        assertEquals("candidate 1", history.current?.label)
        assertEquals("candidate 4", history.redo()?.label)
        assertEquals("candidate 5", history.redo()?.label)
    }

    @Test
    fun byteLimitFailureDoesNotPartiallyDiscardHistory() {
        val history = RecoverySessionHistory()
        repeat(3) { history.record(RecoveryHistoryEntry(kind = HistoryEventKind.CANDIDATE,
            label = "candidate $it", snapshot = snapshot(ModuleValue.ZERO))) }
        history.jump(0)

        assertThrows(IllegalArgumentException::class.java) { history.trimToByteLimit(19) { 10L } }

        assertEquals(3, history.entries.size)
        assertEquals(0, history.cursor)
    }

    @Test
    fun latestSnapshotSurvivesWhenOnlyOneEntryFits() {
        val history = RecoverySessionHistory()
        repeat(3) { history.record(RecoveryHistoryEntry(kind = HistoryEventKind.CANDIDATE,
            label = "candidate $it", snapshot = snapshot(ModuleValue.ZERO))) }

        assertEquals(2, history.trimToByteLimit(10) { 10L })
        assertEquals("candidate 2", history.current?.label)
        assertEquals(0, history.cursor)
    }

    @Test
    fun savedIdentityOwnsItsCodewordsAndDistinguishesQrParameters() {
        val words = ByteArray(26) { it.toByte() }
        val identity = RecoverySelectionIdentity(1, 0, 2, words)
        words[0] = 99
        val exported = identity.codewordBytes()
        exported[1] = 88

        assertArrayEquals(ByteArray(26) { it.toByte() }, identity.codewordBytes())
        assertEquals(RecoverySelectionIdentity(1, 0, 2, ByteArray(26) { it.toByte() }), identity)
        assertFalse(identity == RecoverySelectionIdentity(1, 1, 2, identity.codewordBytes()))
        assertFalse(identity == RecoverySelectionIdentity(1, 0, 3, identity.codewordBytes()))
        assertThrows(IllegalArgumentException::class.java) { RecoverySelectionIdentity(1, 0, 2, ByteArray(25)) }
        assertThrows(IllegalArgumentException::class.java) { RecoverySelectionIdentity(0, 0, 2, ByteArray(26)) }
    }

}
