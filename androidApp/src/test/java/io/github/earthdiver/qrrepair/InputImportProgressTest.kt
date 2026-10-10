package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.ui.BitmapUtils
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class InputImportProgressTest {
    @Test fun newInputCoversPreviousWorkUntilPreparationFinishes() {
        for (style in AppStyle.entries) for (input in listOf("image", "analysis", "text")) withActivity(style) { activity ->
            val previous = field<BitmapUtils.Prepared>(activity, "prepared")
            val settings = field<PreprocessSettings>(activity, "preprocessSettings")
            val release = blockWorker(activity)
            lateinit var progress: AlertDialog
            try {
                import(activity, input)
                progress = field(activity, "inputProgressDialog")
                assertTrue(progress.isShowing)
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, progress.window!!.attributes.width)
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, progress.window!!.attributes.height)
                assertEquals(255, Color.alpha((progress.window!!.decorView.background as ColorDrawable).color))
                assertSame(previous, field(activity, "prepared"))
                assertSame(settings, field(activity, "preprocessSettings"))
                invoke(activity, "showPreprocessDialog")
                invoke(activity, "showCellEditor")
                invoke(activity, "startAutoRecovery")
                assertSame(progress, ShadowDialog.getLatestDialog())
                assertNull(field<Any?>(activity, "recoveryProgressDialog"))
                assertFalse(shadowOf(progress).isCancelable)
            } finally { release.countDown() }
            drain(activity)
            assertFalse(progress.isShowing)
            assertNull(field<Any?>(activity, "inputProgressDialog"))
            if (input == "image") {
                // Cancelling the crop editor must leave the original work intact.
                field<AlertDialog>(activity, "importEditorDialog").cancel()
                assertSame(previous, field(activity, "prepared"))
                assertSame(settings, field(activity, "preprocessSettings"))
            } else {
                assertNotSame(previous, field(activity, "prepared"))
                assertNotNull(field<Any?>(activity, "preprocessEditor"))
            }
        }
    }

    @Test fun failedImportClosesProgressAndPreservesPreviousSettings() {
        for (input in listOf("image", "text")) withActivity(AppStyle.MODERN) { activity ->
            val previous = field<BitmapUtils.Prepared>(activity, "prepared")
            val settings = field<PreprocessSettings>(activity, "preprocessSettings")
            import(activity, input, invalid = true)
            val progress = field<AlertDialog>(activity, "inputProgressDialog")
            drain(activity)
            assertFalse(progress.isShowing)
            assertNull(field<Any?>(activity, "inputProgressDialog"))
            assertSame(previous, field(activity, "prepared"))
            assertSame(settings, field(activity, "preprocessSettings"))
            ShadowDialog.getLatestDialog()?.dismiss()
            invoke(activity, "showPreprocessDialog")
            assertNotNull(field<Any?>(activity, "preprocessEditor"))
        }
    }

    @Test fun cameraImportKeepsPreparationCoveredAndDeletesTheTemporaryCapture() {
        withActivity(AppStyle.MODERN) { activity ->
            val previous = field<BitmapUtils.Prepared>(activity, "prepared")
            val capture = File.createTempFile("qr-capture-test-", ".png", activity.cacheDir)
            try {
                capture.outputStream().use { output ->
                    bitmap().let { it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle() }
                }
                MainActivity::class.java.getDeclaredMethod("loadImage", Uri::class.java, File::class.java,
                    ImageAdjustment::class.java).apply { isAccessible = true }
                    .invoke(activity, Uri.fromFile(capture), capture, ImageAdjustment())
                val loading = field<AlertDialog>(activity, "inputProgressDialog")
                // Queue a barrier after decoding so analysis cannot finish before inspection.
                val release = blockWorker(activity)
                try {
                    shadowOf(Looper.getMainLooper()).idle()
                    val preparing = field<AlertDialog>(activity, "inputProgressDialog")
                    assertFalse(loading.isShowing)
                    assertTrue(preparing.isShowing)
                    assertSame(previous, field(activity, "prepared"))
                } finally { release.countDown() }
                drain(activity)
                assertNull(field<Any?>(activity, "inputProgressDialog"))
                assertNotSame(previous, field(activity, "prepared"))
                assertFalse(capture.exists())
            } finally { capture.delete() }
        }
    }

    @Test fun destroyingActivityDismissesImportProgress() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val release = blockWorker(activity)
        try {
            import(activity, "text")
            val progress = field<AlertDialog>(activity, "inputProgressDialog")
            controller.pause().stop().destroy()
            assertFalse(progress.isShowing)
            assertNull(field<Any?>(activity, "inputProgressDialog"))
        } finally { release.countDown() }
    }

    @Test fun obsoleteImportCannotDismissNewerProgressOrPublishAnError() {
        withActivity(AppStyle.MODERN) { activity ->
            val release = blockWorker(activity)
            val nextRelease = CountDownLatch(1)
            val nextStarted = CountDownLatch(1)
            try {
                import(activity, "text", invalid = true)
                val oldProgress = field<AlertDialog>(activity, "inputProgressDialog")
                field<ExecutorService>(activity, "executor").execute {
                    nextStarted.countDown()
                    nextRelease.await()
                }
                import(activity, "analysis")
                val progress = field<AlertDialog>(activity, "inputProgressDialog")
                assertFalse(oldProgress.isShowing)
                release.countDown()
                assertTrue(nextStarted.await(10, TimeUnit.SECONDS))
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(progress.isShowing)
                assertSame(progress, ShadowDialog.getLatestDialog())
                assertEquals("Preparing image for analysis…", field<TextView>(activity, "statusText").text.toString())
            } finally {
                release.countDown()
                nextRelease.countDown()
            }
            drain(activity)
            assertNull(field<Any?>(activity, "inputProgressDialog"))
        }
    }

    private fun withActivity(style: AppStyle, action: (MainActivity) -> Unit) {
        AppStylePreferences.save(RuntimeEnvironment.getApplication(), style)
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            ShadowDialog.getLatestDialog()?.dismiss()
            val activity = controller.get()
            MainActivity::class.java.getDeclaredField("prepared").apply { isAccessible = true }
                .set(activity, BitmapUtils.prepare(bitmap()))
            MainActivity::class.java.getDeclaredField("preprocessSettings").apply { isAccessible = true }
                .set(activity, PreprocessSettings(blackMax = 42, whiteMin = 199))
            invoke(activity, "showPreprocessDialog")
            action(activity)
        } finally { controller.pause().stop().destroy() }
    }

    private fun import(activity: MainActivity, input: String, invalid: Boolean = false) {
        if (input == "analysis") {
            MainActivity::class.java.getDeclaredMethod("acceptDecodedImage", Bitmap::class.java,
                File::class.java, ImageAdjustment::class.java).apply { isAccessible = true }
                .invoke(activity, bitmap(), null, ImageAdjustment())
            return
        }
        val bytes = when {
            invalid -> "invalid".toByteArray()
            input == "text" -> List(21) { "?".repeat(21) }.joinToString("\n").toByteArray()
            else -> ByteArrayOutputStream().also { output ->
                bitmap().let { it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle() }
            }.toByteArray()
        }
        val uri = Uri.parse("content://synthetic-fixture/import-$input")
        shadowOf(activity.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        if (input == "text") {
            MainActivity::class.java.getDeclaredMethod("loadTextMatrix", Uri::class.java)
                .apply { isAccessible = true }.invoke(activity, uri)
        } else {
            MainActivity::class.java.getDeclaredMethod("loadImage", Uri::class.java, File::class.java,
                ImageAdjustment::class.java).apply { isAccessible = true }
                .invoke(activity, uri, null, ImageAdjustment())
        }
    }

    private fun bitmap() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    private fun invoke(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun blockWorker(activity: MainActivity): CountDownLatch {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        field<ExecutorService>(activity, "executor").execute { started.countDown(); release.await() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        return release
    }
    private fun drain(activity: MainActivity) {
        repeat(3) {
            for (name in listOf("executor", "ioExecutor")) {
                field<ExecutorService>(activity, name).submit {}.get(30, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }
}
