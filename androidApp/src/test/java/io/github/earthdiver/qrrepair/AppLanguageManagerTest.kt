package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class AppLanguageManagerTest {
    @Test
    @Config(sdk = [35], qualifiers = "ja-rJP")
    fun JapaneseSystemDefaultsToJapanese() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        clearPreference(activity)
        assertEquals(AppLanguage.JAPANESE, AppLanguageManager.selected(activity))
        val wrapped = AppLanguageManager.wrap(activity)
        assertEquals("メニュー", wrapped.getString(R.string.menu))
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS")
    fun nonJapaneseSystemDefaultsToEnglishAndSavedChoiceWins() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        clearPreference(activity)
        assertEquals(AppLanguage.ENGLISH, AppLanguageManager.selected(activity))
        assertEquals("Menu", AppLanguageManager.wrap(activity).getString(R.string.menu))
        AppLanguageManager.save(activity, AppLanguage.JAPANESE)
        assertEquals(AppLanguage.JAPANESE, AppLanguageManager.selected(activity))
        assertEquals("メニュー", AppLanguageManager.wrap(activity).getString(R.string.menu))
    }

    @Test
    @Config(sdk = [35], qualifiers = "ja-rJP")
    fun switchingLanguageKeepsInMemoryWorkAndDisplayedPayload() {
        clearPreference(org.robolectric.RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        val prepared = io.github.earthdiver.qrrepair.ui.BitmapUtils.prepare(
            android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888),
        )
        MainActivity::class.java.getDeclaredField("prepared").apply {
            isAccessible = true
            set(activity, prepared)
        }
        val contentField = MainActivity::class.java.getDeclaredField("contentPreviewText").apply { isAccessible = true }
        (contentField.get(activity) as TextView).text = "復号内容: 日本語"

        MainActivity::class.java.getDeclaredMethod("applyLanguage", AppLanguage::class.java).apply {
            isAccessible = true
            invoke(activity, AppLanguage.ENGLISH)
        }

        assertSame(prepared, MainActivity::class.java.getDeclaredField("prepared").apply { isAccessible = true }.get(activity))
        assertEquals("復号内容: 日本語", (contentField.get(activity) as TextView).text.toString())
        assertNotNull(findText(activity.window.decorView, "Menu"))
        clearPreference(activity)
    }

    private fun findText(view: android.view.View, expected: String): TextView? {
        if (view is TextView && view.text.toString() == expected) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findText(view.getChildAt(index), expected)?.let { return it }
        }
        return null
    }

    private fun clearPreference(context: android.content.Context) {
        context.getSharedPreferences("app_language", Activity.MODE_PRIVATE).edit().clear().commit()
    }
}
