package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class AutomaticRepairPreferencesTest {
    @Test fun menuPreferencePersistsAcrossRecreationAndCanBeEnabledAgain() {
        val context = RuntimeEnvironment.getApplication()
        AppLanguageManager.save(context, AppLanguage.ENGLISH)
        assertTrue(AutomaticRepairPreferences.enabled(context))
        for (style in AppStyle.entries) for (language in AppLanguage.entries) {
            AppLanguageManager.save(context, language)
            AppStylePreferences.save(context, style)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                fun open(enabled: Boolean): AlertDialog {
                    MainActivity::class.java.getDeclaredMethod("showAppMenu").apply { isAccessible = true }.invoke(controller.get())
                    val menu = ShadowDialog.getLatestDialog() as AlertDialog
                    val prefix = if (language == AppLanguage.JAPANESE) "簡易自動修復：" else "Quick automatic repair: "
                    val position = (0 until menu.listView.count).single {
                        menu.listView.adapter.getItem(it).toString() ==
                            prefix + if (enabled) "ON" else "OFF"
                    }
                    menu.listView.performItemClick(null, position, position.toLong())
                    return ShadowDialog.getLatestDialog() as AlertDialog
                }
                open(true).listView.performItemClick(null, 1, 1L)
                assertFalse(AutomaticRepairPreferences.enabled(context))
                controller.recreate()
                val saved = open(false)
                assertEquals(1, saved.listView.checkedItemPosition)
                saved.dismiss()
                assertFalse(AutomaticRepairPreferences.enabled(context))
                open(false).listView.performItemClick(null, 0, 0L)
                assertTrue(AutomaticRepairPreferences.enabled(context))
                open(true).dismiss()
            } finally {
                controller.pause().stop().destroy()
                AutomaticRepairPreferences.save(context, true)
                AppLanguageManager.save(context, AppLanguage.ENGLISH)
            }
        }
    }
}
