package io.github.earthdiver.qrrepair

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ToggleButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP")
class PreRecoveryDataPageUiTest {
    @Test
    fun unmaskedViewIsSelectedInitially() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val page = PreRecoveryDataPage(
            activity,
            inputProvider = { null },
        )

        val toggles = findViews(page.view, ToggleButton::class.java)
        assertEquals(2, toggles.size)
        val unmask = toggles.first { it.textOff == "マスク解除前" }
        val partial = toggles.first { it.textOff == "HEX / Binary" }
        assertTrue(unmask.isChecked)
        assertEquals("マスク解除後", unmask.text.toString())
        assertTrue(!partial.isChecked)
        assertEquals("HEX / Binary", partial.text.toString())
    }

    private fun <T : View> findViews(view: View, type: Class<T>): List<T> = buildList {
        if (type.isInstance(view)) add(type.cast(view)!!)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            addAll(findViews(view.getChildAt(index), type))
        }
    }
}
