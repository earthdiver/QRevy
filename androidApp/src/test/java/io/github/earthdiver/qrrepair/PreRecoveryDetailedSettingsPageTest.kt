package io.github.earthdiver.qrrepair

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreRecoveryDetailedSettingsPageTest {
    @Test
    fun errorLimitsAreImmediatelyAboveDebugImageButton() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val formatLimitControl = View(activity)
        val dataEccLimitControl = View(activity)
        val page = PreRecoveryDetailedSettingsPage(
            context = activity,
            buttonFactory = { label, action -> Button(activity).apply {
                text = label
                setOnClickListener { action() }
            } },
            scrollRow = { views -> LinearLayout(activity).apply { views.forEach(::addView) } },
            polarity = RadioGroup(activity),
            localThreshold = CheckBox(activity),
            searchQuadNeighborhood = CheckBox(activity),
            trustKnownPayloadCount = CheckBox(activity),
            lockManualBits = CheckBox(activity),
            formatMismatchLimitControl = formatLimitControl,
            dataEccMismatchLimitControl = dataEccLimitControl,
            actions = DetailedSettingsPageActions({}, {}, {}),
        )

        val content = (page.view as ScrollView).getChildAt(0) as LinearLayout

        assertSame(formatLimitControl, content.getChildAt(content.childCount - 3))
        assertSame(dataEccLimitControl, content.getChildAt(content.childCount - 2))
        assertEquals(
            activity.getString(R.string.save_debug_input),
            (content.getChildAt(content.childCount - 1) as Button).text,
        )
    }
}
