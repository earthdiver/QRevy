package io.github.earthdiver.qrrepair

import android.os.Bundle
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityLifecycleTest {
    @Test
    fun failedBitmapReplacementPreservesOwnedSource() {
        val source = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val result = replaceOwnedBitmap(source) { throw OutOfMemoryError("simulated transform failure") }

        assertTrue(result.isFailure)
        assertFalse(source.isRecycled)
        source.recycle()
    }

    @Test
    fun successfulBitmapReplacementReleasesOldSource() {
        val source = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val replacement = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        val result = replaceOwnedBitmap(source) { replacement }

        assertTrue(result.isSuccess)
        assertTrue(source.isRecycled)
        assertFalse(replacement.isRecycled)
        replacement.recycle()
    }

    @Test
    fun startupCleanupPreservesRecentCameraHandoffAndDeletesStaleCapture() {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "camera-cleanup-${System.nanoTime()}").apply { mkdirs() }
        val now = 2L * CAMERA_CAPTURE_STALE_AFTER_MS
        val recent = File(cacheDir, "qr-capture-recent.jpg").apply {
            writeText("recent")
            setLastModified(now - CAMERA_CAPTURE_STALE_AFTER_MS + 1L)
        }
        val stale = File(cacheDir, "qr-capture-stale.jpg").apply {
            writeText("stale")
            setLastModified(now - CAMERA_CAPTURE_STALE_AFTER_MS)
        }
        val unrelated = File(cacheDir, "other.jpg").apply {
            writeText("unrelated")
            setLastModified(0L)
        }

        try {
            purgeStaleCameraCaptures(cacheDir, now)

            assertTrue(recent.exists())
            assertFalse(stale.exists())
            assertTrue(unrelated.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun savesOnlySessionIdentityForActivityRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val sessionId = "activity-recreation-session"
        MainActivity::class.java.getDeclaredField("sessionId").apply {
            isAccessible = true
            set(activity, sessionId)
        }
        val state = Bundle()

        controller.saveInstanceState(state)

        assertEquals(sessionId, state.getString("main.session_id"))
        controller.destroy()
    }
}
