package io.github.earthdiver.qrrepair

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityDataTest {
    @Test fun dataSamplingRunsOffTheUiThreadAndShowsTheSavedMapping() = withInput { activity, source, settings, executor ->
        val prior = ShadowAlertDialog.getLatestAlertDialog()
        val gate = CountDownLatch(1)
        executor.execute { check(gate.await(5, TimeUnit.SECONDS)) }
        try {
            showData(activity)
            assertSame(prior, ShadowAlertDialog.getLatestAlertDialog())
        } finally { gate.countDown() }
        executor.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNotSame(prior, dialog)
        val expected = requireNotNull(sampleSavedPreRecoveryData(source.luma, settings)).matrix
        val ordinary = requireNotNull(sampleSavedPreRecoveryData(source.luma, settings.copy(distortionCorrection = false))).matrix
        val expectedHex = CodewordHex.unmasked(CodewordExtractor.extract(expected, settings.version, 0))
        assertNotEquals(expectedHex, CodewordHex.unmasked(CodewordExtractor.extract(ordinary, settings.version, 0)))
        assertTrue(texts(dialog.window!!.decorView).any { it.contains(expectedHex) })
        dialog.dismiss()
    }

    @Test fun dataForSupersededSettingsDoesNotOpenADialog() = withInput { activity, _, settings, executor ->
        val prior = ShadowAlertDialog.getLatestAlertDialog()
        val gate = CountDownLatch(1)
        executor.execute { check(gate.await(5, TimeUnit.SECONDS)) }
        try {
            showData(activity)
            field("preprocessSettings").set(activity, settings.copy(distortionCorrection = false))
        } finally { gate.countDown() }
        executor.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(prior, ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test fun dataFixtureRestoresIncomingLanguageStateAfterSuccessAndFailure() =
        withTestAppLanguage(AppLanguage.JAPANESE) {
            val context = RuntimeEnvironment.getApplication()
            val preferences = context.getSharedPreferences("app_language", Context.MODE_PRIVATE)
            preferences.edit().remove("language").commit()
            Locale.setDefault(Locale.CANADA_FRENCH)
            Locale.setDefault(Locale.Category.DISPLAY, Locale.US)
            val configuration = Configuration(context.resources.configuration)
            for (fail in listOf(false, true)) {
                val failure = IllegalStateException("Fixture action failed")
                val runFixture = {
                    withInput { _, _, _, _ ->
                        assertEquals(AppLanguage.ENGLISH, AppLanguageManager.current)
                        assertEquals("en", context.resources.configuration.locales[0].language)
                        if (fail) throw failure
                    }
                }
                if (fail) assertSame(failure, assertThrows(IllegalStateException::class.java) { runFixture() })
                else runFixture()
                assertEquals(AppLanguage.JAPANESE, AppLanguageManager.current)
                assertEquals(Locale.CANADA_FRENCH, Locale.getDefault())
                assertEquals(Locale.US, Locale.getDefault(Locale.Category.DISPLAY))
                assertEquals(Locale.CANADA_FRENCH, Locale.getDefault(Locale.Category.FORMAT))
                assertFalse(preferences.contains("language"))
                assertEquals(configuration, context.resources.configuration)
            }
        }

    private fun withInput(action: (MainActivity, BitmapUtils.Prepared, PreprocessSettings, ExecutorService) -> Unit) = withTestAppLanguage(AppLanguage.ENGLISH) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val source = BitmapUtils.Prepared(bitmap, LumaImage(160, 160,
            IntArray(160 * 160) { if (it % 160 >= 83) 0 else 255 }))
        val quad = ImageQuad(PointD(20.0, 20.0), PointD(140.0, 20.0), PointD(140.0, 140.0), PointD(20.0, 140.0))
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 38.0, 38.0, 6.0, 0.0,
            quad, GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp(listOf(
                WarpLandmark(PointD(0.0, 0.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(1.0, 0.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(0.0, 1.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(1.0, 1.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(.5, .5), PointD(7.0, 0.0)),
            )))
        val settings = PreprocessSettings(manualQuad = quad, previewGeometry = geometry, distortionCorrection = true,
            polarity = PolarityMode.NORMAL, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0))
        val activity = controller.get()
        field("prepared").set(activity, source)
        field("preprocessSettings").set(activity, settings)
        try { action(activity, source, settings, field("executor").get(activity) as ExecutorService) }
        finally {
            controller.pause().stop().destroy()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun showData(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("showCodewordHex")
        .apply { isAccessible = true }.invoke(activity)
    private fun texts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(texts(view.getChildAt(index)))
    }
}
