package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
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
            assertEquals(3, settings.manualFinders.size)
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

    @Test fun generatedQrAssignsAllThreeFinderCenters() {
        val qr = Encoder.encode("Text matrix finder regression", ErrorCorrectionLevel.M).matrix
        val text = (0 until qr.height).joinToString("\n") { y ->
            (0 until qr.width).joinToString("") { x -> qr.get(x, y).toString() }
        }
        val matrix = QrTextMatrixParser.parse(text).matrix
        val finders = BitmapUtils.textMatrixFinders(matrix)
        assertEquals(setOf(FinderRole.TOP_LEFT, FinderRole.TOP_RIGHT, FinderRole.BOTTOM_LEFT), finders.keys)
        val near = 59.5
        val far = (matrix.dimension + .5) * 8 - .5
        assertEquals(PointD(near, near), finders[FinderRole.TOP_LEFT])
        assertEquals(PointD(far, near), finders[FinderRole.TOP_RIGHT])
        assertEquals(PointD(near, far), finders[FinderRole.BOTTOM_LEFT])
    }

    @Test fun damagedOrUnknownFindersAreNotAssignedAndOneFinderRemainsAvailable() {
        for (dimension in listOf(21, 177)) {
            val rows = rows(dimension)
            rows[0][dimension - 7] = '0'
            rows[dimension - 7][0] = '*'
            val matrix = QrTextMatrixParser.parse(rows.joinToString("\n") { String(it) }).matrix
            assertEquals(mapOf(FinderRole.TOP_LEFT to PointD(59.5, 59.5)), BitmapUtils.textMatrixFinders(matrix))
            assertEquals(ModuleValue.ERASED, matrix[0, dimension - 7].value)
            assertTrue(matrix[0, dimension - 7].prioritySearch)
            assertEquals(ModuleSource.IMAGE, matrix[0, 0].source)
        }
        assertTrue(BitmapUtils.textMatrixFinders(QrMatrix(21)).isEmpty())
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
