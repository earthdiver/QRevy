package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.widget.TextView
import java.io.FileOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraCaptureCleanupTest {
    @Test
    fun recreatedCropScreenOwnsVisibleStatusWithoutCameraScreenInitialization() {
        val capture = File(RuntimeEnvironment.getApplication().cacheDir, "qr-capture-restored-status.jpg")
        FileOutputStream(capture).use { output ->
            assertTrue(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).run {
                try {
                    compress(Bitmap.CompressFormat.JPEG, 90, output)
                } finally {
                    recycle()
                }
            })
        }
        val state = Bundle().apply {
            putString("camera.capture_path", capture.absolutePath)
            putBoolean("camera.capture_handed_off", false)
        }
        val controller = Robolectric.buildActivity(CameraActivity::class.java).create(state).start().resume().visible()

        try {
            val cropStatus = controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)
                .let { root ->
                    generateSequence(root.getChildAt(0)) { null }.flatMap { view ->
                        fun descendants(current: android.view.View): Sequence<android.view.View> = sequence {
                            yield(current)
                            if (current is android.view.ViewGroup) {
                                for (index in 0 until current.childCount) yieldAll(descendants(current.getChildAt(index)))
                            }
                        }
                        descendants(view)
                    }
                }
                .filterIsInstance<TextView>()
                .first { it.text == controller.get().getString(R.string.crop_help) }
            assertTrue(cropStatus.isShown)
        } finally {
            controller.get().finish()
            controller.destroy()
            capture.delete()
        }
    }

    @Test
    fun failedCompressionPreservesOriginalCapture() {
        val target = File.createTempFile("qr-capture-original", ".jpg").apply { writeText("original") }

        try {
            replaceWithCompressedCapture(target) { output ->
                output.write("partial".toByteArray())
                false
            }
            fail("Expected compression failure")
        } catch (_: IOException) {
            assertEquals("original", target.readText())
            assertFalse(target.parentFile?.listFiles().orEmpty().any { it.name.startsWith("${target.name}.crop-") })
        } finally {
            target.delete()
        }
    }

    @Test
    fun successfulCompressionReplacesOriginalCapture() {
        val target = File.createTempFile("qr-capture-original", ".jpg").apply { writeText("original") }

        replaceWithCompressedCapture(target) { output ->
            output.write("replacement".toByteArray())
            true
        }

        assertEquals("replacement", target.readText())
        target.delete()
    }

    @Test
    fun finishingCanceledCameraDeletesPendingCapture() {
        val controller = Robolectric.buildActivity(CameraActivity::class.java).setup()
        val activity = controller.get()
        val capture = File(activity.cacheDir, "qr-capture-cleanup-test.jpg").apply { writeText("temporary") }
        setCapture(activity, capture, handedOff = false)

        activity.finish()
        controller.destroy()

        assertFalse(capture.exists())
    }

    @Test
    fun finishingAfterHandoffPreservesCaptureForMainActivity() {
        val controller = Robolectric.buildActivity(CameraActivity::class.java).setup()
        val activity = controller.get()
        val capture = File(activity.cacheDir, "qr-capture-handoff-test.jpg").apply { writeText("temporary") }
        setCapture(activity, capture, handedOff = true)

        activity.finish()
        controller.destroy()

        assertTrue(capture.exists())
        capture.delete()
    }

    @Test
    fun completedCropFromRecreatedActivityPreservesCaptureForReplacement() {
        val capture = File.createTempFile("qr-capture-recreated", ".jpg").apply { writeText("cropped") }

        cleanupCompletedCaptureForDestroyedActivity(capture, finishing = false)

        assertTrue(capture.exists())
        capture.delete()
    }

    @Test
    fun completedCropFromFinishedActivityDeletesCapture() {
        val capture = File.createTempFile("qr-capture-finished", ".jpg").apply { writeText("cropped") }

        cleanupCompletedCaptureForDestroyedActivity(capture, finishing = true)

        assertFalse(capture.exists())
    }

    @Test
    fun captureGateRejectsConcurrentAndStaleCallbacks() {
        val gate = CaptureRequestGate()

        assertTrue(gate.tryStart("first"))
        assertFalse(gate.tryStart("second"))
        assertFalse(gate.finish("second"))
        assertTrue(gate.finish("first"))
        assertTrue(gate.tryStart("second"))
        assertFalse(gate.finish("first"))
        assertTrue(gate.finish("second"))
    }

    @Test
    fun cameraDecodeSampleBoundsLargeCaptures() {
        assertEquals(1, boundedBitmapSampleSize(2_600, 1_950))
        assertEquals(2, boundedBitmapSampleSize(4_000, 3_000))
        assertEquals(4, boundedBitmapSampleSize(8_000, 6_000))
        assertTrue(8_000 / boundedBitmapSampleSize(8_000, 6_000) <= 2_600)
    }

    @Test
    fun fullFrameCropCreatesIndependentWorkerBitmap() {
        val source = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)

        val cropped = createIndependentCrop(source, RectF(0f, 0f, 20f, 10f)).getOrThrow()

        assertTrue(cropped !== source)
        assertEquals(20, cropped.width)
        assertEquals(10, cropped.height)
        cropped.recycle()
        source.recycle()
    }

    @Test
    fun cropCreationReportsBitmapAllocationFailure() {
        val source = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)

        val result = createIndependentCrop(source, RectF(0f, 0f, 20f, 10f)) { _, _, _, _, _ ->
            throw OutOfMemoryError("simulated allocation failure")
        }

        assertTrue(result.isFailure)
        source.recycle()
    }

    private fun setCapture(activity: CameraActivity, file: File, handedOff: Boolean) {
        CameraActivity::class.java.getDeclaredField("currentCaptureFile").apply {
            isAccessible = true
            set(activity, file)
        }
        CameraActivity::class.java.getDeclaredField("captureHandedOff").apply {
            isAccessible = true
            setBoolean(activity, handedOff)
        }
    }
}
