package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
class PreprocessDialogControllerTest {
    private class Host : PreprocessDialogHost {
        val saves = mutableListOf<PreprocessSave>()
        val effects = mutableListOf<String>()
        var diagnostic: Bitmap? = null
        override fun retains(image: BitmapUtils.Prepared) =
            saves.lastOrNull()?.image === image || diagnostic === image.bitmap
        override fun save(draft: PreprocessSave) { saves += draft; effects += "save" }
        override fun showSavedStatus() { effects += "saved status" }
        override fun startRecovery() { effects += "repair" }
        override fun saveDiagnosticInput(bitmap: Bitmap, owned: Boolean) { diagnostic = bitmap }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun click(dialog: AlertDialog, label: String) {
        views(dialog.window!!.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == label }.performClick()
    }

    private fun automaticThresholdControl(dialog: AlertDialog): CheckBox =
        views(dialog.window!!.decorView).filterIsInstance<CheckBox>()
            .single { it.text.toString() == "閾値を自動設定" }

    private fun withDialog(settings: PreprocessSettings? = null, style: AppStyle = AppStyle.CLASSIC, block: (AlertDialog, Host, BitmapUtils.Prepared, BitmapUtils.Prepared) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.get().setTheme(style.theme)
        val source = BitmapUtils.prepare(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        })
        val working = BitmapUtils.rotate(source.bitmap, 15f)
        val host = Host()
        PreprocessDialogController(activity.get(), settings ?: PreprocessSettings(version = 1, rotationDegrees = 15f,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0),
                PointD(220.0, 220.0), PointD(20.0, 220.0))),
            source, working, null, Executor { it.run() }, AtomicInteger(), RecoveryEngine(), host).show()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try { block(dialog, host, source, working) } finally {
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            activity.pause().stop().destroy()
            if (!working.bitmap.isRecycled) working.bitmap.recycle()
            if (!source.bitmap.isRecycled) source.bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun systemBackConfirmsBeforeDiscardingUnsavedChangesInBothStyles() {
        for (style in AppStyle.entries) withDialog(style = style) { dialog, host, _, _ ->
            automaticThresholdControl(dialog).performClick()
            dialog.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()
            val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotSame(dialog, confirmation)
            assertTrue(dialog.isShowing)
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialog.isShowing)
            assertTrue(host.saves.isEmpty())
            dialog.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertTrue(host.saves.isEmpty())
        }
    }

    @Test fun dialogCancellationAlsoProtectsUnsavedChanges() = withDialog { dialog, host, _, _ ->
        automaticThresholdControl(dialog).performClick()
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing)
        assertNotSame(dialog, ShadowDialog.getLatestDialog())
        assertTrue(host.saves.isEmpty())
        (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
    }

    @Suppress("DEPRECATION")
    @Test fun systemBackClosesSavedWorkWithoutAnExtraConfirmation() = withDialog { dialog, host, _, _ ->
        click(dialog, "保存")
        dialog.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, host.saves.size)
    }

    @Test fun eitherSliderUnchecksAutomaticThresholdsUntilTheyAreEnabledAgain() {
        val settings = PreprocessSettings(automaticThresholds = true,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        for (sliderIndex in 0..1) withDialog(settings) { dialog, host, _, _ ->
            val automaticControl = automaticThresholdControl(dialog)
            assertTrue(automaticControl.isChecked)
            val slider = views(dialog.window!!.decorView).filterIsInstance<android.widget.SeekBar>()
                .filter { it.max == 254 }[sliderIndex]
            val before = slider.progress
            slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            assertTrue(slider.progress > before)
            assertFalse(automaticControl.isChecked)
            click(dialog, "保存")
            val manual = host.saves.last().settings
            assertFalse(manual.automaticThresholds)
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            val quad = overlay.imageQuad()
            overlay.setImageQuad(quad.copy(topLeft = PointD(quad.topLeft.x + 1, quad.topLeft.y)))
            overlay.onGeometryEditFinished!!.invoke()
            click(dialog, "保存")
            assertEquals(manual.blackMax, host.saves.last().settings.blackMax)
            assertEquals(manual.whiteMin, host.saves.last().settings.whiteMin)
            assertFalse(host.saves.last().settings.automaticThresholds)
            automaticControl.performClick()
            assertTrue(automaticControl.isChecked)
            click(dialog, "保存")
            val automatic = host.saves.last().settings
            assertTrue(automatic.automaticThresholds)
            assertEquals(settings.provisionalBlackMax, automatic.blackMax)
            assertEquals(settings.provisionalWhiteMin, automatic.whiteMin)
        }
    }

    @Test fun uncheckingAutomaticThresholdsKeepsTheCurrentPairAcrossCornerChanges() {
        val settings = PreprocessSettings(automaticThresholds = true,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        withDialog(settings) { dialog, host, _, _ ->
            click(dialog, "保存")
            val before = host.saves.last().settings
            val automaticControl = automaticThresholdControl(dialog)
            automaticControl.performClick()
            assertFalse(automaticControl.isChecked)
            click(dialog, "保存")
            assertEquals(before.copy(automaticThresholds = false), host.saves.last().settings)
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            val quad = overlay.imageQuad()
            overlay.setImageQuad(quad.copy(topLeft = PointD(quad.topLeft.x + 1, quad.topLeft.y)))
            overlay.onGeometryEditFinished!!.invoke()
            click(dialog, "保存")
            val after = host.saves.last().settings
            assertFalse(after.automaticThresholds)
            assertEquals(before.blackMax, after.blackMax)
            assertEquals(before.whiteMin, after.whiteMin)
        }
    }

    @Test fun reopeningSettingsRestoresTheSavedAutomaticThresholdChoiceAndPair() {
        for (enabled in listOf(false, true)) {
            val initial = PreprocessSettings(automaticThresholds = enabled, blackMax = 72, whiteMin = 181,
                provisionalBlackMax = 96, provisionalWhiteMin = 160,
                manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
            var saved: PreprocessSettings? = null
            withDialog(initial) { dialog, host, _, _ ->
                assertEquals(enabled, automaticThresholdControl(dialog).isChecked)
                click(dialog, "保存")
                val captured = host.saves.last().settings
                saved = captured
                assertEquals(enabled, captured.automaticThresholds)
                assertEquals(if (enabled) 96 else 72, captured.blackMax)
                assertEquals(if (enabled) 160 else 181, captured.whiteMin)
            }
            withDialog(saved) { dialog, host, _, _ ->
                assertEquals(enabled, automaticThresholdControl(dialog).isChecked)
                click(dialog, "保存")
                assertEquals(saved, host.saves.last().settings)
            }
        }
    }

    @Test fun confirmedInvertedFinderControlsThresholdLabelsAndSavedManualValues() {
        val settings = PreprocessSettings(polarity = PolarityMode.AUTO, blackMax = 80, whiteMin = 180,
            manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(50.0, 50.0)),
            confirmedFinderPolarity = FinderPolarity.INVERTED,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        withDialog(settings) { dialog, host, _, _ ->
            val labels = views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.contains("画像の明部（QRの黒(1)）: 175以上"))
            assertTrue(labels.contains("画像の暗部（QRの白(0)）: 75以下"))
            click(dialog, "保存")
            val saved = host.saves.last().settings
            assertEquals(80, saved.blackMax)
            assertEquals(180, saved.whiteMin)
            assertEquals(FinderPolarity.INVERTED, saved.confirmedFinderPolarity)
        }
    }

    @Test fun unassignedInvertedGridUsesSourceThresholdsInLogicalPolarity() {
        val settings = PreprocessSettings(polarity = PolarityMode.INVERTED, automaticThresholds = true,
            provisionalBlackMax = 40, provisionalWhiteMin = 80, logicalGridAssigned = false,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        withDialog(settings) { dialog, host, _, _ ->
            click(dialog, "保存")
            val saved = host.saves.last().settings
            assertEquals(175, saved.blackMax)
            assertEquals(215, saved.whiteMin)
            assertEquals(40, saved.provisionalBlackMax)
            assertEquals(80, saved.provisionalWhiteMin)
        }
    }

    @Test fun manualInvertedPreviewAgreesWithRecoverySamplingWithoutConfirmedFinders() {
        val settings = PreprocessSettings(polarity = PolarityMode.INVERTED,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        withDialog(settings) { dialog, _, _, working ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            val matrix = PreprocessOverlayView::class.java.getDeclaredField("editMatrix").run {
                isAccessible = true; get(overlay) as QrMatrix
            }
            val q = overlay.imageQuad()
            val geometry = GridGeometry(1, FinderRole.TOP_LEFT, q.topLeft.x, q.topLeft.y, 10.0, 0.0,
                q, GeometryModel.MANUAL_PROJECTIVE).reanchored()
            val expected = MatrixSampler(working.luma, settings.variants().single()).sample(geometry).first
            for (y in 0 until 21) for (x in 0 until 21) assertEquals(expected[x, y].value, matrix[x, y].value)
        }
    }

    @Test fun dismissDiscardsTemporaryImageWithoutSaving() = withDialog { dialog, host, source, working ->
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(host.saves.isEmpty())
        assertTrue(working.bitmap.isRecycled)
        assertFalse(source.bitmap.isRecycled)
    }

    @Test fun saveHandsOffImageAndCopiesEditsBeforeFurtherChanges() = withDialog { dialog, host, _, working ->
        val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
        // Explicitly edit the value; later draft edits must not mutate the saved snapshot.
        val coord = Coord(10, 10)
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ERASED)
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ONE)
        click(dialog, "保存")
        val saved = host.saves.single()
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ZERO)
        assertEquals(ModuleValue.ONE, saved.settings.preRecoveryEdits[coord])
        assertEquals(ModuleValue.ONE, saved.cellEdits.edits[coord])
        assertTrue(saved.events.isNotEmpty())
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(working.bitmap.isRecycled)
        assertEquals(listOf("save", "saved status"), host.effects)
    }

    @Test fun repairStartsAfterSavingAndRetainsTheImage() = withDialog { dialog, host, _, working ->
        click(dialog, "設定を保存して修復")
        assertEquals(listOf("save", "repair"), host.effects)
        assertFalse(dialog.isShowing)
        assertFalse(working.bitmap.isRecycled)
    }

    @Test fun dismissKeepsImageOwnedByDiagnosticExport() = withDialog { dialog, host, _, working ->
        host.diagnostic = working.bitmap
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(working.bitmap.isRecycled)
        assertTrue(host.saves.isEmpty())
    }
    @Test fun assignedFindersCanBeReleasedAndStayReleasedAfterSaving() = checkFinderRelease(FinderRole.entries.toSet())

    @Test fun twoAssignedFindersCanBeReleasedAndStayReleasedAfterSaving() =
        checkFinderRelease(setOf(FinderRole.TOP_RIGHT, FinderRole.BOTTOM_LEFT))

    private fun checkFinderRelease(roles: Set<FinderRole>) {
        val initial = PreprocessSettings(version = 1, rotationDegrees = 15f,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)),
            logicalGridAssigned = true,
            manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(50.0, 50.0),
                FinderRole.TOP_RIGHT to PointD(190.0, 50.0), FinderRole.BOTTOM_LEFT to PointD(50.0, 190.0)).filterKeys { it in roles })
        var saved: PreprocessSettings? = null
        withDialog(initial) { dialog, host, _, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertEquals(initial.manualFinders, overlay.finderPoints())
            assertTrue(overlay.isLogicalGridAssigned())
            val coord = Coord(10, 10)
            overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ERASED)
            overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ONE)
            for (role in roles) {
                assertTrue(overlay.beginFinderRemoval())
                assertTrue(overlay.removeConfirmedFinder(role))
                overlay.onFinderRemoved!!.invoke(role)
            }
            assertFalse(overlay.isLogicalGridAssigned())
            click(dialog, "保存")
            val settingsAfterRemoval = host.saves.single().settings
            saved = settingsAfterRemoval
            assertTrue(settingsAfterRemoval.manualFinders.isEmpty())
            assertFalse(settingsAfterRemoval.logicalGridAssigned)
            assertEquals(ModuleValue.ONE, settingsAfterRemoval.preRecoveryEdits[coord])
        }
        withDialog(saved) { dialog, _, _, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertTrue(overlay.finderPoints().isEmpty())
            assertFalse(overlay.isLogicalGridAssigned())
            overlay.setFinderPoint(FinderRole.BOTTOM_LEFT, overlay.imageQuad().topLeft)
            assertTrue(overlay.isLogicalGridAssigned())
            assertEquals(setOf(FinderRole.BOTTOM_LEFT), overlay.finderPoints().keys)
        }
    }

}
