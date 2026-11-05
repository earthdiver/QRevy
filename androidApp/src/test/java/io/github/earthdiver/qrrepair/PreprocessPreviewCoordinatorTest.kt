package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.FinderPolarity
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.GeometryModel
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.PolarityMode
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.ThinPlateWarp
import io.github.earthdiver.qrrepair.core.WarpLandmark
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreprocessPreviewCoordinatorTest {
    private val controls = PreprocessControlSnapshot(PolarityMode.NORMAL, false, 96, 159, false, 1)
    private val quad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))
    private val grid = PreprocessGeometrySnapshot(true, quad, emptyMap(), FinderPolarity.UNKNOWN)

    private fun curved(shift: Double = 2.0) = GridGeometry(1, FinderRole.TOP_LEFT, 50.0, 50.0, 10.0, 0.0,
        quad, GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp(listOf(
            WarpLandmark(PointD(0.0, 0.0), PointD(0.0, 0.0)),
            WarpLandmark(PointD(1.0, 0.0), PointD(0.0, 0.0)),
            WarpLandmark(PointD(0.0, 1.0), PointD(0.0, 0.0)),
            WarpLandmark(PointD(1.0, 1.0), PointD(0.0, 0.0)),
            WarpLandmark(PointD(.5, .5), PointD(shift, -1.0)),
        ))).reanchored()

    @Test fun queuedCorrectionAndSamplingCompletionsCannotPublishAfterRelease() = withEditor(temporaryWorking = true) { editor, source, working ->
        val workers = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        var changes = 0
        val preview = PreprocessPreviewCoordinator(editor, Executor { workers.addLast(it) }, { ui.addLast(it) }) { changes++ }
        preview.requestCorrection(controls, grid)
        assertNull(preview.sample({ controls }, { grid }))
        assertTrue(preview.samplingPending)
        assertEquals(2, workers.size)
        // Complete observations while the restored warp is still selected, then complete the fit.
        workers.removeLast().run()
        workers.removeFirst().run()
        assertEquals(2, ui.size)
        assertEquals(0, changes)
        preview.release()
        while (ui.isNotEmpty()) ui.removeFirst().invoke()
        assertEquals(0, changes)
        assertFalse(editor.correction.complete)
        assertTrue(working.bitmap.isRecycled)
        assertFalse(source.bitmap.isRecycled)
    }

    @Test fun draggingRejectsOldWorkAndDefersFittingWhileKeepingTheStraightGridAvailable() = withEditor { editor, _, _ ->
        val workers = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        var changes = 0
        val preview = PreprocessPreviewCoordinator(editor, Executor { workers.addLast(it) }, { ui.addLast(it) }) { changes++ }
        preview.requestCorrection(controls, grid)
        val moved = grid.copy(quad = quad.copy(topLeft = PointD(24.0, 20.0)))
        preview.geometryEdited()
        assertTrue(preview.geometryDragging)
        preview.requestCorrection(controls, moved)
        assertEquals(1, workers.size)
        val straight = requireNotNull(preview.sample({ controls }, { moved }))
        assertNull(straight.geometry.warp)
        assertEquals(moved.quad, straight.geometry.imageQuad)
        assertFalse(preview.samplingPending)
        workers.removeFirst().run()
        ui.removeFirst().invoke()
        assertEquals(0, changes)
        assertFalse(editor.correctionReady(controls, moved))

        preview.geometryEditFinished()
        assertFalse(preview.geometryDragging)
        preview.requestCorrection(controls, moved)
        assertEquals(1, workers.size)
        workers.removeFirst().run()
        assertEquals(0, changes)
        ui.removeFirst().invoke()
        assertEquals(1, changes)
        assertTrue(editor.correctionReady(controls, moved))
    }

    @Test fun inlineObservationCompletionUsesRefreshedControlsInBothNestedAndOuterSamples() = withEditor { editor, _, _ ->
        editor.requestCorrection(controls, grid)
        var currentControls = controls
        var notifications = 0
        var nested: PreprocessEditorModel.Preview? = null
        lateinit var preview: PreprocessPreviewCoordinator
        preview = PreprocessPreviewCoordinator(editor, Executor { it.run() }, { it() }) {
            notifications++
            currentControls = controls.copy(blackProgress = 145)
            nested = preview.sample({ currentControls }, { grid })
        }
        val outer = requireNotNull(preview.sample({ currentControls }, { grid }))
        assertEquals(1, notifications)
        assertFalse(preview.samplingPending)
        for (sample in listOf(requireNotNull(nested), outer)) {
            assertEquals(145, sample.profile.settings.blackMax)
            assertEquals(ModuleValue.ONE, sample.matrix[10, 10].value)
            assertEquals(curved(), sample.geometry)
        }
    }

    @Test fun selectingAnotherCorrectionRejectsQueuedObservationsBeforePublishingTheNewGrid() = withEditor { editor, _, _ ->
        val correction = requireNotNull(editor.requestCorrection(controls, grid))
        editor.correction.accept(correction, listOf(curved(), curved(5.0)))
        val workers = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        var changes = 0
        val preview = PreprocessPreviewCoordinator(editor, Executor { workers.addLast(it) }, { ui.addLast(it) }) { changes++ }
        assertNull(preview.sample({ controls }, { grid }))
        workers.removeFirst().run()
        assertEquals(1, ui.size)
        preview.selectCorrection(1)
        assertEquals(0, changes)
        assertNull(preview.sample({ controls }, { grid }))
        ui.removeFirst().invoke()
        assertEquals(0, changes)
        workers.removeFirst().run()
        ui.removeFirst().invoke()
        assertEquals(1, changes)
        assertEquals(curved(5.0), preview.sample({ controls }, { grid })!!.geometry)
        assertFalse(preview.samplingPending)
    }

    private fun withEditor(temporaryWorking: Boolean = false,
        test: (PreprocessEditorModel, BitmapUtils.Prepared, BitmapUtils.Prepared) -> Unit) {
        fun image() = BitmapUtils.prepare(Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 128))
        })
        val source = image()
        val working = if (temporaryWorking) image() else source
        val settings = PreprocessSettings(version = 1, manualQuad = quad, polarity = PolarityMode.NORMAL,
            distortionCorrection = true, previewGeometry = curved())
        val editor = PreprocessEditorModel(settings, source, working, null, null, { false }, { true })
        try { test(editor, source, working) } finally {
            editor.release()
            if (!working.bitmap.isRecycled) working.bitmap.recycle()
            source.bitmap.takeIf { !it.isRecycled }?.recycle()
        }
    }
}
