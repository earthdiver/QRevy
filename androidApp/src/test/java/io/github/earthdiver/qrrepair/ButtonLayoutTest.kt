package io.github.earthdiver.qrrepair

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w320dp-h640dp-port-mdpi")
class ButtonLayoutTest {
    @Test fun footerButtonsKeepEqualTopAndBottomWhenLabelsWrapAndPagesChange() {
        val app = RuntimeEnvironment.getApplication()
        for (style in AppStyle.entries) for (scale in listOf(1f, 1.5f)) {
            val configuration = Configuration(app.resources.configuration).apply { fontScale = scale }
            val context = ContextThemeWrapper(app.createConfigurationContext(configuration), style.theme)
            val factory = DialogViewFactory(context)
            val host = PreprocessPageHost(context,
                PreprocessPage.entries.associateWith { TextView(context) }, factory::button,
                listOf(
                    PreprocessPageHost.BasicAction("キャンセル") {},
                    PreprocessPageHost.BasicAction("保存") {},
                    PreprocessPageHost.BasicAction("設定を保存して\n修復を実行", 2f) {},
                ), {})
            for (page in listOf(PreprocessPage.BASIC, PreprocessPage.CELL_EDITOR, PreprocessPage.DATA, PreprocessPage.BASIC)) {
                host.navigateTo(page)
                host.view.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
                host.view.layout(0, 0, 280, 500)
                val rows = descendants(host.view).filterIsInstance<ViewGroup>().filter { row ->
                    row.visibility == View.VISIBLE && (0 until row.childCount).any { row.getChildAt(it) is Button }
                }
                for (row in rows) {
                    val buttons = (0 until row.childCount).map { row.getChildAt(it) }
                        .filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }
                    assertTrue(buttons.isNotEmpty())
                    assertEquals("Top offset: $style / $scale / $page", 1, buttons.map { it.top }.distinct().size)
                    assertEquals("Bottom offset: $style / $scale / $page", 1, buttons.map { it.bottom }.distinct().size)
                    for (button in buttons) {
                        assertTrue(button.height >= if (style == AppStyle.MODERN) 36 else 40)
                        assertTrue("Clipped text", button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                    }
                }
            }
        }
    }

    @Test fun modernTabsAreThreeQuartersOfTheirPreviousHeight() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), AppStyle.MODERN.theme)
        val navigation = ModernWorkflowNavigation(context, {}, {}, {})
        navigation.view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST))
        navigation.view.layout(0, 0, 360, navigation.view.measuredHeight)
        for (i in 0 until navigation.view.childCount) assertEquals(36, navigation.view.getChildAt(i).height)
        assertEquals(40, navigation.view.height)
    }

    @Test fun tabsKeepSelectionAndDisabledResultWithoutTriggeringActions() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), AppStyle.MODERN.theme)
        var edits = 0
        val navigation = ModernWorkflowNavigation(context, {}, { edits++ }, {})
        navigation.update(1, canShowResult = false)
        val tabs = (0..2).map { navigation.view.getChildAt(it) as Button }
        assertFalse(tabs[0].isSelected)
        assertTrue(tabs[1].isSelected)
        assertFalse(tabs[2].isEnabled)
        assertEquals(PrecisionStyle.ACCENT, tabs[1].currentTextColor)
        assertEquals(0, edits)
        tabs[1].performClick()
        assertEquals(1, edits)
        navigation.update(2)
        assertTrue(tabs[2].isSelected)
        assertTrue(tabs[2].isEnabled)
        assertFalse(tabs[1].isSelected)
    }

    @Test fun largeEditorHistoryAndCloseStayAtBottomOutsideScrollingControls() {
        val app = RuntimeEnvironment.getApplication()
        for (style in AppStyle.entries) for (height in listOf(640, 892)) {
            val context = ContextThemeWrapper(app, style.theme)
            val factory = DialogViewFactory(context)
            var undo = 0
            var redo = 0
            var close = 0
            val canvas = View(context)
            val page = PreRecoveryLargeCellEditPage(context, canvas, factory::button,
                { factory.compactRow(*it) }, { factory.scrollRow(*it) },
                LargeCellEditPageActions({}, {}, {}, {}, { undo++ }, { redo++ }, {}, { close++ }))
            page.countStatus.text = "変更操作: 12回・変更セル: 10セル　やり直し: 3回"
            val root = page.view as ViewGroup
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 320, height)
            val row = root.getChildAt(root.childCount - 1) as ViewGroup
            assertSame(root, page.countStatus.parent)
            assertEquals(page.countStatus.bottom, row.top)
            assertEquals(height, row.bottom)
            assertEquals(canvas.bottom, page.countStatus.top)
            assertTrue(canvas.height > 0)
            descendants(root).filterIsInstance<android.widget.ScrollView>().forEach {
                it.scrollTo(0, it.getChildAt(0).height)
            }
            assertEquals(height, row.bottom)
            for (i in 0..2) {
                val button = row.getChildAt(i) as Button
                assertTrue(button.height > 0)
                assertTrue(button.bottom <= row.height)
                button.performClick()
            }
            assertEquals(1, undo)
            assertEquals(1, redo)
            assertEquals(1, close)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
