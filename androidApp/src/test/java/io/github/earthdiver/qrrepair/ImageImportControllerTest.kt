package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageImportControllerTest {
    @Test fun preparationTransfersLiveImagesAndAdjustmentSettingsToRecipient() {
        val callbacks = ArrayDeque<() -> Unit>()
        var result: PreparedImageImport? = null
        val adjustment = ImageAdjustment(contrast = 20, brightness = 10)
        val input = bitmap()
        val controller = controller(callbacks, { 1 })
        controller.prepare(1, input, null, adjustment) { result = it }
        assertNull(result)
        while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
        val delivered = requireNotNull(result)
        assertFalse(delivered.source.bitmap.isRecycled)
        assertFalse(delivered.adjusted.bitmap.isRecycled)
        assertEquals(adjustment, delivered.settings.imageAdjustment)
        assertTrue(delivered.settings.automaticThresholds)
        delivered.source.bitmap.recycle()
        if (delivered.adjusted.bitmap !== delivered.source.bitmap) delivered.adjusted.bitmap.recycle()
    }

    @Test fun preparationSupersededWhileAwaitingUiReleasesSourceAndDoesNotPublish() {
        val callbacks = ArrayDeque<() -> Unit>()
        var generation = 1
        val input = bitmap()
        var delivered = false
        val controller = controller(callbacks, { generation })
        controller.prepare(1, input, null, ImageAdjustment()) { delivered = true }
        generation++
        while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
        assertFalse(delivered)
        assertTrue(input.isRecycled)
    }

    @Test fun obsoletePreparationStillDeletesCameraHandoff() {
        val callbacks = ArrayDeque<() -> Unit>()
        val input = bitmap()
        val capture = File.createTempFile("qr-capture-import-", ".png", RuntimeEnvironment.getApplication().cacheDir)
        try {
            controller(callbacks, { 2 }).prepare(1, input, capture, ImageAdjustment()) {
                fail("An obsolete import must not be delivered")
            }
            while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
            assertTrue(input.isRecycled)
            assertFalse(capture.exists())
        } finally { capture.delete() }
    }

    private fun controller(callbacks: ArrayDeque<() -> Unit>, generation: () -> Int) = ImageImportController(
        RuntimeEnvironment.getApplication().contentResolver,
        Executor { it.run() },
        { callbacks.addLast(it) },
        generation,
        { false },
        {},
        { throw AssertionError("Unexpected import failure", it) },
    )

    private fun bitmap() = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
    }
}
