package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP")
class RecoveryProgressDialogTest {
    @Test fun popupReportsProgressAndElapsedTimeAndRequestsCooperativeInterruption() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val progress = RecoveryProgressDialog(activity.get(), 14)
        try {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            progress.priorityProgress(17, 16384)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
            assertTrue(message.contains("17 / 16384"))
            assertTrue(message, (1..2).any { seconds -> message.contains(localizedText("経過時間: ${seconds}秒", "Elapsed: ${seconds}s")) })
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(progress.stopped.get())
            assertTrue(dialog.isShowing)
            assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            progress.close()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(dialog.isShowing)
        } finally {
            progress.close()
            activity.pause().stop().destroy()
        }
    }
    @Test fun ordinaryRepairHasNoStarCounterButCanBeInterrupted() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val progress = RecoveryProgressDialog(activity.get(), 0)
        try {
            progress.phase("Grid 1 / 2")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
            assertTrue(message.contains("Grid 1 / 2"))
            assertFalse(message.contains("*"))
            assertFalse(message.contains("0 / 0"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(progress.stopped.get())
        } finally {
            progress.close()
            activity.pause().stop().destroy()
        }
    }

    @Test fun priorityCounterStartsOnlyWithEnumerationAndResetsForNextGrid() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val progress = RecoveryProgressDialog(activity.get(), 4)
        try {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            fun message(): String {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                return dialog.findViewById<TextView>(android.R.id.message).text.toString()
            }
            assertFalse(message().contains("0 / 16"))
            progress.priorityProgress(3, 16)
            assertTrue(message().contains("3 / 16"))
            progress.priorityProgress(0, 0)
            assertFalse(message().contains(" / 16"))
            assertFalse(message().contains("0 / 0"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            progress.phase("Trials completed")
            assertFalse(message().contains("Trials completed"))
        } finally {
            progress.close()
            activity.pause().stop().destroy()
        }
    }

}
