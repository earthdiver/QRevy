package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.RecoveryEngine
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
class ModernResultNavigationTest {
    @Test fun resultTabViewsExistingResultWithoutSavingOrRepairingAndEditorHasRoom() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get().apply {
            AppLanguageManager.applyTo(this, AppLanguage.JAPANESE)
            setTheme(AppStyle.MODERN.theme)
        }
        val image = BitmapUtils.prepare(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
        var available = true
        var saves = 0
        var repairs = 0
        var views = 0
        lateinit var editor: PreprocessEditorSession
        val host = object : PreprocessDialogHost {
            override fun retains(image: BitmapUtils.Prepared) = true
            override fun save(draft: PreprocessSave) { saves++; available = false }
            override fun showSavedStatus() = Unit
            override fun startRecovery() { repairs++ }
            override fun saveDiagnosticInput(bitmap: Bitmap, owned: Boolean) = Unit
            override fun canShowResult() = available
            override fun showResult() { views++ }
            override fun editorOpened(session: PreprocessEditorSession) { editor = session }
        }
        PreprocessDialogController(activity, PreprocessSettings(), image, image, null,
            Executor { it.run() }, AtomicInteger(), RecoveryEngine(), host).show(PreprocessPage.CELL_EDITOR)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try {
            val root = dialog.window!!.decorView
            assertEquals("Numeric settings must still be able to open the keyboard", 0,
                dialog.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            fun text(value: String): TextView {
                val labels = descendants(root).filterIsInstance<TextView>()
                return labels.firstOrNull { it.text.toString() == value }
                    ?: error("Missing $value in ${labels.map { it.text.toString() }}")
            }
            val result = text(activity.getString(R.string.step_result))
            assertTrue(result.isEnabled)
            val overlay = descendants(root).filterIsInstance<PreprocessOverlayView>().single()
            overlay.onEditCellSelected!!.invoke(10, 10)
            text(activity.getString(R.string.cell_source_menu, activity.getString(R.string.cell_source_image))).performClick()
            org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu().menu.performIdentifierAction(CellEditSource.FIXED.ordinal, 0)
            val count = "変更操作: 1回・変更セル: 1セル　やり直し: 0回"
            assertNotNull(text(count))
            text(activity.getString(R.string.step_adjust)).performClick()
            text(activity.getString(R.string.details)).performClick()
            val fixedCorrections = text(activity.getString(R.string.allow_fixed_corrections)) as android.widget.CheckBox
            fixedCorrections.performClick()
            val fixedCorrectionsDraft = fixedCorrections.isChecked
            val returnToSettings = text(activity.getString(R.string.repair_settings_navigation))
            assertTrue("Detailed settings must offer a visible return to repair settings", returnToSettings.isShown)
            returnToSettings.performClick()
            assertTrue("The footer must return to repair settings", text(activity.getString(R.string.details)).isShown)
            assertEquals(0, saves)
            assertEquals(0, repairs)
            text(activity.getString(R.string.details)).performClick()
            assertEquals("Returning must preserve the detailed settings draft", fixedCorrectionsDraft, fixedCorrections.isChecked)
            text(activity.getString(R.string.repair_settings_navigation)).performClick()
            text(activity.getString(R.string.cell_edit_tap)).performClick()
            assertNotNull(text(count))
            result.performClick()
            assertEquals(1, views)
            assertFalse("Result navigation must hide the editor window", root.isShown)
            editor.open(PreprocessPage.CELL_EDITOR)
            assertEquals(0, saves)
            assertEquals(0, repairs)
            assertNotNull(text(count))
            assertTrue(dialog.isShowing)
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 640)
            var scroll: View? = text(count)
            while (scroll != null && scroll !is ScrollView) scroll = scroll.parent as? View
            assertTrue("Cell controls have too little room: ${scroll?.height}", scroll != null && scroll.height >= 150)
            assertEquals("Cell preview must fit a square QR", overlay.width, overlay.height)
            for (height in listOf(640, 892)) {
                for (pageLabel in listOf(R.string.step_adjust, R.string.step_edit)) {
                    text(activity.getString(pageLabel)).performClick()
                    root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 360, height)
                    assertEquals("Both image tabs must use the same square preview", overlay.width, overlay.height)
                    val footer = text(activity.getString(R.string.home_navigation)).parent as View
                    assertEquals(listOf("ホーム", if (pageLabel == R.string.step_edit) "修復設定" else "セル編集", "データ", "保存", "修復"),
                        (0 until (footer as ViewGroup).childCount).map { (footer.getChildAt(it) as TextView).text.toString() })
                    val rect = android.graphics.Rect(0, 0, footer.width, footer.height)
                    (root as ViewGroup).offsetDescendantRectToMyCoords(footer, rect)
                    assertTrue(footer.height > 0)
                    assertTrue("Footer floated upward at $height: $rect", height - rect.bottom in 0..24)
                    val switch = footer.getChildAt(1) as android.widget.Button
                    val background = switch.background as android.graphics.drawable.RippleDrawable
                    assertEquals(PrecisionStyle.ACCENT,
                        (background.getDrawable(0) as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
                    switch.performClick()
                    assertTrue(text(activity.getString(if (pageLabel == R.string.step_edit) R.string.step_adjust else R.string.step_edit)).isSelected)
                    assertNotNull(text(count))
                    assertEquals(0, saves)
                    assertEquals(0, repairs)
                }
            }
            text("保存").performClick()
            assertEquals(1, saves)
            assertEquals(0, repairs)
            assertTrue(dialog.isShowing)
            assertNotNull(text(count))
            assertFalse(result.isEnabled)
            text(activity.getString(R.string.repair_short)).performClick()
            assertEquals(2, saves)
            assertEquals(1, repairs)
        } finally { dialog.dismiss(); controller.pause().stop().destroy(); image.bitmap.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
