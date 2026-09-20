package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.QrEditorView
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
class TextMatrixWorkspaceTest {
    @Test fun importedMatrixReturnsToRealSettingsFromBothHomeActions() {
        AppStylePreferences.save(RuntimeEnvironment.getApplication(), AppStyle.MODERN)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            // An incomplete payload keeps the import on the settings page.
            val text = rows(21).joinToString("\n") { String(it) }
            val uri = Uri.parse("content://qr-repair-test/matrix")
            shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
            MainActivity::class.java.getDeclaredMethod("loadTextMatrix", Uri::class.java)
                .apply { isAccessible = true }.invoke(activity, uri)
            (field("executor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val settings = field("preprocessSettings").get(activity) as PreprocessSettings
            assertTrue(settings.manualFinders.isEmpty())
            assertFalse(settings.logicalGridAssigned)
            var dialog = ShadowDialog.getLatestDialog() as AlertDialog
            for (action in listOf(R.string.workspace_navigation, 0)) {
                label(dialog.window!!.decorView, activity.getString(R.string.home_navigation)).performClick()
                assertFalse(dialog.isShowing)
                if (action == 0) {
                    (field("resumeButton").get(activity) as View).performClick()
                } else label(activity.window.decorView, activity.getString(action)).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue("Returning home must reopen the settings editor", dialog.isShowing)
                assertTrue(label(dialog.window!!.decorView, activity.getString(R.string.step_adjust)).isSelected)
                assertEquals(1, views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().size)
            }
            dialog.dismiss()
            field("immediateResultShown").set(activity, true)
            MainActivity::class.java.getDeclaredMethod("openWorkspace").apply { isAccessible = true }.invoke(activity)
            assertFalse("An existing scan result must remain accessible", dialog.isShowing)
            assertTrue(label(activity.window.decorView, activity.getString(R.string.step_result)).isSelected)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun importedMatricesRequireOrientationInBothEditors() {
        for (intact in listOf(false, true)) for (large in listOf(false, true)) {
            val input = if (intact) rows(25) else Array(25) { CharArray(25) { '?' } }
            withImportedMatrix(input) { activity, dialog, settings ->
                assertEquals(2, settings.version)
                assertNotNull(settings.manualQuad)
                assertFalse(settings.logicalGridAssigned)
                assertTrue(settings.manualFinders.isEmpty())
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                assertFalse(overlay.isLogicalGridAssigned())
                assertTrue(label(dialog.window!!.decorView, "セル編集（長押し）").performLongClick())
                val editor = if (large) {
                    label(dialog.window!!.decorView, "大きな編集画面を開く").performClick()
                    ShadowDialog.getLatestDialog() as AlertDialog
                } else dialog
                val root = editor.window!!.decorView
                if (large) {
                    label(root, activity.getString(R.string.switch_to_rectangle)).performClick()
                    views(root).filterIsInstance<QrEditorView>().single().onCellSelected!!.invoke(17, 19)
                } else overlay.onEditCellSelected!!.invoke(17, 19)
                org.robolectric.shadows.ShadowToast.reset()
                label(root, "*").performClick()
                assertEquals(activity.getString(R.string.priority_requires_geometry),
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                if (large) editor.dismiss()
            }
        }
    }

    @Test fun finderAssignmentRotatesImportedEditsAndChecksPriorityPositions() {
        val input = Array(25) { CharArray(25) { '?' } }
        input[24][24] = '*'
        input[10][10] = '*'
        withImportedMatrix(input) { activity, dialog, settings ->
            assertEquals(setOf(Coord(24, 24), Coord(10, 10)), settings.preRecoveryPrioritySearch)
            val root = dialog.window!!.decorView
            val overlay = views(root).filterIsInstance<PreprocessOverlayView>().single()
            val quad = overlay.imageQuad()
            overlay.setFinderPoint(FinderRole.TOP_LEFT, quad.topRight)
            assertTrue(overlay.isLogicalGridAssigned())
            assertEquals(quad.asList().toSet(), overlay.imageQuad().asList().toSet())
            assertEquals(quad.topRight, overlay.imageQuad().topLeft)
            val notice = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(notice.findViewById<TextView>(android.R.id.message).text.contains("(24,0)"))
            notice.dismiss()
            label(root, "保存").performClick()
            val saved = field("preprocessSettings").get(activity) as PreprocessSettings
            assertTrue(saved.logicalGridAssigned)
            assertEquals(setOf(Coord(10, 14)), saved.preRecoveryPrioritySearch)
            assertEquals(ModuleValue.ERASED, saved.preRecoveryEdits[Coord(24, 0)])
        }
    }

    private fun withImportedMatrix(input: Array<CharArray>, block: (MainActivity, AlertDialog, PreprocessSettings) -> Unit) {
        AppStylePreferences.save(RuntimeEnvironment.getApplication(), AppStyle.CLASSIC)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val uri = Uri.parse("content://qr-repair-test/matrix")
            val text = input.joinToString("\n") { String(it) }
            shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
            MainActivity::class.java.getDeclaredMethod("loadTextMatrix", Uri::class.java)
                .apply { isAccessible = true }.invoke(activity, uri)
            (field("executor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            try { block(activity, dialog, field("preprocessSettings").get(activity) as PreprocessSettings) }
            finally { dialog.dismiss() }
        } finally { controller.pause().stop().destroy() }
    }

    private fun rows(dimension: Int): Array<CharArray> {
        val rows = Array(dimension) { CharArray(dimension) { '?' } }
        val pattern = listOf("1111111", "1000001", "1011101", "1011101", "1011101", "1000001", "1111111")
        for ((left, top) in listOf(0 to 0, dimension - 7 to 0, 0 to dimension - 7)) {
            pattern.forEachIndexed { y, row -> row.forEachIndexed { x, cell -> rows[top + y][left + x] = cell } }
        }
        return rows
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun label(root: View, text: String) = views(root).filterIsInstance<TextView>().first { it.text.toString() == text }
}
