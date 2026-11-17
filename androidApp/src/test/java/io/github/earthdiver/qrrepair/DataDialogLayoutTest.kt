package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ToggleButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], qualifiers = "en-rUS-w480dp-h853dp-mdpi")
class DataDialogLayoutTest {
    @Test fun switchingBetweenShortAndScrollableDataKeepsTheControlsInPlace() {
        for (style in AppStyle.entries) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get().apply { setTheme(style.theme) }
            val text = TextView(activity)
            val toggle = ToggleButton(activity).apply { textOn = "Partial info"; textOff = "Hex / Binary" }
            val scroll = ScrollView(activity).apply { addView(text) }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(toggle)
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            val dialog = showDataDialog(activity, content)
            try {
                val decor = dialog.window!!.decorView as ViewGroup
                fun layout(): Pair<Rect, Rect> {
                    val requested = dialog.window!!.attributes.height
                    val height = if (requested > 0) requested else 800
                    val mode = if (requested > 0) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
                    decor.measure(View.MeasureSpec.makeMeasureSpec(460, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, mode))
                    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                    fun rect(view: View) = Rect(0, 0, view.width, view.height).also {
                        decor.offsetDescendantRectToMyCoords(view, it)
                    }
                    return rect(toggle) to rect(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
                }
                text.text = "A short explanation"
                val positions = layout()
                for (value in listOf("0123456789 ABCDEF\n".repeat(300), "A short explanation", "Different short data")) {
                    text.text = value
                    toggle.toggle()
                    val current = layout()
                    assertEquals("$style toggle moved", positions.first.top, current.first.top)
                    assertEquals("$style close button moved", positions.second, current.second)
                    assertTrue("$style data body must fill the available height", scroll.height > decor.height / 2)
                    assertTrue(current.second.bottom <= decor.height)
                    if (value.length > 1000) assertTrue(scroll.canScrollVertically(1))
                }
            } finally { dialog.dismiss(); controller.pause().stop().destroy() }
        }
    }
}
