package io.github.earthdiver.qrrepair.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import io.github.earthdiver.qrrepair.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The crop editor starts around the symbol, and margin added beyond it does not lower the analysis resolution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolInitialCropTest {
    private val activity get() = Robolectric.buildActivity(Activity::class.java).setup().get().apply { setTheme(R.style.AppTheme) }

    /** A 25-module pattern with Finder-like corners at [unit] pixels per module, [margin] pixels from each edge. */
    private fun symbolImage(unit: Int, margin: Int): Bitmap {
        val n = 25
        val size = n * unit + 2 * margin
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val canvas = Canvas(this)
            val black = Paint().apply { color = Color.BLACK }
            for (y in 0 until n) for (x in 0 until n) {
                val finder = listOf(0 to 0, n - 7 to 0, 0 to n - 7).any { (sx, sy) ->
                    val dx = x - sx; val dy = y - sy
                    dx in 0..6 && dy in 0..6 && (dx == 0 || dy == 0 || dx == 6 || dy == 6 || (dx in 2..4 && dy in 2..4))
                }
                val inFinderArea = listOf(0 to 0, n - 7 to 0, 0 to n - 7).any { (sx, sy) -> x - sx in -1..7 && y - sy in -1..7 }
                if (finder || (!inFinderArea && (x * 17 + y * 11 + x * y) % 7 < 3))
                    canvas.drawRect((margin + x * unit).toFloat(), (margin + y * unit).toFloat(),
                        (margin + (x + 1) * unit).toFloat(), (margin + (y + 1) * unit).toFloat(), black)
            }
        }
    }

    @Test fun fractionalCropBoundsAndScaledDimensionsUseAndroidPixelRounding() {
        val source = Bitmap.createBitmap(2400, 1597, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val editor = ImageAdjustmentEditor(activity, source, onCancel = {}, onApply = {})
        try {
            editor.createCrop().let { assertEquals(1800, it.width); assertEquals(1198, it.height); it.recycle() }
            editor.preview.restoreCrop(RectF(10.8f, 20.2f, 111.2f, 221.1f))
            editor.createCrop().let { assertEquals(76, it.width); assertEquals(151, it.height); it.recycle() }
        } finally { editor.dispose(); source.recycle() }
    }

    @Test fun cropEditorStartsAroundTheSymbolWithAQuietZone() {
        val unit = 8; val margin = 300
        val source = symbolImage(unit, margin)
        val editor = ImageAdjustmentEditor(activity, source, onCancel = {}, onApply = {})
        try {
            val crop = editor.preview.cropRect()
            val side = 25f * unit
            val quiet = side * 4 / 21
            assertTrue("$crop", crop.left <= margin - quiet + 2 && crop.top <= margin - quiet + 2)
            assertTrue("$crop", crop.right >= margin + side + quiet - 2 && crop.bottom >= margin + side + quiet - 2)
            assertTrue("$crop", crop.left >= margin - side * .3f && crop.right <= margin + side * 1.3f)
            assertEquals(crop, editor.preview.initialCropRect())
        } finally { editor.dispose(); source.recycle() }
    }

    @Test fun cropEditorStartsWithTheWholeImageWhenTheSymbolFillsIt() {
        val source = symbolImage(8, 4)
        val editor = ImageAdjustmentEditor(activity, source, onCancel = {}, onApply = {})
        try {
            assertEquals(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), editor.preview.cropRect())
        } finally { editor.dispose(); source.recycle() }
    }

    @Test fun widerCropsKeepTheInitialCropResolutionAndOnlyWiderSelectionsAreReduced() {
        // A symbol at 80 px per module whose initial crop is wider than the analysis size.
        val unit = 80; val margin = 1100
        val source = symbolImage(unit, margin)
        val editor = ImageAdjustmentEditor(activity, source, onCancel = {}, onApply = {})
        try {
            val initial = editor.preview.initialCropRect()
            val basis = maxOf(initial.width(), initial.height())
            assertTrue("$initial", basis > BitmapUtils.MAX_PREPARED_DIMENSION)
            val expectedScale = BitmapUtils.MAX_PREPARED_DIMENSION / basis
            fun cropWith(rect: RectF): Bitmap { editor.preview.restoreCrop(rect); return editor.createCrop() }

            // The initial crop is reduced to the analysis size.
            cropWith(initial).let { assertEquals(BitmapUtils.MAX_PREPARED_DIMENSION.toFloat(), maxOf(it.width, it.height).toFloat(), 1f); it.recycle() }
            // A narrower selection keeps the scale of the initial crop.
            val narrow = RectF(initial.left + 200, initial.top + 200, initial.right - 200, initial.bottom - 200)
            cropWith(narrow).let { assertEquals(narrow.width() * expectedScale, it.width.toFloat(), 1.5f); it.recycle() }
            // A wider selection is the new basis.
            val whole = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
            cropWith(whole).let { assertEquals(BitmapUtils.MAX_PREPARED_DIMENSION, maxOf(it.width, it.height)); it.recycle() }
        } finally { editor.dispose(); source.recycle() }
    }

    @Test fun cropMarginDoesNotChangeTheAnalysisImageOfTheInitialCrop() {
        // A symbol whose initial crop exceeds the analysis size, with a narrow and a wide margin.
        val unit = 60
        val sizes = listOf(600, 1100).map { margin ->
            val source = symbolImage(unit, margin)
            val editor = ImageAdjustmentEditor(activity, source, onCancel = {}, onApply = {})
            try {
                editor.createCrop().let { crop -> (crop.width to crop.height).also { crop.recycle() } }
            } finally { editor.dispose(); source.recycle() }
        }
        assertTrue("$sizes", sizes.all { maxOf(it.first, it.second) == BitmapUtils.MAX_PREPARED_DIMENSION })
        // The extent is located on a reduced copy, so its edges may differ by a few source pixels.
        val tolerance = BitmapUtils.MAX_PREPARED_DIMENSION * .005f
        assertEquals(sizes[0].first.toFloat(), sizes[1].first.toFloat(), tolerance)
        assertEquals(sizes[0].second.toFloat(), sizes[1].second.toFloat(), tolerance)
    }
}
