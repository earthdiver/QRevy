package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.ToggleButton
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port-mdpi")
class SavedPreRecoveryDataTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun call(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun set(activity: MainActivity, name: String, value: Any) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)

    private fun dataText(view: View) = views(view).filterIsInstance<TextView>()
        .first { it.text.contains("\n? = ") }.text.toString()

    private fun observedMatrix(damagedFormat: Boolean = false): QrMatrix =
        postTerminatorMatrix(PayloadBits().apply { byteSegment("A") }.finish(16), ecc = ErrorCorrectionLevel.M).also { matrix ->
            if (damagedFormat) FormatSolver.copies(21).flatten().forEach { (x, y) ->
                matrix.set(x, y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }

    private fun withActivity(matrix: QrMatrix, constraint: QrFormatConstraint? = null,
                             style: AppStyle = AppStyle.CLASSIC, block: (MainActivity) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        AppLanguageManager.save(app, AppLanguage.ENGLISH)
        AppStylePreferences.save(app, style)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val bitmap = BitmapUtils.renderTextMatrixSource(matrix)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
            set(controller.get(), "prepared", BitmapUtils.prepare(bitmap))
            set(controller.get(), "preprocessSettings", PreprocessSettings(formatConstraint = constraint,
                manualQuad = ImageQuad(PointD(35.5, 35.5), PointD(195.5, 35.5), PointD(195.5, 195.5), PointD(35.5, 195.5))))
            block(controller.get())
        } finally {
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
            controller.pause().stop().destroy()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test fun savingKeepsTheEditorsThresholdPolicyAndManualValues() {
        for (style in AppStyle.entries) for (assigned in listOf(false, true)) for (automatic in listOf(false, true)) {
            withActivity(observedMatrix(), style = style) { activity ->
                val bitmap = BitmapUtils.renderTextMatrixSource(observedMatrix())
                for (y in 112 until 120) for (x in 112 until 120)
                    bitmap.setPixel(x, y, android.graphics.Color.rgb(150, 150, 150))
                set(activity, "prepared", BitmapUtils.prepare(bitmap))
                val settings = PreprocessSettings(polarity = PolarityMode.NORMAL, blackMax = 96, whiteMin = 160,
                    automaticThresholds = automatic, logicalGridAssigned = assigned,
                    manualQuad = ImageQuad(PointD(35.5, 35.5), PointD(195.5, 35.5), PointD(195.5, 195.5), PointD(35.5, 195.5)),
                    preRecoveryEdits = mapOf(Coord(11, 11) to ModuleValue.ONE))
                set(activity, "preprocessSettings", settings)
                call(activity, "showPreprocessDialog")
                shadowOf(Looper.getMainLooper()).idle()
                val editor = ShadowDialog.getLatestDialog() as AlertDialog
                val overlay = views(editor.window!!.decorView).filterIsInstance<io.github.earthdiver.qrrepair.ui.PreprocessOverlayView>().single()
                val preview = overlay.javaClass.getDeclaredField("editMatrix").apply { isAccessible = true }.get(overlay) as QrMatrix
                val expected = preview[10, 10].value
                if (!assigned && automatic) assertEquals(ModuleValue.ERASED, expected)
                views(editor.window!!.decorView).filterIsInstance<Button>().first { it.text == "Save" }.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val data = call(activity, "savedPreRecoveryData") as PreRecoveryDataInput
                assertEquals("$style assigned=$assigned automatic=$automatic", expected, data.matrix[10, 10].value)
                assertEquals(ModuleValue.ONE, data.matrix[11, 11].value)
                assertEquals(ModuleSource.USER, data.matrix[11, 11].source)
                editor.dismiss()
                bitmap.recycle()
            }
        }
    }

    @Test fun formatChosenAndSavedInTheEditorIsUsedByMainData() {
        for (style in AppStyle.entries) withActivity(observedMatrix(), style = style) { activity ->
            call(activity, "showPreprocessDialog")
            shadowOf(Looper.getMainLooper()).idle()
            val editor = ShadowDialog.getLatestDialog() as AlertDialog
            fun choose(prefix: String, index: Int) {
                views(editor.window!!.decorView).filterIsInstance<Button>()
                    .first { it.text.startsWith(prefix, ignoreCase = true) }.performClick()
                (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, index, index.toLong())
                shadowOf(Looper.getMainLooper()).idle()
            }
            choose("ECC:", 4)
            choose("Mask:", 8)
            views(editor.window!!.decorView).filterIsInstance<Button>().first { it.text == "Data" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val editorText = dataText(editor.window!!.decorView)
            assertTrue(editorText.contains("manual format: ECC=H, Mask=7"))
            views(editor.window!!.decorView).filterIsInstance<Button>().first { it.text == "Save" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            editor.dismiss()
            call(activity, "showCodewordHex")
            val main = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dataText(main.window!!.decorView).endsWith(editorText))
        }
    }

    @Test fun automaticAndPartialSelectionsUseTheSameTrustedFormatInBothViews() {
        val constraints = listOf(null, QrFormatConstraint(ErrorCorrectionLevel.H, 7),
            QrFormatConstraint(ecLevel = ErrorCorrectionLevel.H), QrFormatConstraint(dataMask = 7),
            QrFormatConstraint(ecLevel = ErrorCorrectionLevel.M), QrFormatConstraint(dataMask = 0))
        for (damaged in listOf(false, true)) for (constraint in constraints) {
            val matrix = observedMatrix(damaged)
            withActivity(matrix, constraint) { activity ->
                val page = PreRecoveryDataPage(activity, { PreRecoveryDataInput(matrix, 1) }, { constraint })
                page.refresh()
                call(activity, "showCodewordHex")
                val main = ShadowDialog.getLatestDialog() as AlertDialog
                for ((unmasked, partial) in listOf(true to false, false to false, true to true)) {
                    for (view in listOf(page.view, main.window!!.decorView)) {
                        val toggles = views(view).filterIsInstance<ToggleButton>()
                        toggles.single { it.textOff == activity.getString(R.string.mask_before) }.isChecked = unmasked
                        toggles.single { it.textOff == activity.getString(R.string.raw_data_view) }.isChecked = partial
                    }
                    val expected = dataText(page.view)
                    val actual = dataText(main.window!!.decorView)
                    assertTrue("Damaged=$damaged, constraint=$constraint, unmasked=$unmasked, partial=$partial\n$actual",
                        actual.endsWith(expected))
                }
            }
        }
    }

    @Test fun recoveryCandidatesAndFailedAttemptsKeepTheirOwnFormat() {
        val matrix = observedMatrix()
        val candidate = RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, 0)).single()
        for (failed in listOf(false, true)) withActivity(matrix, QrFormatConstraint(ErrorCorrectionLevel.H, 7)) { activity ->
            val state = MainActivity::class.java.getDeclaredField("recoveryState").apply { isAccessible = true }.get(activity) as RecoveryUiState
            state.workingMatrix = matrix
            if (failed) state.failure = RecoveryFailure(ErrorCorrectionLevel.M, 0, 0, 0, 0, RecoveryFailureStage.PAYLOAD_VALIDATION)
            else state.selectedRecovery = candidate
            call(activity, "showCodewordHex")
            val main = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dataText(main.window!!.decorView).contains(" / ECC M / mask 0"))
        }
    }
}
