package io.github.earthdiver.qrrepair

import android.app.Activity
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreprocessCellEditControllerTest {
    @Test fun unchangedStampSynchronizesSelectionWithoutAnEditOrHistoryAndSourceChangesUseAllCells() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val cells = PreRecoveryCellEditState(emptyMap(), emptySet())
            val host = Host(cells)
            val controller = PreprocessCellEditController(activity.get(), cells, host)
            val targets = (9..11).flatMap { y -> (9..11).map { x -> Coord(x, y) } }.toSet()
            cells.select(setOf(Coord(1, 1)))
            controller.setValues(targets, ModuleValue.ERASED, asRectangle = true)
            assertEquals(targets, host.selection!!.cells)
            assertTrue(host.selection!!.isRectangle)
            assertEquals(0, host.matrixUpdates)
            assertEquals(0, host.records)
            assertEquals(0, cells.undoCount)
            controller.changeSource(CellEditSource.TENTATIVE)
            assertEquals(targets, cells.softEdits)
            assertEquals(1, host.matrixUpdates)
            assertEquals(targets, host.selection!!.cells)
            controller.changeSource(CellEditSource.IMAGE)
            assertTrue(cells.edits.isEmpty())
            assertEquals(targets, host.selection!!.cells)
            assertTrue(host.selection!!.isRectangle)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun rejectedAssignmentUpdatesSelectionWithoutRecordingAnEdit() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val cells = PreRecoveryCellEditState(emptyMap(), emptySet(), validateEdits = { false })
            val host = Host(cells)
            val controller = PreprocessCellEditController(activity.get(), cells, host)
            val targets = setOf(Coord(10, 10), Coord(11, 10))
            controller.setValues(targets, ModuleValue.ONE, asRectangle = true)
            assertEquals(CellEditOutcome.REJECTED, cells.lastEditOutcome)
            assertEquals(targets, host.selection!!.cells)
            assertTrue(host.selection!!.isRectangle)
            assertEquals(0, host.records)
            assertEquals(0, host.matrixUpdates)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun unavailableImageRestoresTheCommittedSelectionInsteadOfLeavingAGesturePreview() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val cells = PreRecoveryCellEditState(emptyMap(), emptySet())
            val old = setOf(Coord(2, 2), Coord(3, 2))
            cells.select(old, true)
            val host = Host(cells).apply { original = null }
            val controller = PreprocessCellEditController(activity.get(), cells, host)
            controller.setValues(setOf(Coord(10, 10)), ModuleValue.ERASED, asRectangle = true)
            assertEquals(old, host.selection!!.cells)
            assertTrue(host.selection!!.isRectangle)
            assertEquals(0, host.records)
            assertEquals(0, cells.undoCount)
        } finally { activity.pause().stop().destroy() }
    }

    private class Host(val cells: PreRecoveryCellEditState) : PreprocessCellEditController.Host {
        var original: QrMatrix? = QrMatrix(21)
        var selection: CellEditSelection? = null
        var matrixUpdates = 0
        var records = 0
        override fun observedMatrix() = original
        override fun hasLogicalGrid() = true
        override fun regions() = QrCellRegions(1)
        override fun regionLabel(coord: Coord) = "Data"
        override fun refreshSelection() { selection = cells.selection }
        override fun refreshEditedCells(changed: Set<Coord>) { matrixUpdates++; refreshSelection() }
        override fun showStatus(text: String) = Unit
        override fun recordChange(key: String, label: String, detail: String) { records++ }
    }
}
