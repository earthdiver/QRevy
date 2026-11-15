package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
@Config(sdk = [35], qualifiers = "en-rUS")
class AutomaticRepairDiagnosticUiTest {
    @Test fun diagnosticsAreOpenedFromTheDeveloperSubmenuInBothLanguages() {
        for (language in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val ja = language == AppLanguage.JAPANESE
                val developer = if (ja) "開発者向けメニュー" else "Developer menu"
                val diagnostics = if (ja) "簡易自動修復の診断情報" else "Quick automatic repair diagnostics"
                MainActivity::class.java.getDeclaredMethod("showAppMenu").apply { isAccessible = true }.invoke(activity)
                val menu = ShadowDialog.getLatestDialog() as AlertDialog
                val labels = (0 until menu.listView.count).map { menu.listView.adapter.getItem(it).toString() }
                assertFalse(labels.contains(diagnostics))
                val index = labels.indexOf(developer)
                assertTrue(index >= 0)
                menu.listView.performItemClick(null, index, index.toLong())
                val submenu = ShadowDialog.getLatestDialog() as AlertDialog
                assertEquals(1, submenu.listView.count)
                assertEquals(diagnostics, submenu.listView.adapter.getItem(0).toString())
                submenu.listView.performItemClick(null, 0, 0L)
                assertNotSame(submenu, ShadowDialog.getLatestDialog())
                assertTrue(ShadowDialog.getLatestDialog().isShowing)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun failedImageRepairShowsItsReasonBeforeOpeningDetailedAnalysis() {
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        AutomaticRepairPreferences.save(RuntimeEnvironment.getApplication(), true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            MainActivity::class.java.getDeclaredMethod("acceptDecodedImage", Bitmap::class.java, java.io.File::class.java,
                ImageAdjustment::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(activity, bitmap, null, ImageAdjustment(), false)
            repeat(5) {
                for (name in listOf("executor", "ioExecutor"))
                    (field(name).get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertNotNull(field("automaticDiagnostic").get(activity))
            assertNull(field("preprocessEditor").get(activity))
            assertNull((field("recoveryState").get(activity) as RecoveryUiState).selectedRecovery)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(shadowOf(dialog).message.toString().contains("could not be estimated"))
            assertNotEquals("Copy diagnostics", dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.text?.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(field("preprocessEditor").get(activity))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun diagnosticsOfferOnlyCopyAndCloseAndPreserveDetailedSettingsInBothLanguages() {
        for (language in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val source = BitmapUtils.prepare(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
                val original = PreprocessSettings(lmCorrectionEnabled = false)
                val edited = original.copy(version = 2, preRecoveryEdits = mapOf(Coord(10, 10) to ModuleValue.ONE))
                field("preprocessSettings").set(activity, edited)
                val diagnostic = AutomaticRepairDiagnosticSession(source.luma, original, "FINISH: reason=TOTAL_TIME_LIMIT").apply {
                    extendedReport = "Ten-second attempt"
                    unlimitedRepairReport = "Unlimited attempt"
                }
                field("automaticDiagnostic").set(activity, diagnostic)
                MainActivity::class.java.getDeclaredMethod("showAutomaticDiagnostics").apply { isAccessible = true }.invoke(activity)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val visibleButtons = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                    .map { dialog.getButton(it) }.filter { it.visibility == View.VISIBLE }
                assertEquals(setOf(activity.getString(R.string.close),
                    if (language == AppLanguage.JAPANESE) "診断をコピー" else "Copy diagnostics"),
                    visibleButtons.map { it.text.toString() }.toSet())
                assertEquals(2, visibleButtons.size)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                assertTrue(dialog.isShowing)
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(diagnostic.report(), clipboard.primaryClip!!.getItemAt(0).text.toString())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(dialog.isShowing)
                assertEquals(edited, field("preprocessSettings").get(activity))
                assertNull(field("preprocessEditor").get(activity))
                assertEquals(QuickAutoRetryMode.TEN_SECONDS, diagnostic.nextRetry)
            } finally { controller.pause().stop().destroy() }
        }
    }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
