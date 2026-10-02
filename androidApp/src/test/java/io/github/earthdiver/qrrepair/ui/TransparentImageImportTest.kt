package io.github.earthdiver.qrrepair.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import io.github.earthdiver.qrrepair.core.StandardQrDecoder
import io.github.earthdiver.qrrepair.core.UiMode
import io.github.earthdiver.qrrepair.session.RecoverySessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransparentImageImportTest {
    private val owned = mutableSetOf<Bitmap>()
    private fun own(bitmap: Bitmap): Bitmap = bitmap.also { owned.add(it) }
    private fun withBitmaps(block: () -> Unit) {
        try { block() } finally { owned.forEach { if (!it.isRecycled) it.recycle() } }
    }

    private fun qr(transparent: Boolean): Bitmap {
        val modules = QRCodeWriter().encode("TRANSPARENT QR INPUT", BarcodeFormat.QR_CODE, 232, 232)
        val pixels = IntArray(modules.width * modules.height) { i ->
            if (modules[i % modules.width, i / modules.width]) Color.BLACK
            else if (transparent) Color.TRANSPARENT else Color.WHITE
        }
        return own(Bitmap.createBitmap(pixels, modules.width, modules.height, Bitmap.Config.ARGB_8888))
    }

    private fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return StandardQrDecoder.decode(bitmap.width, bitmap.height, pixels)?.text
    }

    @Test fun transparentQrHasTheSamePixelsSamplingAndDecodeAsWhiteBackgroundQr() = withBitmaps {
        val transparent = qr(true)
        val opaque = qr(false)
        val prepared = BitmapUtils.prepare(transparent).also { own(it.bitmap) }
        val expected = BitmapUtils.prepare(opaque)
        assertTrue(expected.bitmap.sameAs(prepared.bitmap))
        assertArrayEquals(expected.luma.pixels, prepared.luma.pixels)
        assertArrayEquals(expected.luma.rgbPixels, prepared.luma.rgbPixels)
        assertEquals("TRANSPARENT QR INPUT", decode(prepared.bitmap))
        assertEquals(Color.TRANSPARENT, transparent.getPixel(0, 0))
        assertFalse(transparent.isRecycled)
        assertSame(opaque, expected.bitmap)
    }

    @Test fun resizedTransparentQrMatchesWhiteBackgroundQr() = withBitmaps {
        val transparent = BitmapUtils.prepare(qr(true), maxDimension = 116).also { own(it.bitmap) }
        val opaque = BitmapUtils.prepare(qr(false), maxDimension = 116).also { own(it.bitmap) }
        assertEquals(116, transparent.bitmap.width)
        assertTrue(opaque.bitmap.sameAs(transparent.bitmap))
        assertArrayEquals(opaque.luma.pixels, transparent.luma.pixels)
        assertEquals("TRANSPARENT QR INPUT", decode(transparent.bitmap))
    }

    @Test fun rotationAndTonesUseTheSameWhiteBackgroundForTransparentQr() = withBitmaps {
        val transparent = BitmapUtils.prepare(qr(true)).also { own(it.bitmap) }
        val opaque = BitmapUtils.prepare(qr(false))
        for (rotation in listOf(0f, 90f)) for (tones in listOf(
            ImageAdjustment(), ImageAdjustment(20, -10), ImageAdjustment(inverted = true),
        )) {
            val settings = PreprocessSettings(rotationDegrees = rotation, imageAdjustment = tones)
            val actual = BitmapUtils.forSettings(transparent, settings).also { own(it.bitmap) }
            val expected = BitmapUtils.forSettings(opaque, settings).also { own(it.bitmap) }
            assertTrue(expected.bitmap.sameAs(actual.bitmap))
            assertArrayEquals(expected.luma.pixels, actual.luma.pixels)
            assertArrayEquals(expected.luma.rgbPixels, actual.luma.rgbPixels)
            if (!tones.inverted) assertEquals("TRANSPARENT QR INPUT", decode(actual.bitmap))
        }
    }

    @Test fun editorCompositesPartialTransparencyBeforeTonesAndPreservesItsSource() = withBitmaps {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val original = own(Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)).apply {
            for (y in 0 until height) for (x in 0 until width) setPixel(x, y, when {
                x < 10 -> Color.TRANSPARENT
                x < 20 -> Color.argb(128, 0, 0, 0)
                else -> Color.BLACK
            })
        }
        val editor = ImageAdjustmentEditor(activity, original, onCancel = {}, onApply = {})
        try {
            editor.preview.cropEnabled = false
            editor.preview.restoreCrop(RectF(0f, 0f, 30f, 30f))
            val cropped = own(editor.createCrop())
            assertEquals(Color.WHITE, cropped.getPixel(5, 15))
            assertEquals(Color.rgb(127, 127, 127), cropped.getPixel(15, 15))
            assertEquals(Color.BLACK, cropped.getPixel(25, 15))
            val prepared = BitmapUtils.prepare(cropped)
            for (tones in listOf(ImageAdjustment(), ImageAdjustment(25, -10, true))) {
                editor.setAdjustment(tones)
                editor.preview.measure(View.MeasureSpec.makeMeasureSpec(90, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(90, View.MeasureSpec.EXACTLY))
                editor.preview.layout(0, 0, 90, 90)
                val rendered = own(Bitmap.createBitmap(90, 90, Bitmap.Config.ARGB_8888))
                editor.preview.draw(Canvas(rendered))
                val adjusted = BitmapUtils.adjust(prepared, tones).also { own(it.bitmap) }
                for (x in listOf(5, 15, 25)) {
                    val actual = rendered.getPixel(x * 3 + 1, 46)
                    val expected = adjusted.bitmap.getPixel(x, 15)
                    assertEquals(255, Color.alpha(actual))
                    for (channel in listOf(Color::red, Color::green, Color::blue)) {
                        assertTrue(kotlin.math.abs(channel(expected) - channel(actual)) <= 1)
                    }
                    assertEquals(Color.red(expected), adjusted.luma.luma(x, 15))
                }
            }
            assertEquals(Color.TRANSPARENT, original.getPixel(5, 15))
            assertEquals(128, Color.alpha(original.getPixel(15, 15)))
        } finally { editor.dispose() }
        assertFalse(original.isRecycled)
        activity.finish()
    }

    @Test fun editorReturningToZeroRotationRetainsWhiteBackgroundAndIndependentCrop() = withBitmaps {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val original = qr(true)
        val editor = ImageAdjustmentEditor(activity, original, onCancel = {}, onApply = {})
        try {
            val state = editor.saveState()
            state.putFloat("rotation", 90f)
            editor.restoreState(state)
            state.putFloat("rotation", 0f)
            editor.restoreState(state)
            editor.preview.restoreCrop(RectF(0f, 0f, original.width.toFloat(), original.height.toFloat()))
            val cropped = own(editor.createCrop())
            editor.dispose()
            assertEquals("TRANSPARENT QR INPUT", decode(cropped))
            assertEquals(Color.TRANSPARENT, original.getPixel(0, 0))
            assertFalse(original.isRecycled)
        } finally { editor.dispose() }
        activity.finish()
    }

    @Test fun verifiedTransparentAndNormalizedSavedSourcesRestoreTheSameAnalysis() = withBitmaps {
        val store = RecoverySessionStore(RuntimeEnvironment.getApplication())
        val original = qr(true)
        val normalized = BitmapUtils.prepare(original).also { own(it.bitmap) }
        val settings = PreprocessSettings(imageAdjustment = ImageAdjustment(20, -10))
        val expected = BitmapUtils.forSettings(normalized, settings).also { own(it.bitmap) }
        for (source in listOf(original, normalized.bitmap)) {
            val id = store.newId()
            try {
                store.saveSourceBitmap(id, source)
                store.save(id, UiMode.SIMPLE, listOf(RecoveryHistoryEntry(
                    kind = HistoryEventKind.IMPORT, label = "Synthetic transparent QR",
                    snapshot = RecoverySnapshot(null, null, null, -1, settings),
                )), 0)
                val restored = store.load(id)!!
                val bitmap = own(restored.sourceBitmap!!)
                assertTrue(source.sameAs(bitmap))
                val prepared = BitmapUtils.prepare(bitmap).also { own(it.bitmap) }
                val adjusted = BitmapUtils.forSettings(prepared,
                    restored.historyEntries.single().snapshot.preprocessSettings!!).also { own(it.bitmap) }
                assertTrue(expected.bitmap.sameAs(adjusted.bitmap))
                assertArrayEquals(expected.luma.pixels, adjusted.luma.pixels)
                assertEquals("TRANSPARENT QR INPUT", decode(adjusted.bitmap))
            } finally { store.delete(id) }
        }
    }
}
