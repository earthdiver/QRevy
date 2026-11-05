package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import io.github.earthdiver.qrrepair.createIndependentCrop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class CropOverlayViewTest {
    @Test fun smallAndNarrowImagesKeepUsableCropsWhenEveryCornerIsDragged() {
        val sizes = listOf(1, 21, 29, 32, 34, 35, 38, 40)
        val dimensions = sizes.map { it to it } + listOf(1 to 80, 80 to 1, 21 to 80, 80 to 21)
        for ((width, height) in dimensions) withPreview(width, height) { view, source ->
            for (corner in 0..3) for (outward in listOf(false, true)) {
                view.setBitmap(source)
                val before = view.cropRect()
                val left = corner % 2 == 0
                val top = corner < 2
                drag(view, source,
                    if (left) before.left else before.right,
                    if (top) before.top else before.bottom,
                    if (left == outward) 0f else width.toFloat(),
                    if (top == outward) 0f else height.toFloat())
                val after = view.cropRect()
                assertValidCrop(after, width, height)
                assertTrue("Width must not shrink below the existing small selection",
                    after.width() + .0001f >= minOf(32f, before.width()))
                assertTrue("Height must not shrink below the existing small selection",
                    after.height() + .0001f >= minOf(32f, before.height()))
                val cropped = createIndependentCrop(source, after).getOrThrow()
                try {
                    assertTrue(cropped.width in 1..width)
                    assertTrue(cropped.height in 1..height)
                    assertFalse(source.isRecycled)
                } finally { cropped.recycle() }
            }
        }
    }

    @Test fun restoredSmallSelectionCanExpandWithoutMovingTheOppositeCorner() {
        withPreview(100, 100) { view, source ->
            val before = RectF(1f, 2f, 12f, 14f)
            view.restoreCrop(before)
            drag(view, source, before.left, before.top, 0f, 0f)
            assertEquals(RectF(0f, 0f, 12f, 14f), view.cropRect())
            drag(view, source, 12f, 14f, 100f, 100f)
            assertEquals(RectF(0f, 0f, 100f, 100f), view.cropRect())
        }
    }

    @Test fun ordinaryImagesRetainTheMinimumCropSizeAndCanMoveToTheImageBoundary() {
        withPreview(200, 200) { view, source ->
            val before = view.cropRect()
            drag(view, source, before.left, before.top, 200f, 200f)
            val reduced = view.cropRect()
            assertEquals(32f, reduced.width(), .0001f)
            assertEquals(32f, reduced.height(), .0001f)
            assertEquals(before.right, reduced.right, 0f)
            assertEquals(before.bottom, reduced.bottom, 0f)
            drag(view, source, reduced.centerX(), reduced.centerY(), 0f, 0f)
            assertEquals(RectF(0f, 0f, 32f, 32f), view.cropRect())
        }
    }

    private fun assertValidCrop(crop: RectF, width: Int, height: Int) {
        assertTrue("Crop stays inside ${width}x$height: $crop",
            crop.left >= 0f && crop.top >= 0f && crop.right <= width && crop.bottom <= height)
        assertTrue("Crop remains nonempty: $crop", crop.width() > 0f && crop.height() > 0f)
    }

    private fun withPreview(width: Int, height: Int, block: (CropOverlayView, Bitmap) -> Unit) {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rendered = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        try {
            val view = CropOverlayView(RuntimeEnvironment.getApplication()).apply {
                setBitmap(source)
                measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
                layout(0, 0, 400, 400)
                draw(Canvas(rendered))
            }
            block(view, source)
        } finally {
            rendered.recycle()
            source.recycle()
        }
    }

    private fun drag(view: CropOverlayView, source: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float) {
        val scale = minOf(view.width.toFloat() / source.width, view.height.toFloat() / source.height)
        val offsetX = (view.width - source.width * scale) / 2f
        val offsetY = (view.height - source.height * scale) / 2f
        fun touch(action: Int, x: Float, y: Float, time: Long) {
            val event = MotionEvent.obtain(0L, time, action, offsetX + x * scale, offsetY + y * scale, 0)
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, x0, y0, 0L)
        touch(MotionEvent.ACTION_MOVE, x1, y1, 16L)
        touch(MotionEvent.ACTION_UP, x1, y1, 32L)
    }
}
