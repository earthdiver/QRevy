package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP")
class AppStyleTest {
    @Test fun menuChoiceWaitsForNextLaunchAndPersistsUntilChangedAgain() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = first.get()
            assertFalse(AppStylePreferences.isModern(activity))
            (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
            choose(activity, AppStyle.MODERN)
            assertEquals(AppStyle.MODERN, AppStylePreferences.selected(activity))
            assertFalse(AppStylePreferences.isModern(activity))
            assertNull(find(activity.window.decorView, "ホーム"))
            // New dialogs/controls still use the active style after a pending change.
            assertEquals(40, DialogViewFactory(activity).button("test") {}.minimumHeight)
            first.recreate()
            assertFalse(AppStylePreferences.isModern(first.get()))
            MainActivity::class.java.getDeclaredMethod("applyLanguage", AppLanguage::class.java)
                .apply { isAccessible = true }.invoke(first.get(), AppLanguage.ENGLISH)
            assertFalse(AppStylePreferences.isModern(first.get()))
        } finally { first.pause().stop().destroy() }

        repeat(2) { launch ->
            val next = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = next.get()
                assertTrue(AppStylePreferences.isModern(activity))
                assertEquals("QR Repair", find(activity.window.decorView, "QR Repair")!!.text.toString())
                if (launch == 1) {
                    choose(activity, AppStyle.CLASSIC)
                    assertTrue(AppStylePreferences.isModern(activity))
                    assertEquals(36, DialogViewFactory(activity).button("test") {}.minimumHeight)
                    next.recreate()
                    assertTrue(AppStylePreferences.isModern(next.get()))
                }
            } finally { next.pause().stop().destroy() }
        }
        val last = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertFalse(AppStylePreferences.isModern(last.get()))
            assertEquals(AppStyle.CLASSIC, AppStylePreferences.selected(last.get()))
        } finally { last.pause().stop().destroy() }
    }

    @Test fun cancelDoesNotSaveAndReopeningShowsPendingSelection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
            var dialog = openStyleMenu(activity)
            dialog.listView.performItemClick(null, 1, 1)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AppStyle.CLASSIC, AppStylePreferences.selected(activity))
            choose(activity, AppStyle.MODERN)
            dialog = openStyleMenu(activity)
            assertEquals(1, dialog.listView.checkedItemPosition)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AppStyle.MODERN, AppStylePreferences.selected(activity))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun processRestorationReadsSavedChoiceInsteadOfOldActivityStyle() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val state = Bundle()
        first.saveInstanceState(state)
        AppStylePreferences.save(first.get(), AppStyle.MODERN)
        first.pause().stop().destroy()
        // No nonconfiguration object survives process death, but Android may restore the Bundle.
        val restored = Robolectric.buildActivity(MainActivity::class.java).setup(state)
        try { assertTrue(AppStylePreferences.isModern(restored.get())) }
        finally { restored.pause().stop().destroy() }
    }

    @Test fun absentOrUnrecognizedPreferenceFallsBackToClassic() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(AppStyle.CLASSIC, AppStylePreferences.selected(app))
        app.getSharedPreferences("app_appearance", 0).edit().putString("style", "unrecognized").commit()
        assertEquals(AppStyle.CLASSIC, AppStylePreferences.selected(app))
    }

    private fun openStyleMenu(activity: MainActivity): AlertDialog {
        find(activity.window.decorView, activity.getString(R.string.menu))!!.performClick()
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        val list = menu.listView
        val index = (0 until list.adapter.count).first { list.adapter.getItem(it) == activity.getString(R.string.appearance) }
        list.performItemClick(null, index, index.toLong())
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun choose(activity: MainActivity, style: AppStyle) {
        val dialog = openStyleMenu(activity)
        val index = if (style == AppStyle.CLASSIC) 0 else 1
        dialog.listView.performItemClick(null, index, index.toLong())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(find(confirmation.window!!.decorView, activity.getString(R.string.style_next_launch)))
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun find(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), text)?.let { return it }
        return null
    }
}
