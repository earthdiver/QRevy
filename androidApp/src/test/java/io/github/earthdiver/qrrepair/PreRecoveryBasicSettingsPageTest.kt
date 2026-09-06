package io.github.earthdiver.qrrepair

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreRecoveryBasicSettingsPageTest {
    @Test
    fun formatControlsAreTheLastItemInsideTheScrollableContent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val footer = View(activity)
        val page = PreRecoveryBasicSettingsPage(
            context = activity,
            buttonFactory = { label, action -> Button(activity).apply {
                text = label
                setOnClickListener { action() }
            } },
            compactRow = { views -> LinearLayout(activity).apply { views.forEach { addView(it) } } },
            versionButton = Button(activity),
            blackLabel = TextView(activity),
            blackSeekBar = SeekBar(activity),
            whiteLabel = TextView(activity),
            whiteSeekBar = SeekBar(activity),
            useUnknownColors = CheckBox(activity),
            scrollContentFooter = footer,
            actions = BasicSettingsPageActions({}, {}, { _, _, _ -> true }, {}, {}, { false }),
        )

        val scroll = page.view as ScrollView
        val content = scroll.getChildAt(0) as LinearLayout
        val summary = (0 until content.childCount)
            .map(content::getChildAt)
            .filterIsInstance<TextView>()
            .first { it.text == activity.getString(R.string.fixed_geometry_summary) }
        assertSame(footer, content.getChildAt(content.childCount - 1))
        val expectedTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            activity.resources.displayMetrics,
        )
        assertEquals(expectedTextSize, summary.textSize, 0.01f)
    }
}
