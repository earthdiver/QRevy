package io.github.earthdiver.qrrepair

import android.app.Activity
import android.view.View
import android.widget.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreprocessPageHostTest {
    @Test
    fun hostChangesExactlyOnePageAndReportsTheTransition() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val pages = PreprocessPage.entries.associateWith { View(activity) }
        val shown = mutableListOf<PreprocessPage>()
        val host = PreprocessPageHost(
            activity,
            pages,
            buttonFactory = { label, action -> Button(activity).apply {
                text = label
                setOnClickListener { action() }
            } },
            basicActions = listOf(PreprocessPageHost.BasicAction("保存") {}),
            onPageShown = shown::add,
        )

        host.navigateTo(PreprocessPage.DATA)

        assertEquals(PreprocessPage.DATA, host.currentPage())
        assertEquals(listOf(PreprocessPage.BASIC, PreprocessPage.DATA), shown)
        assertEquals(listOf(PreprocessPage.DATA), pages.filterValues { it.visibility == View.VISIBLE }.keys.toList())
    }

    @Test
    fun hostBackButtonUsesNavigationStateInsteadOfVisibleViewInference() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val pages = PreprocessPage.entries.associateWith { View(activity) }
        val host = PreprocessPageHost(
            activity,
            pages,
            buttonFactory = { label, action -> Button(activity).apply {
                text = label
                setOnClickListener { action() }
            } },
            basicActions = emptyList(),
            onPageShown = {},
        )
        host.navigateTo(PreprocessPage.CELL_EDITOR)
        // Simulate stale external visibility. It must not determine the Back destination.
        pages.getValue(PreprocessPage.BASIC).visibility = View.VISIBLE

        host.navigateBack()

        assertEquals(PreprocessPage.BASIC, host.currentPage())
        assertTrue(pages.getValue(PreprocessPage.BASIC).visibility == View.VISIBLE)
    }

}
