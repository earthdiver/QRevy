package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.ErrorLimitMode
import io.github.earthdiver.qrrepair.core.ErrorLimitPolicy
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
class BitErrorLimitDialogTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun input(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
    private fun step(dialog: AlertDialog, label: String) = views(dialog.window!!.decorView)
        .filterIsInstance<Button>().first { it.text.toString() == label }.performClick().also { shadowOf(Looper.getMainLooper()).idle() }
    private fun activity() = Robolectric.buildActivity(MainActivity::class.java).setup().get().also {
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun choice(dialog: AlertDialog, prefix: String) = views(dialog.window!!.decorView)
        .filterIsInstance<RadioButton>().single { it.text.startsWith(prefix) }
    private fun choose(dialog: AlertDialog, prefix: String) {
        choice(dialog, prefix).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun openEditor(activity: MainActivity, settings: PreprocessSettings): AlertDialog {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        MainActivity::class.java.getDeclaredField("prepared").apply { isAccessible = true }
            .set(activity, BitmapUtils.prepare(bitmap))
        MainActivity::class.java.getDeclaredField("preprocessSettings").apply { isAccessible = true }
            .set(activity, settings)
        MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply { isAccessible = true }.invoke(activity)
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun changeVersion(editor: AlertDialog, version: Int) {
        views(editor.window!!.decorView).filterIsInstance<Button>().single { it.text.startsWith("QRバージョン:") }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, version - 1, (version - 1).toLong())
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun limitButton(editor: AlertDialog, title: String): Button {
        val label = views(editor.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == title }
        return views(label.parent as View).filterIsInstance<Button>().single()
    }
    private fun savedSettings(activity: MainActivity) = MainActivity::class.java.getDeclaredField("preprocessSettings")
        .apply { isAccessible = true }.get(activity) as PreprocessSettings
    private fun saveEditor(editor: AlertDialog) {
        views(editor.window!!.decorView).filterIsInstance<Button>().first { it.text.toString() == "保存" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun automaticLimitFollowsVersionAndInactiveManualValueDoesNotBlockSave() {
        val activity = activity()
        val editor = openEditor(activity, PreprocessSettings(version = 10,
            dataEccMismatchLimit = 300, dataEccMismatchLimitMode = ErrorLimitMode.AUTOMATIC))
        val button = limitButton(editor, "データ・ECCの誤り上限")
        val originalLabel = button.text.toString()
        changeVersion(editor, 1)
        assertTrue(button.text.startsWith("初期設定"))
        assertNotEquals(originalLabel, button.text.toString())
        assertTrue(button.text.contains("${ErrorLimitPolicy.data(1, ErrorLimitMode.AUTOMATIC, null)}bit"))
        saveEditor(editor)
        val saved = savedSettings(activity)
        assertEquals(1, saved.version)
        assertEquals(ErrorLimitMode.AUTOMATIC, saved.dataEccMismatchLimitMode)
        assertEquals(300, saved.dataEccMismatchLimit)
    }

    @Test fun explicitManualLimitRemainsUnchangedAcrossVersions() {
        val activity = activity()
        val editor = openEditor(activity, PreprocessSettings(version = 1, dataEccMismatchLimit = 5))
        val button = limitButton(editor, "データ・ECCの誤り上限")
        assertEquals("手動（5bit）", button.text.toString())
        changeVersion(editor, 10)
        assertEquals("手動（5bit）", button.text.toString())
        saveEditor(editor)
        val saved = savedSettings(activity)
        assertEquals(10, saved.version)
        assertEquals(ErrorLimitMode.MANUAL, saved.dataEccMismatchLimitMode)
        assertEquals(5, saved.dataEccMismatchLimit)
    }

    @Test fun bothControllerLimitsUsePresetDialogAndSaveTheirModesSeparately() {
        val activity = activity()
        val editor = openEditor(activity, PreprocessSettings(version = 1,
            formatMismatchLimit = 2, formatMismatchLimitMode = ErrorLimitMode.AUTOMATIC,
            dataEccMismatchLimit = 300, dataEccMismatchLimitMode = ErrorLimitMode.AUTOMATIC))
        val format = limitButton(editor, "形式情報の誤り上限")
        format.performClick()
        val formatDialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(choice(formatDialog, "初期設定").isChecked)
        choose(formatDialog, "緩め")
        formatDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("緩め（${ErrorLimitPolicy.RELAXED_FORMAT}bit）", format.text.toString())
        assertEquals(ErrorLimitMode.AUTOMATIC, savedSettings(activity).formatMismatchLimitMode)
        val data = limitButton(editor, "データ・ECCの誤り上限")
        data.performClick()
        val dataDialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(choice(dataDialog, "初期設定").isChecked)
        choose(dataDialog, "厳しめ")
        dataDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("厳しめ（${ErrorLimitPolicy.strictData(1)}bit）", data.text.toString())
        saveEditor(editor)
        val saved = savedSettings(activity)
        assertEquals(ErrorLimitMode.RELAXED, saved.formatMismatchLimitMode)
        assertEquals(2, saved.formatMismatchLimit)
        assertEquals(ErrorLimitMode.STRICT, saved.dataEccMismatchLimitMode)
        assertEquals(300, saved.dataEccMismatchLimit)
    }

    @Test fun bothLimitTypesShowPresetValuesAndApplyOnlyTheSelectedDraft() {
        val activity = activity()
        for ((title, maximum) in listOf("形式情報の誤り上限" to 30, "データ・ECCの誤り上限" to 208)) {
            val applied = mutableListOf<Pair<ErrorLimitMode, Int?>>()
            val dialog = showBitErrorLimitDialog(activity, title,
                currentMode = ErrorLimitMode.AUTOMATIC, currentManualValue = 7,
                maximum = maximum, automaticLimit = 3, strictLimit = 1, relaxedLimit = 2,
            ) { mode, value -> applied += mode to value }
            assertEquals("初期設定（3bit）", choice(dialog, "初期設定").text.toString())
            assertTrue(choice(dialog, "初期設定").isChecked)
            assertEquals("厳しめ（1bit）", choice(dialog, "厳しめ").text.toString())
            assertEquals("緩め（2bit）", choice(dialog, "緩め").text.toString())
            choose(dialog, "厳しめ")
            assertEquals("1", input(dialog).text.toString())
            choose(dialog, "緩め")
            assertEquals("2", input(dialog).text.toString())
            assertTrue(applied.isEmpty())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(listOf(ErrorLimitMode.RELAXED to 7), applied)
            assertFalse(dialog.isShowing)
        }
    }

    @Test fun exactMatchAppliesZeroForBothLimitsAndRetainsTheManualValue() {
        val activity = activity()
        for (maximum in listOf(30, 208)) {
            var applied: Pair<ErrorLimitMode, Int?>? = null
            val dialog = showBitErrorLimitDialog(activity, "test",
                currentMode = ErrorLimitMode.AUTOMATIC, currentManualValue = 7,
                maximum = maximum, automaticLimit = 6, strictLimit = 6, relaxedLimit = 9,
            ) { mode, value -> applied = mode to value }
            assertEquals("完全一致（0bit）", choice(dialog, "完全一致").text.toString())
            choose(dialog, "完全一致")
            assertEquals("0", input(dialog).text.toString())
            assertNull(applied)
            choose(dialog, "手動")
            assertEquals("7", input(dialog).text.toString())
            choose(dialog, "完全一致")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(ErrorLimitMode.EXACT to 7, applied)
            val selected = requireNotNull(applied)
            val reopened = showBitErrorLimitDialog(activity, "test",
                currentMode = selected.first, currentManualValue = selected.second,
                maximum = maximum, automaticLimit = 6, strictLimit = 6, relaxedLimit = 9,
            ) { _, _ -> }
            assertTrue(choice(reopened, "完全一致").isChecked)
            assertEquals("0", input(reopened).text.toString())
            reopened.dismiss()
        }
    }

    @Test fun changingPresetThenCancelingPreservesTheCallerDraft() {
        val activity = activity()
        var calls = 0
        val dialog = showBitErrorLimitDialog(activity, "test",
            currentMode = ErrorLimitMode.STRICT, currentManualValue = 7,
            maximum = 208, automaticLimit = 3, strictLimit = 1, relaxedLimit = 2,
        ) { _, _ -> calls++ }
        choose(dialog, "緩め")
        input(dialog).setText("19")
        choose(dialog, "制限なし")
        assertTrue(choice(dialog, "制限なし").isChecked)
        assertEquals(0, calls)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, calls)
        assertFalse(dialog.isShowing)
    }

    @Test fun steppingAPresetUsesItsEffectiveValueAndSwitchesToManual() {
        val activity = activity()
        var applied: Pair<ErrorLimitMode, Int?>? = null
        val dialog = showBitErrorLimitDialog(activity, "test",
            currentMode = ErrorLimitMode.RELAXED, currentManualValue = 7,
            maximum = 30, automaticLimit = 3, strictLimit = 1, relaxedLimit = 2,
        ) { mode, value -> applied = mode to value }
        assertEquals("2", input(dialog).text.toString())
        step(dialog, "＋")
        assertEquals("3", input(dialog).text.toString())
        assertTrue(choice(dialog, "手動").isChecked)
        choose(dialog, "厳しめ")
        assertEquals("1", input(dialog).text.toString())
        choose(dialog, "手動")
        assertEquals("3", input(dialog).text.toString())
        input(dialog).setText("29")
        step(dialog, "＋")
        step(dialog, "＋")
        assertEquals("30", input(dialog).text.toString())
        input(dialog).setText("0")
        step(dialog, "−")
        assertEquals("0", input(dialog).text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(ErrorLimitMode.MANUAL to 0, applied)
    }

    @Test fun invalidManualValuesKeepTheDialogOpenButDoNotBlockExplicitPresets() {
        val activity = activity()
        val applied = mutableListOf<Pair<ErrorLimitMode, Int?>>()
        val dialog = showBitErrorLimitDialog(activity, "test",
            currentMode = ErrorLimitMode.MANUAL, currentManualValue = 300,
            maximum = 208, automaticLimit = 3, strictLimit = 1, relaxedLimit = 2,
        ) { mode, value -> applied += mode to value }
        assertEquals("300", input(dialog).text.toString())
        assertNotNull(input(dialog).error)
        for (invalid in listOf("", "-1", "209", "1.5", "999999999999999999999999")) {
            input(dialog).setText(invalid)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertNotNull(input(dialog).error)
            assertTrue(applied.isEmpty())
        }
        choose(dialog, "厳しめ")
        assertNull(input(dialog).error)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(listOf(ErrorLimitMode.STRICT to 300), applied)
    }

    @Test fun returningToDefaultRetainsItsModeEvenWhenItsValueMatchesAPreset() {
        val activity = activity()
        var applied: Pair<ErrorLimitMode, Int?>? = null
        val dialog = showBitErrorLimitDialog(activity, "test",
            currentMode = ErrorLimitMode.STRICT, currentManualValue = 7,
            maximum = 30, automaticLimit = 1, strictLimit = 1, relaxedLimit = 2,
        ) { mode, value -> applied = mode to value }
        choose(dialog, "初期設定")
        assertTrue(choice(dialog, "初期設定").isChecked)
        assertFalse(choice(dialog, "厳しめ").isChecked)
        assertEquals("1", input(dialog).text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(ErrorLimitMode.AUTOMATIC to 7, applied)
    }

    @Test fun legacyUnlimitedIsSelectedExplicitlyAndDefaultCanAlsoBeUnlimited() {
        val activity = activity()
        var applied: Pair<ErrorLimitMode, Int?>? = null
        fun open() = showBitErrorLimitDialog(activity, "test",
            currentMode = ErrorLimitMode.MANUAL, currentManualValue = null,
            maximum = 30, automaticLimit = null, strictLimit = 1, relaxedLimit = 3,
        ) { mode, value -> applied = mode to value }
        val dialog = open()
        assertTrue(choice(dialog, "制限なし").isChecked)
        assertFalse(choice(dialog, "手動").isChecked)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(ErrorLimitMode.UNLIMITED to null, applied)
        val next = open()
        assertEquals("初期設定（制限なし）", choice(next, "初期設定").text.toString())
        choose(next, "初期設定")
        next.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(ErrorLimitMode.AUTOMATIC to null, applied)
    }

    @Test fun unlimitedCanBeAppliedWithoutAcceptingAnInvalidManualNumber() {
        val activity = activity()
        val applied = mutableListOf<Pair<ErrorLimitMode, Int?>>()
        val dialog = showBitErrorLimitDialog(activity, "test",
            currentMode = ErrorLimitMode.MANUAL, currentManualValue = 29648,
            maximum = 208, automaticLimit = 3, strictLimit = 1, relaxedLimit = 2,
        ) { mode, value -> applied += mode to value }
        assertEquals("29648", input(dialog).text.toString())
        assertNotNull(input(dialog).error)
        choose(dialog, "制限なし")
        assertTrue(applied.isEmpty())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(listOf(ErrorLimitMode.UNLIMITED to 29648), applied)
        assertFalse(dialog.isShowing)
    }

    @Test fun decreasingVersionRequestsNewLimitWithoutClampingAndRechecksBeforeSave() {
        val activity = activity()
        fun set(name: String, value: Any) = MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(activity, value)
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        set("prepared", BitmapUtils.prepare(bitmap))
        set("preprocessSettings", PreprocessSettings(version = 2, dataEccMismatchLimit = 300))
        MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply { isAccessible = true }.invoke(activity)
        val editor = ShadowDialog.getLatestDialog() as AlertDialog
        val root = editor.window!!.decorView
        views(root).filterIsInstance<Button>().first { it.text.startsWith("QRバージョン:") }.performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, 0, 0).also { shadowOf(Looper.getMainLooper()).idle() }
        val limit = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("300", input(limit).text.toString())
        assertTrue(limit.findViewById<TextView>(android.R.id.message).text.contains("208"))
        input(limit).setText("209")
        limit.getButton(AlertDialog.BUTTON_POSITIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        assertTrue(limit.isShowing)
        limit.getButton(AlertDialog.BUTTON_NEGATIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        // Save is intercepted while the invalid draft remains, without changing persisted settings.
        views(root).filterIsInstance<Button>().first { it.text.toString() == "保存" }.performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        val again = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("300", input(again).text.toString())
        val saved = MainActivity::class.java.getDeclaredField("preprocessSettings").apply { isAccessible = true }.get(activity) as PreprocessSettings
        assertEquals(2, saved.version)
        assertEquals(300, saved.dataEccMismatchLimit)
        input(again).setText("208")
        again.getButton(AlertDialog.BUTTON_POSITIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.toString().contains("208bit") })
    }
}
