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
    @Config(qualifiers = "ja-rJP-w320dp-h640dp-port-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fixedCandidateOptionWrapsAtLargeTextSize() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(android.content.res.Configuration(activity.resources.configuration).apply {
                fontScale = 1.5f
            }, activity.resources.displayMetrics)
            val option = CheckBox(activity).apply { setText(R.string.allow_fixed_corrections) }
            option.measure(View.MeasureSpec.makeMeasureSpec(296, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            option.layout(0, 0, option.measuredWidth, option.measuredHeight)
            val layout = requireNotNull(option.layout)
            org.junit.Assert.assertTrue(layout.lineCount > 1)
            for (line in 0 until layout.lineCount) {
                assertEquals(0, layout.getEllipsisCount(line))
                org.junit.Assert.assertTrue(layout.getLineWidth(line) <= option.width - option.compoundPaddingLeft - option.compoundPaddingRight)
            }
            org.junit.Assert.assertTrue(layout.height <= option.height - option.compoundPaddingTop - option.compoundPaddingBottom)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun errorLimitsAreImmediatelyAboveDebugImageButton() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val formatLimitControl = View(activity)
        val dataEccLimitControl = View(activity)
        val partialBitControl = View(activity)
        val priorityControl = View(activity)
        val page = PreRecoveryDetailedSettingsPage(
            context = activity,
            buttonFactory = { label, action -> Button(activity).apply {
                text = label
                setOnClickListener { action() }
            } },
            scrollRow = { views -> LinearLayout(activity).apply { views.forEach(::addView) } },
            polarity = RadioGroup(activity),
            searchQuadNeighborhood = CheckBox(activity),
            trustKnownPayloadCount = CheckBox(activity),
            allowFixedCorrections = CheckBox(activity),
            formatMismatchLimitControl = formatLimitControl,
            dataEccMismatchLimitControl = dataEccLimitControl,
            actions = DetailedSettingsPageActions({}, {}, {}),
            partialBitSearchControl = partialBitControl,
            prioritySearchControl = priorityControl,
        )

        val content = (page.view as ScrollView).getChildAt(0) as LinearLayout

        assertEquals(content.indexOfChild(partialBitControl) + 1, content.indexOfChild(priorityControl))
        assertSame(formatLimitControl, content.getChildAt(content.childCount - 3))
        assertSame(dataEccLimitControl, content.getChildAt(content.childCount - 2))
        assertEquals(
            activity.getString(R.string.save_debug_input),
            (content.getChildAt(content.childCount - 1) as Button).text,
        )
    }
}
