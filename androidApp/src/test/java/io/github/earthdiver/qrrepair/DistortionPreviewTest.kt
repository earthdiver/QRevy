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
    private fun settings() = PreprocessSettings(lmCorrectionEnabled = false, version = 4, manualQuad = quad, polarity = PolarityMode.NORMAL, distortionCorrection = true)
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

    private fun sampleReady(editor: PreprocessEditorModel, controls: PreprocessControlSnapshot,
        grid: PreprocessGeometrySnapshot): PreprocessEditorModel.Preview {
        editor.requestObservations(controls, grid)?.let { request ->
            assertTrue(editor.acceptObservations(request, runCatching { editor.calculateObservations(request) }))
        }
        return requireNotNull(editor.sample(controls, grid))
    }

    @Test fun rankingCountsOnlyImageUnknownsInCodewordsTimingAndFormat() {
        val matrix = QrMatrix(45)
        for (y in 0 until 45) for (x in 0 until 45) matrix.set(x, y, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        val data = QrCellRegions(7).codewordCoordinates.toList()
        for (p in listOf(data[0], Coord(8, 6), Coord(6, 8), Coord(8, 0),
            Coord(0, 0), Coord(7, 0), Coord(22, 6), Coord(34, 0))) {
            matrix.set(p.x, p.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        matrix.set(data[1].x, data[1].y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        assertEquals(4, DistortionPreviewState().rankingUnknownCount(matrix, 7))
    }

    @Test fun enablingLmKeepsTheSelectedCurveAndRejectsOldSelectionResults() {
        val source = image()
        try {
            val first = editor(source)
            first.correction.accept(first.requestCorrection(controls, grid)!!, listOf(geometry(2.0), geometry()))
            first.correction.select(1)
            first.cells.applyValues(listOf(Coord(18, 18)), ModuleValue.ONE) { ModuleValue.ZERO }
            first.selectLm(true)
            assertTrue(first.usesCurveSeed)
            assertNull(first.requestLm(controls, grid))
            assertNull(first.requestCorrection(controls, grid))
            assertFalse(first.correctionReady(controls, grid))
            val pending = first.correction.requestSelectedRefinement()!!
            assertEquals(geometry(), pending.geometry)
            first.correction.select(0)
            val next = first.correction.requestSelectedRefinement()!!
            assertEquals(geometry(2.0), next.geometry)
            assertFalse(first.correction.acceptRefinement(pending, Result.success(DistortionPreviewState.RefinementCalculation(geometry(9.0), listOf(0, 1)))))
            assertTrue(first.correction.acceptRefinement(next, Result.failure(IllegalStateException("fit failed"))))
            assertEquals(geometry(2.0), first.correction.selected)
            assertTrue(first.correctionReady(controls, grid))
            assertNull(first.correction.requestSelectedRefinement())
            val saved = first.commit(first.captureSettings(controls, grid))
            assertNull(saved.settings.lmGeometry)
            val restored = editor(source, saved.settings)
            restored.restoreCorrectionPreview(saved.correctionPreview)
            assertNull(restored.requestLm(controls, grid))
            assertNull(restored.requestCorrection(controls, grid))
            assertTrue(restored.correctionReady(controls, grid))
            assertNull(restored.correction.requestSelectedRefinement())
            assertEquals(ModuleValue.ONE, restored.cells.edits[Coord(18, 18)])
            restored.correction.select(1)
            val stale = restored.correction.requestSelectedRefinement()!!
            restored.selectLm(false)
            assertFalse(restored.correction.acceptRefinement(stale, Result.success(DistortionPreviewState.RefinementCalculation(geometry(9.0), listOf(0, 1)))))
        } finally { source.bitmap.recycle() }
    }

    @Test fun improvedCandidateMovesAheadWithoutLosingSelectionOrCompletedAttempts() {
        val source = image()
        try {
            val state = DistortionPreviewState()
            val input = DistortionPreviewState.Input(source.luma, geometry(), SamplingProfile(settings(), false), true)
            state.accept(state.request(input)!!, listOf(geometry(2.0), geometry(3.0), geometry()))
            state.select(2)
            val request = state.requestSelectedRefinement()!!
            val improved = geometry(4.0)
            assertTrue(state.acceptRefinement(request, Result.success(
                DistortionPreviewState.RefinementCalculation(improved, listOf(2, 0, 1)))))
            assertEquals(listOf(improved, geometry(2.0), geometry(3.0)), state.candidates)
            assertEquals(0, state.selectedIndex)
            assertEquals(improved, state.selected)
            assertNull(state.requestSelectedRefinement())
            state.select(1)
            assertNull(state.requestSelectedRefinement())
            assertEquals(setOf(0, 1), state.snapshot()!!.refinedIndices)
            val restored = DistortionPreviewState()
            assertTrue(restored.restoreCompleted(restored.request(input)!!, state.snapshot()!!))
            assertEquals(state.candidates, restored.candidates)
            assertEquals(state.selected, restored.selected)
            assertNull(restored.requestSelectedRefinement())
            restored.select(2)
            assertEquals(geometry(3.0), restored.requestSelectedRefinement()!!.geometry)
        } finally { source.bitmap.recycle() }
    }

    @Test fun refinementWorkerSortsByImageUnknownCountAndKeepsTiesStable() {
        val source = image()
        try {
            val state = DistortionPreviewState()
            val profile = SamplingProfile(settings(), false)
            val input = DistortionPreviewState.Input(source.luma, geometry(), profile, true)
            val candidates = listOf(geometry(15.0), geometry(0.0), geometry(-15.0), geometry(0.0).copy(angleRad = .01))
            state.accept(state.request(input)!!, candidates)
            state.select(2)
            val request = state.requestSelectedRefinement()!!
            val result = state.calculateRefinement(request)
            val replaced = candidates.toMutableList().also { it[2] = result.geometry }
            val counts = replaced.map { geometryRankingUnknownCount(source.luma, it, profile) { false } }
            assertTrue("Fixture must have different unknown counts", counts.distinct().size > 1)
            assertEquals(counts[1], counts[3])
            assertTrue(state.acceptRefinement(request, Result.success(result)))
            assertEquals(counts.sorted(), state.candidates.map {
                geometryRankingUnknownCount(source.luma, it, profile) { false }
            })
            assertTrue(state.candidates.indexOf(candidates[1]) < state.candidates.indexOf(candidates[3]))
            assertEquals(result.geometry, state.selected)
            assertNull(state.requestSelectedRefinement())
        } finally { source.bitmap.recycle() }
    }

    @Test fun completedCandidatesAndSelectionSurviveAClosedEditorWithoutImageCaches() {
        val source = image()
        try {
            for (lmEnabled in listOf(false, true)) {
                val lm = geometry().copy(model = GeometryModel.PROJECTIVE, warp = null).reanchored()
                val first = editor(source, settings().copy(lmCorrectionEnabled = lmEnabled,
                    lmGeometry = lm.takeIf { lmEnabled }))
                assertNull(first.requestLm(controls, grid))
                val request = first.requestCorrection(controls, grid)!!
                assertEquals(lmEnabled, request.input.jointRefinement)
                first.correction.accept(request, listOf(geometry(2.0), geometry()), memoryLimited = true)
                first.correction.select(1)
                first.correction.requestSelectedRefinement()?.let {
                    first.correction.acceptRefinement(it, Result.success(DistortionPreviewState.RefinementCalculation(it.geometry, it.candidates.indices.toList())))
                }
                first.cells.applyValues(listOf(Coord(18, 18)), ModuleValue.ZERO) { ModuleValue.ONE }
                val saved = first.commit(first.captureSettings(controls, grid))
                assertNotNull(saved.correctionPreview)
                first.release()
                val next = editor(source, saved.settings)
                next.restoreCorrectionPreview(saved.correctionPreview)
                assertNull(next.requestLm(controls, grid))
                assertNull(next.requestCorrection(controls, grid))
                assertTrue(next.correctionReady(controls, grid))
                assertEquals(listOf(geometry(2.0), geometry()), next.correction.candidates)
                assertEquals(1, next.correction.selectedIndex)
                assertTrue(next.correction.memoryLimited)
                assertEquals(ModuleValue.ZERO, next.cells.edits[Coord(18, 18)])
                // Cell sampling is separate; only the compact geometry result survives.
                assertNotNull(next.requestObservations(controls, grid))
                next.correction.select(0)
                assertNull(next.requestCorrection(controls, grid))
                assertEquals(geometry(2.0), next.captureSettings(controls, grid).previewGeometry)
            }
        } finally { source.bitmap.recycle() }
    }

    @Test fun changedImageGeometryPolarityOrCorrectionModeRecomputesCandidates() {
        val source = image()
        try {
            val first = editor(source)
            first.correction.accept(first.requestCorrection(controls, grid)!!, listOf(geometry()))
            val saved = first.commit(first.captureSettings(controls, grid))
            for (changed in listOf(
                saved.settings.copy(rotationDegrees = 1f),
                saved.settings.copy(manualQuad = quad.copy(topLeft = PointD(21.0, 20.0))),
                saved.settings.copy(imageAdjustment = ImageAdjustment(brightness = 1)),
                saved.settings.copy(perspectiveCorrectionQuad = quad),
                saved.settings.copy(previewGeometry = geometry(2.0)),
            )) {
                val next = editor(source, changed)
                next.restoreCorrectionPreview(saved.correctionPreview)
                assertNotNull(next.requestCorrection(controls, grid))
            }
            val moved = editor(source, saved.settings)
            moved.restoreCorrectionPreview(saved.correctionPreview)
            assertNotNull(moved.requestCorrection(controls, grid.copy(quad = quad.copy(topLeft = PointD(21.0, 20.0)))))
            val inverted = editor(source, saved.settings)
            inverted.restoreCorrectionPreview(saved.correctionPreview)
            assertNotNull(inverted.requestCorrection(controls, grid.copy(confirmedFinderPolarity = FinderPolarity.INVERTED)))
            val toggled = editor(source, saved.settings)
            toggled.restoreCorrectionPreview(saved.correctionPreview)
            toggled.selectDistortion(false)
            toggled.selectDistortion(true)
            assertNotNull(toggled.requestCorrection(controls, grid))
            val lm = editor(source, saved.settings)
            lm.restoreCorrectionPreview(saved.correctionPreview)
            lm.selectLm(true)
            assertNotNull(lm.requestCorrection(controls, grid))
        } finally { source.bitmap.recycle() }
    }

    @Test fun displayedCellsAndSavedRepairInputUseTheSelectedCorrectionWithoutLosingFixedEdits() {
        val source = image()
        try {
            val editor = editor(source)
            val fixed = Coord(18, 18)
            editor.cells.applyValues(listOf(fixed), ModuleValue.ZERO) { ModuleValue.ONE }
            assertEquals(ModuleValue.ZERO, sampleReady(editor, controls, grid).matrix[16, 16].value)
            val request = editor.requestCorrection(controls, grid)!!
            assertFalse(editor.correctionReady(controls, grid))
            editor.correction.accept(request, listOf(geometry(2.0), geometry()))
            editor.correction.select(1)
            val preview = sampleReady(editor, controls, grid)
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
            assertEquals(savedGrid, sampleReady(editor, manual, grid).geometry)

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
                val preview = sampleReady(editor, thresholds, grid)
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
            assertNull(sampleReady(editor, controls, moved).geometry.warp)
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
            assertEquals(geometry(), sampleReady(editor, automatic, grid).geometry)
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

    @Test fun automaticInvertedRankingSeparatesTheCorrectTimingPhaseFromAShiftedGrid() {
        val source = BitmapUtils.prepare(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(300 * 300) { index ->
                val x = index % 300; val y = index / 300
                val value = if (((x - 16) / 8 + (y - 16) / 8) % 2 == 0) 120 else 20
                Color.rgb(value, value, value)
            }, 0, 300, 0, 0, 300, 300)
        })
        try {
            val editor = editor(source, settings().copy(provisionalBlackMax = 70, provisionalWhiteMin = 71))
            val input = editor.requestCorrection(controls.copy(polarity = PolarityMode.INVERTED, automaticThresholds = true),
                grid.copy(confirmedFinderPolarity = FinderPolarity.INVERTED))!!.input
            assertEquals(184, input.profile.settings.blackMax)
            assertEquals(185, input.profile.settings.whiteMin)
            assertEquals(0.0, quickGeometryScore(input.image, input.base, input.profile), 1e-6)
            assertEquals(24.0, quickGeometryScore(input.image, input.base.adjusted(dxPixels = 8.0), input.profile), 1e-6)
        } finally { source.bitmap.recycle() }
    }

    @Test fun curvedCellsWaitForWorkerObservationsAndUseCurrentThresholdsAndEdits() {
        val source = image(Color.rgb(128, 128, 128))
        try {
            val editor = editor(source, settings().copy(previewGeometry = geometry()))
            editor.requestCorrection(controls, grid)
            assertNull(editor.sample(controls, grid))
            assertFalse(editor.samplingReady(controls, grid))
            val request = editor.requestObservations(controls, grid)!!
            val thresholds = controls.copy(blackProgress = 145)
            assertNull(editor.requestObservations(thresholds, grid))
            val fixed = Coord(24, 16)
            editor.cells.applyValues(listOf(fixed), ModuleValue.ZERO) { ModuleValue.ONE }
            assertTrue(editor.acceptObservations(request, runCatching { editor.calculateObservations(request) }))
            assertTrue(editor.samplingReady(thresholds, grid))
            val preview = editor.sample(thresholds, grid)!!
            assertEquals(ModuleValue.ONE, preview.matrix[fixed.x, fixed.y].value)
            editor.cells.applyTo(preview.matrix, false)
            assertEquals(ModuleValue.ZERO, preview.matrix[fixed.x, fixed.y].value)
            assertEquals(ModuleSource.USER, preview.matrix[fixed.x, fixed.y].source)
            assertEquals(ModuleValue.ONE, editor.sample(thresholds, grid)!!.matrix[fixed.x, fixed.y].value)
        } finally { source.bitmap.recycle() }
    }

    @Test fun obsoleteCurvedObservationWorkIsCancelledAndCannotReplaceANewerSelection() {
        val source = image()
        try {
            val editor = editor(source)
            val correction = editor.requestCorrection(controls, grid)!!
            editor.correction.accept(correction, listOf(geometry(2.0), geometry(7.0)))
            val first = editor.requestObservations(controls, grid)!!
            editor.correction.select(1)
            val latest = editor.requestObservations(controls, grid)!!
            assertTrue(runCatching { editor.calculateObservations(first) }.exceptionOrNull() is RecoveryCancelledException)
            assertFalse(editor.acceptObservations(first, Result.success(arrayOfNulls(33 * 33))))
            assertNull(editor.sample(controls, grid))
            assertTrue(editor.acceptObservations(latest, runCatching { editor.calculateObservations(latest) }))
            assertEquals(geometry(7.0), editor.sample(controls, grid)!!.geometry)
            editor.correction.select(0)
            val closing = editor.requestObservations(controls, grid)!!
            editor.release()
            assertTrue(runCatching { editor.calculateObservations(closing) }.exceptionOrNull() is RecoveryCancelledException)
            assertFalse(editor.acceptObservations(closing, Result.success(arrayOfNulls(33 * 33))))
        } finally { source.bitmap.recycle() }
    }
}
