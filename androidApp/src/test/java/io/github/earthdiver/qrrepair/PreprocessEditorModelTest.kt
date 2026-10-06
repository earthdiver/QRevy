package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.PolarityMode
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreprocessEditorModelTest {
    private val controls = PreprocessControlSnapshot(PolarityMode.NORMAL, false, 96, 159, false, 1)
    private val grid = PreprocessGeometrySnapshot(true,
        ImageQuad(PointD(2.0, 2.0), PointD(22.0, 2.0), PointD(22.0, 22.0), PointD(2.0, 22.0)), emptyMap())

    private fun image() = BitmapUtils.prepare(Bitmap.createBitmap(25, 25, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
    })

    @Test fun savingAndRestoringKeepIndependentEditsAndReplaceTheValidationCallback() {
        val source = image()
        try {
            val editor = PreprocessEditorModel(PreprocessSettings(version = 1), source, source,
                null, null, { false }, { true })
            val coord = Coord(10, 10)
            editor.cells.applyValues(listOf(coord), ModuleValue.ONE) { ModuleValue.ZERO }
            val settings = editor.captureSettings(controls, grid)
            editor.noteChange("cell-edit", "Edited cells", "1 cell", settings)
            val saved = editor.commit(settings)
            val draft = editor.captureDraft(settings, PreprocessPage.CELL_EDITOR)
            assertFalse(editor.hasUnsavedChanges(settings))

            editor.cells.undo()
            assertTrue(editor.hasUnsavedChanges(editor.captureSettings(controls, grid)))
            assertEquals(ModuleValue.ONE, saved.settings.preRecoveryEdits[coord])
            assertEquals(ModuleValue.ONE, saved.cellEdits.edits[coord])
            assertEquals(ModuleValue.ONE, draft.cellEdits.edits[coord])
            assertEquals("cell-edit", saved.events.single().key)

            val restored = PreprocessEditorModel(PreprocessSettings(), source, source,
                null, draft, { false }, { false })
            assertTrue(restored.cells.applyValues(listOf(coord), ModuleValue.ZERO) { ModuleValue.ZERO }.isEmpty())
            assertEquals(ModuleValue.ONE, restored.cells.edits[coord])
            assertEquals(setOf(coord), restored.cells.undo())
            assertTrue(saved.cellEdits.undo().isNotEmpty())
            assertEquals(ModuleValue.ONE, draft.cellEdits.edits[coord])
        } finally { source.bitmap.recycle() }
    }

    @Test fun replacingAndClosingPreserveBorrowedAndRetainedImages() {
        val source = image()
        val first = image()
        val next = image()
        var retained: BitmapUtils.Prepared? = null
        val editor = PreprocessEditorModel(PreprocessSettings(version = 1), source, first,
            null, null, { it === retained }, { true })
        try {
            val sameBitmap = BitmapUtils.Prepared(first.bitmap, first.luma)
            editor.replaceWorking(sameBitmap)
            assertFalse(first.bitmap.isRecycled)
            retained = editor.commit(editor.captureSettings(controls, grid)).image
            editor.replaceWorking(next)
            assertFalse(first.bitmap.isRecycled)
            editor.release()
            editor.release()
            assertTrue(next.bitmap.isRecycled)
            assertFalse(first.bitmap.isRecycled)
            assertFalse(source.bitmap.isRecycled)
        } finally {
            listOf(source, first, next).forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }

    @Test fun previewEditsNeverAlterTheNextObservation() {
        val source = image()
        try {
            val editor = PreprocessEditorModel(PreprocessSettings(version = 1), source, source,
                null, null, { false }, { true })
            val preview = requireNotNull(editor.sample(controls, grid))
            assertEquals(ModuleValue.ZERO, preview.matrix[10, 10].value)
            preview.matrix.set(10, 10, ModuleValue.ONE, 1f, ModuleSource.USER)
            val next = requireNotNull(editor.sample(controls, grid))
            assertEquals(ModuleValue.ZERO, next.matrix[10, 10].value)
            assertEquals(preview.geometry, next.geometry)
            assertEquals(grid.quad, next.geometry.imageQuad)
        } finally { source.bitmap.recycle() }
    }
}
