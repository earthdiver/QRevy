package io.github.earthdiver.qrrepair

import android.app.Activity
import android.view.View
import android.widget.Button
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w320dp-h640dp-port-mdpi")
class CellSourceControlTest {
    @Test fun menuKeepsEditedValueUntilImageIsExplicitlySelected() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val coord = Coord(10, 12)
            val baseline = QrMatrix(21).apply { set(coord.x, coord.y, ModuleValue.ONE, 1f, ModuleSource.IMAGE) }
            val state = PreRecoveryCellEditState(emptyMap(), emptySet()).apply { select(listOf(coord)) }
            lateinit var control: CellSourceControl
            control = CellSourceControl(activity, { text, action -> Button(activity).apply {
                this.text = text
                setOnClickListener { action() }
            } }) { mode ->
                state.setSource(state.selectedCells, mode) { baseline[it.x, it.y].value }
                control.update(state, baseline)
            }
            activity.setContentView(control.view)
            control.update(state, baseline)
            fun choose(source: CellEditSource) {
                control.button.performClick()
                val popup = ShadowPopupMenu.getLatestPopupMenu()
                assertEquals(3, popup.menu.size())
                assertTrue(popup.menu.performIdentifierAction(source.ordinal, 0))
                popup.dismiss()
            }
            choose(CellEditSource.FIXED)
            assertEquals(ModuleValue.ONE, state.edits[coord])
            state.applyValues(listOf(coord), ModuleValue.ZERO) { ModuleValue.ONE }
            control.update(state, baseline)
            choose(CellEditSource.TENTATIVE)
            assertEquals(ModuleValue.ZERO, state.edits[coord])
            assertEquals(setOf(coord), state.softEdits)
            assertTrue(control.button.text.contains(activity.getString(R.string.cell_source_tentative)))
            choose(CellEditSource.FIXED)
            assertEquals(ModuleValue.ZERO, state.fixedEdits[coord])
            choose(CellEditSource.IMAGE)
            assertTrue(state.edits.isEmpty())
            assertTrue(control.summary.text.contains("1"))
            state.clearSelection()
            control.update(state, baseline)
            assertFalse(control.button.isEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun fixedMenuIsDisabledForImageUnknownAndTentativePriority() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val coord = Coord(10, 12)
            val baseline = QrMatrix(21)
            val state = PreRecoveryCellEditState(emptyMap(), emptySet()).apply { select(listOf(coord)) }
            val control = CellSourceControl(activity, { text, action -> Button(activity).apply {
                this.text = text
                setOnClickListener { action() }
            } }, {})
            activity.setContentView(control.view)
            fun verify() {
                control.update(state, baseline)
                control.button.performClick()
                val popup = ShadowPopupMenu.getLatestPopupMenu()
                assertFalse(popup.menu.findItem(CellEditSource.FIXED.ordinal).isEnabled)
                assertTrue(popup.menu.findItem(CellEditSource.TENTATIVE.ordinal).isEnabled)
                popup.dismiss()
            }
            verify()
            state.setPrioritySearch(listOf(coord))
            assertEquals(CellEditSource.TENTATIVE, state.sourceAt(coord))
            verify()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun bothStylesExposeAndSynchronizeEditedCellHighlighting() {
        for (style in AppStyle.entries) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            activity.setTheme(style.theme)
            try {
                fun control() = CellSourceControl(activity, { text, action -> Button(activity).apply {
                    this.text = text
                    setOnClickListener { action() }
                } }, {})
                val main = control()
                val large = control()
                var enabled = false
                val sync: (Boolean) -> Unit = {
                    enabled = it
                    main.setManualFocus(it)
                    large.setManualFocus(it)
                }
                main.onManualFocusChanged = sync
                large.onManualFocusChanged = sync
                fun checkbox(control: CellSourceControl) = (0 until control.view.childCount)
                    .map { control.view.getChildAt(it) }.filterIsInstance<android.widget.CheckBox>().single()
                val first = checkbox(main)
                val second = checkbox(large)
                assertEquals("指定セルを強調", first.text.toString())
                assertEquals(View.VISIBLE, first.visibility)
                first.performClick()
                assertTrue(enabled)
                assertTrue(second.isChecked)
                second.performClick()
                assertFalse(enabled)
                assertFalse(first.isChecked)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun sourceLabelsFitNarrowWidthsAndLargeText() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(android.content.res.Configuration(activity.resources.configuration).apply {
                fontScale = 1.5f
            }, activity.resources.displayMetrics)
            val coord = Coord(10, 10)
            val baseline = QrMatrix(21).apply { set(coord.x, coord.y, ModuleValue.ONE, 1f, ModuleSource.IMAGE) }
            val state = PreRecoveryCellEditState(emptyMap(), emptySet()).apply { select(listOf(coord)) }
            val control = CellSourceControl(activity, { text, action -> Button(activity).apply {
                this.text = text
                setOnClickListener { action() }
            } }, {})
            activity.setContentView(control.view)
            for (width in listOf(320, 360)) for (source in CellEditSource.entries) {
                state.setSource(listOf(coord), source) { ModuleValue.ONE }
                control.update(state, baseline)
                control.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                control.view.layout(0, 0, width, control.view.measuredHeight)
                assertTrue(control.button.right <= width)
                assertTrue(control.button.height > 0)
                val layout = requireNotNull(control.button.layout)
                for (line in 0 until layout.lineCount) {
                    assertEquals("Clipped $source at width $width", 0, layout.getEllipsisCount(line))
                    assertTrue(layout.getLineWidth(line) <= control.button.width - control.button.compoundPaddingLeft - control.button.compoundPaddingRight)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}
