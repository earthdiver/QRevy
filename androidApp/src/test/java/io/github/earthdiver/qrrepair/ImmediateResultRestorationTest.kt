package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.TextView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.RecoverySessionHistory
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
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port-mdpi")
class ImmediateResultRestorationTest {
    @Test fun scanResultSurvivesRecreationAndColdSessionRestoreWithoutAnotherNotice() {
        for (style in AppStyle.entries) {
            AppStylePreferences.save(RuntimeEnvironment.getApplication(), style)
            var controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                load(controller.get())
                assertResult(controller.get())
                (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
                controller.recreate()
                drain(controller.get())
                assertResult(controller.get())
                assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
                val state = Bundle()
                controller.saveInstanceState(state).pause().stop().destroy()
                controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
                drain(controller.get())
                assertResult(controller.get())
                assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun aSettingsCheckpointDoesNotRestoreTheEarlierScanResult() {
        var controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            load(activity)
            val history = field(activity, "sessionHistory") as RecoverySessionHistory
            history.record(history.current!!.copy(id = "settings-checkpoint", kind = HistoryEventKind.SETTINGS))
            MainActivity::class.java.getDeclaredMethod("persistSessionAsync").apply { isAccessible = true; invoke(activity) }
            drain(activity)
            (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
            controller.recreate()
            drain(controller.get())
            assertFalse(field(controller.get(), "immediateResultShown") as Boolean)
            assertFalse((field(controller.get(), "contentPreviewText") as TextView).text.contains(TEXT))
            ShadowDialog.getLatestDialog()?.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun load(activity: MainActivity) {
        val matrix = Encoder.encode(TEXT, ErrorCorrectionLevel.M).matrix
        val text = (0 until matrix.height).joinToString("\n") { y ->
            (0 until matrix.width).joinToString("") { x -> matrix.get(x, y).toString() }
        }
        val uri = Uri.parse("content://synthetic-fixture/immediate-matrix")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        MainActivity::class.java.getDeclaredMethod("loadTextMatrix", Uri::class.java).apply { isAccessible = true; invoke(activity, uri) }
        drain(activity)
    }
    private fun assertResult(activity: MainActivity) {
        assertTrue(field(activity, "immediateResultShown") as Boolean)
        assertTrue((field(activity, "contentPreviewText") as TextView).text.contains(TEXT))
    }
    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun drain(activity: MainActivity) {
        repeat(4) {
            for (name in listOf("executor", "ioExecutor")) {
                (field(activity, name) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }
    companion object { private const val TEXT = "RESULT-MUST-STAY-ACCESSIBLE" }
}
