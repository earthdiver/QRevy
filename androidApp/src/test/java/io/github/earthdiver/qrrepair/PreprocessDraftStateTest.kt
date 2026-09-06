package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.PreprocessSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessDraftStateTest {
    @Test
    fun consecutiveChangesWithSameKeyAreCoalesced() {
        val initial = PreprocessSettings()
        val state = PreprocessDraftState(initial)
        state.note("black", "暗部", "10", initial.copy(blackMax = 10))
        state.note("black", "暗部", "20", initial.copy(blackMax = 20))

        assertEquals(1, state.events.size)
        assertEquals("20", state.events.single().detail)
        assertTrue(state.hasUnsavedChanges(initial))
    }

    @Test
    fun commitRecordsPendingEventsAndOneFinalSnapshotWhenNeeded() {
        val initial = PreprocessSettings()
        val noted = initial.copy(blackMax = 20)
        val current = noted.copy(whiteMin = 220)
        val state = PreprocessDraftState(initial)
        state.note("black", "暗部", "20", noted)
        val recorded = mutableListOf<PreprocessDraftEvent>()

        state.commit(current, recorded::add)

        assertEquals(listOf("暗部", "修復設定を確定"), recorded.map { it.label })
        assertFalse(state.hasUnsavedChanges(current))
        assertTrue(state.events.isEmpty())
    }

    @Test
    fun commitDoesNotDuplicateLastEventSnapshot() {
        val initial = PreprocessSettings()
        val current = initial.copy(version = 2)
        val state = PreprocessDraftState(initial)
        state.note("version", "Version", "2", current)
        val recorded = mutableListOf<PreprocessDraftEvent>()

        state.commit(current, recorded::add)

        assertEquals(1, recorded.size)
        assertEquals("Version", recorded.single().label)
    }
}
