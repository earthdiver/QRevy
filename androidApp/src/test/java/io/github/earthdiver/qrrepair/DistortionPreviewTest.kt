package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DistortionPreviewTest {
    private val quad = ImageQuad(PointD(20.0, 20.0), PointD(276.0, 20.0), PointD(276.0, 276.0), PointD(20.0, 276.0))
    private val grid = PreprocessGeometrySnapshot(true, quad, emptyMap(), FinderPolarity.UNKNOWN)
    private val controls = PreprocessControlSnapshot(PolarityMode.NORMAL, false, 96, 159, false, 4)
    private fun settings() = PreprocessSettings(version = 4, manualQuad = quad, polarity = PolarityMode.NORMAL, distortionCorrection = true)
    private fun geometry(shift: Double = 7.0) = GridGeometry(4, FinderRole.TOP_LEFT, 44.0, 44.0, 8.0, 0.0,
        quad, GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp(listOf(
            WarpLandmark(PointD(0.0, 0.0), PointD(0.0, 0.0)),
            WarpLandmark(PointD(1.0, 0.0), PointD(0.0, 0.0)),
            WarpLandmark(PointD(0.0, 1.0), PointD(0.0, 0.0)),
            WarpLandmark(PointD(1.0, 1.0), PointD(0.0, 0.0)),
            WarpLandmark(PointD(.5, .5), PointD(shift, -5.0)),
        ))).reanchored()
    private fun image(dark: Int = Color.BLACK) = BitmapUtils.prepare(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(300 * 300) { if (it % 300 > 152) dark else Color.WHITE }, 0, 300, 0, 0, 300, 300)
    })
    private fun editor(source: BitmapUtils.Prepared, initial: PreprocessSettings = settings()) =
        PreprocessEditorModel(initial, source, source, null, null, { true }, { true })

    @Test fun displayedCellsAndSavedRepairInputUseTheSelectedCorrectionWithoutLosingFixedEdits() {
        val source = image()
        try {
            val editor = editor(source)
            val fixed = Coord(18, 18)
            editor.cells.applyValues(listOf(fixed), ModuleValue.ZERO) { ModuleValue.ONE }
            assertEquals(ModuleValue.ZERO, editor.sample(controls, grid)!!.matrix[16, 16].value)
            val request = editor.requestCorrection(controls, grid)!!
            assertFalse(editor.correctionReady(controls, grid))
            editor.correction.accept(request, listOf(geometry(2.0), geometry()))
            editor.correction.select(1)
            val preview = editor.sample(controls, grid)!!
            assertEquals(geometry(), preview.geometry)
            assertEquals(ModuleValue.ONE, preview.matrix[16, 16].value)
            val saved = editor.captureSettings(controls, grid)
            assertEquals(preview.geometry, saved.previewGeometry)
            assertEquals(quad, saved.manualQuad)
            assertEquals(ModuleValue.ZERO, saved.fixedPreRecoveryEdits[fixed])
            val recoveredInput = MatrixSampler(source.luma, SamplingProfile(saved, false)).sample(saved.previewGeometry!!).first
            for (y in 0..32) for (x in 0..32) assertEquals(preview.matrix[x, y].value, recoveredInput[x, y].value)
        } finally { source.bitmap.recycle() }
    }

    @Test fun thresholdEditsReuseThePendingFitAndReclassifyCellsOnTheSelectedGrid() {
        val source = image(Color.rgb(128, 128, 128))
        try {
            val savedGrid = geometry()
            val editor = editor(source, settings().copy(automaticThresholds = true, previewGeometry = savedGrid))
            val manual = controls.copy(blackProgress = 90)
            val request = editor.requestCorrection(manual, grid)!!
            assertEquals(savedGrid, editor.sample(manual, grid)!!.geometry)

            val black = manual.copy(blackProgress = 145)
            assertNull(editor.requestCorrection(black, grid))
            assertTrue(editor.correction.isCurrent(request))
            assertTrue(editor.correction.accept(request, listOf(geometry(2.0), geometry(4.0), savedGrid)))
            editor.correction.select(1)
            val order = editor.correction.candidates
            val selected = order[1]
            val white = manual.copy(whiteProgress = 110)
            for ((thresholds, value) in listOf(black to ModuleValue.ONE, white to ModuleValue.ZERO, manual to ModuleValue.ERASED)) {
                assertNull(editor.requestCorrection(thresholds, grid))
                assertTrue(editor.correctionReady(thresholds, grid))
                assertSame(order, editor.correction.candidates)
                assertSame(selected, editor.correction.selected)
                val preview = editor.sample(thresholds, grid)!!
                assertSame(selected, preview.geometry)
                assertEquals(value, preview.matrix[24, 16].value)
                assertSame(selected, editor.captureSettings(thresholds, grid).previewGeometry)
            }
            assertNull(editor.requestCorrection(manual.copy(automaticThresholds = true), grid))
        } finally { source.bitmap.recycle() }
    }

    @Test fun movingCornersAndChangingVersionRejectOldResultsAndClearTheSavedSelection() {
        val source = image()
        try {
            val editor = editor(source)
            val old = editor.requestCorrection(controls, grid)!!
            editor.correction.accept(old, listOf(geometry()))
            val moved = grid.copy(quad = quad.copy(topLeft = PointD(22.0, 20.0)))
            assertNull(editor.captureSettings(controls, moved).previewGeometry)
            val latest = editor.requestCorrection(controls, moved)!!
            assertFalse(editor.correction.isCurrent(old))
            assertFalse(editor.correction.accept(old, listOf(geometry())))
            editor.correction.accept(latest, emptyList())
            assertNull(editor.sample(controls, moved)!!.geometry.warp)
            assertEquals(moved.quad, editor.captureSettings(controls, moved).previewGeometry!!.imageQuad)
            val version = controls.copy(version = 3)
            assertNotNull(editor.requestCorrection(version, moved))
            assertNull(editor.captureSettings(version, moved).previewGeometry)
            editor.release()
            assertFalse(editor.correction.isCurrent(latest))
        } finally { source.bitmap.recycle() }
    }

    @Test fun restoredSelectionSurvivesCandidateReorderingAndAutomaticThresholdOutputsDoNotRefit() {
        val source = image()
        try {
            val automatic = controls.copy(automaticThresholds = true)
            val initial = settings().copy(automaticThresholds = true, previewGeometry = geometry())
            val editor = editor(source, initial)
            val request = editor.requestCorrection(automatic, grid)!!
            assertEquals(geometry(), editor.sample(automatic, grid)!!.geometry)
            editor.correction.accept(request, listOf(geometry(2.0), geometry()))
            assertEquals(geometry(), editor.correction.selected)
            assertEquals(1, editor.correction.selectedIndex)
            assertNull(editor.requestCorrection(automatic.copy(blackProgress = 180, whiteProgress = 181), grid))
            assertTrue(editor.correctionReady(automatic, grid))
            assertNotNull(editor.requestCorrection(automatic.copy(polarity = PolarityMode.INVERTED), grid))
            assertNull(editor.captureSettings(automatic.copy(polarity = PolarityMode.INVERTED), grid).previewGeometry)
        } finally { source.bitmap.recycle() }
    }

    @Test fun disabledOrUnassignedCorrectionDoesNotScheduleWorkAndImageReplacementCancelsIt() {
        val source = image()
        val replacement = image()
        try {
            assertNull(editor(source, settings().copy(distortionCorrection = false)).requestCorrection(controls, grid))
            val editor = editor(source)
            assertNull(editor.requestCorrection(controls, grid.copy(logicalGridAssigned = false)))
            val request = editor.requestCorrection(controls, grid)!!
            editor.replaceWorking(replacement)
            assertFalse(editor.correction.accept(request, listOf(geometry())))
            assertNotNull(editor.requestCorrection(controls, grid))
        } finally { source.bitmap.recycle(); replacement.bitmap.recycle() }
    }
}
