package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageAdjustmentPipelineTest {
    private fun source(): BitmapUtils.Prepared {
        val bitmap = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888)
        for (y in 0 until 80) for (x in 0 until 60) bitmap.setPixel(x, y, Color.rgb(40 + x, 70 + y, 130))
        return BitmapUtils.prepare(bitmap)
    }

    @Test fun previewPixelsAndSamplingLuminanceAgreeWhileMarkerColorsStayOriginal() {
        val source = source()
        val adjusted = BitmapUtils.adjust(source, ImageAdjustment(20, -10, true))
        for (y in 0 until 80) for (x in 0 until 60) {
            val color = adjusted.bitmap.getPixel(x, y)
            val expected = (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color) + 500) / 1000
            assertEquals(expected, adjusted.luma.luma(x, y))
            assertEquals(source.bitmap.getPixel(x, y) and 0xffffff, adjusted.luma.rgbPixels!![y * 60 + x])
        }
        assertEquals(Color.rgb(40, 70, 130), source.bitmap.getPixel(0, 0))
        assertSame(source, BitmapUtils.adjust(source, ImageAdjustment()))
    }

    @Test fun repeatedPreparationDoesNotAccumulateTonesAndResetReturnsOriginal() {
        val source = source()
        val settings = PreprocessSettings(imageAdjustment = ImageAdjustment(75, 30, true))
        val first = BitmapUtils.forSettings(source, settings)
        val again = BitmapUtils.forSettings(source, settings)
        assertTrue(first.bitmap.sameAs(again.bitmap))
        assertSame(source, BitmapUtils.forSettings(source, settings.copy(imageAdjustment = ImageAdjustment())))
        assertFalse(source.bitmap.isRecycled)
    }

    @Test fun geometryThenAdjustmentMatchesRestorationIncludingOriginalMarkerColors() {
        val source = source()
        val quad = ImageQuad(PointD(5.0, 5.0), PointD(65.0, 5.0), PointD(65.0, 50.0), PointD(5.0, 50.0))
        val settings = PreprocessSettings(rotationDegrees = 90f, perspectiveCorrectionQuad = quad,
            imageAdjustment = ImageAdjustment(20, -10, true))
        val rawGeometry = BitmapUtils.forSettings(source, settings.copy(imageAdjustment = ImageAdjustment()))
        val expected = BitmapUtils.adjust(rawGeometry, settings.imageAdjustment)
        val actual = BitmapUtils.forSettings(source, settings)
        assertTrue(expected.bitmap.sameAs(actual.bitmap))
        assertArrayEquals(expected.luma.pixels, actual.luma.pixels)
        assertArrayEquals(rawGeometry.luma.rgbPixels, actual.luma.rgbPixels)
        assertFalse(source.bitmap.isRecycled)
    }

}
