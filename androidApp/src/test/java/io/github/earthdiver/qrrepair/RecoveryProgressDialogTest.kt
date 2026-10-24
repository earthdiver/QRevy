package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.ListDecodingBudget
import io.github.earthdiver.qrrepair.core.ListDecodingSliceLimitException
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

    @Test fun aDeferredListPassRestoresTheCurrentGridAndKeepsInterruptionVisible() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val progress = RecoveryProgressDialog(activity.get(), 4)
        var clock = 0L
        val budget = ListDecodingBudget(
            onProgress = progress::listDecodingProgress,
            onIdle = progress::listDecodingIdle,
            clockMillis = { clock },
        )
        try {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            fun message(): String {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                return dialog.findViewById<TextView>(android.R.id.message).text.toString()
            }
            val listLabel = localizedText("リスト復号による追加探索", "List-decoding fallback")
            progress.phase("Grid 1 / 2")
            try {
                budget.measureSlice(1000) {
                    assertTrue(message().contains(listLabel))
                    clock += 1000
                    budget.check()
                }
                fail("The first pass must defer when its allowance is exhausted")
            } catch (_: ListDecodingSliceLimitException) { }
            assertTrue(message().contains("Grid 1 / 2"))
            assertFalse(message().contains(listLabel))
            progress.priorityProgress(3, 16)
            assertTrue(message().contains("3 / 16"))
            budget.measure {
                assertTrue(message().contains(listLabel))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            assertTrue(message().contains(localizedText("中断処理中", "Interrupting")))
            assertFalse(message().contains(listLabel))
        } finally {
            progress.close()
            activity.pause().stop().destroy()
        }
    }

    @Test fun discoveriesUpdateDuringSearchAndStoppingWithoutLeakingIntoAnotherDialog() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val progress = RecoveryProgressDialog(activity.get(), 0)
        try {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            fun message(): String {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                return dialog.findViewById<TextView>(android.R.id.message).text.toString()
            }
            assertTrue(message().contains(localizedText("見つかった候補: 0件", "Candidates found: 0")))
            progress.candidatesFound(io.github.earthdiver.qrrepair.core.RecoveryDiscoveryCount.Exact(3))
            assertTrue(message().contains(localizedText("見つかった候補: 3件", "Candidates found: 3")))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            progress.candidatesFound(io.github.earthdiver.qrrepair.core.RecoveryDiscoveryCount.AtLeast(4))
            assertTrue(message().contains(localizedText("見つかった候補: 4件以上", "Candidates found: at least 4")))
            progress.close()
            val next = RecoveryProgressDialog(activity.get(), 0)
            try {
                progress.candidatesFound(io.github.earthdiver.qrrepair.core.RecoveryDiscoveryCount.Exact(99))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                val nextDialog = ShadowDialog.getLatestDialog() as AlertDialog
                val text = nextDialog.findViewById<TextView>(android.R.id.message).text.toString()
                assertTrue(text.contains(localizedText("見つかった候補: 0件", "Candidates found: 0")))
                assertFalse(text.contains("99"))
            } finally { next.close() }
        } finally {
            progress.close()
            activity.pause().stop().destroy()
        }
    }

}
