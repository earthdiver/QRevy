package io.github.earthdiver.qrrepair

import android.widget.ScrollView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class MainActivityInsetsTest {
    @Config(sdk = [35])
    @Test fun systemBarsAndCutoutsKeepMainActionsVisibleWithoutAccumulatingPadding() = withTestAppLanguage(AppLanguage.ENGLISH) {
        val app = RuntimeEnvironment.getApplication()
        val original = AppStylePreferences.selected(app)
        try {
            for (style in AppStyle.entries) {
                AppStylePreferences.save(app, style)
                val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    val root = controller.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    val base = listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
                    val insets = WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 48))
                        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(16, 0, 0, 0)).build()
                    repeat(2) {
                        ViewCompat.dispatchApplyWindowInsets(root, insets)
                        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, 360, 640)
                        assertEquals(listOf(base[0] + 16, base[1] + 24, base[2], base[3] + 48),
                            listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom))
                        for (button in views(root).filter { it.isShown && (it is Button || it is ScrollView) && !insideScrollView(it, root) }) {
                            val rect = android.graphics.Rect(0, 0, button.width, button.height)
                            (root as ViewGroup).offsetDescendantRectToMyCoords(button, rect)
                            assertTrue("$style: ${(button as? Button)?.text ?: "scroll viewport"} below navigation bar: $rect", rect.bottom <= 592)
                        }
                    }
                    ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.CONSUMED)
                    assertEquals(base, listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom))
                } finally { controller.pause().stop().destroy() }
            }
        } finally { AppStylePreferences.save(app, original) }
    }
    @Test fun consumedInsetsLeaveExistingContentPaddingUnchanged() = withTestAppLanguage(AppLanguage.ENGLISH) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val base = listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
            repeat(2) { ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.CONSUMED) }
            assertEquals(base, listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom))
        } finally { controller.pause().stop().destroy() }
    }
    private fun insideScrollView(view: View, root: View): Boolean {
        var parent = view.parent
        while (parent is View && parent !== root) {
            if (parent is ScrollView) return true
            parent = parent.parent
        }
        return false
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
