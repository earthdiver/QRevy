package io.github.earthdiver.qrrepair.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectivePreviewGridTest {
    private class Lines(bitmap: Bitmap) : Canvas(bitmap) {
        val lines = mutableListOf<List<Float>>()
        var handles = 0
        override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
            lines += listOf(x1, y1, x2, y2)
        }
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) { handles++ }
    }

    @Test fun collapsedCornersKeepHandlesAvailableWithoutDrawingAnInvalidGrid() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val bitmap = Bitmap.createBitmap(480, 380, Bitmap.Config.ARGB_8888)
        try {
            val point = PointD(40.0, 40.0)
            val overlay = PreprocessOverlayView(controller.get()).apply {
                setBitmap(bitmap)
                setImageQuad(ImageQuad(point, point, point, point))
                layout(0, 0, 480, 380)
            }
            val canvas = Lines(bitmap)
            overlay.draw(canvas)
            assertTrue(canvas.lines.isEmpty())
            assertTrue(canvas.handles == 4)
        } finally {
            bitmap.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test fun drawnGridAndTimingLinesFollowTheSamplingHomography() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val bitmap = Bitmap.createBitmap(480, 380, Bitmap.Config.ARGB_8888)
        try {
            val trapezoid = listOf(PointD(40.0, 40.0), PointD(440.0, 40.0), PointD(340.0, 340.0), PointD(140.0, 340.0))
            val rectangle = listOf(PointD(40.0, 40.0), PointD(440.0, 40.0), PointD(440.0, 340.0), PointD(40.0, 340.0))
            for (points in listOf(rectangle, trapezoid, trapezoid.drop(1) + trapezoid.take(1), trapezoid.reversed())) {
                val quad = ImageQuad(points[0], points[1], points[2], points[3])
                val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 40.0, 40.0, 15.0, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE)
                val overlay = PreprocessOverlayView(controller.get()).apply {
                    setBitmap(bitmap)
                    setVersion(1)
                    setImageQuad(quad)
                    layout(0, 0, 480, 380)
                }
                val canvas = Lines(bitmap)
                overlay.draw(canvas)
                fun assertLine(x1: Double, y1: Double, x2: Double, y2: Double) {
                    val a = geometry.moduleToImage(x1, y1)
                    val b = geometry.moduleToImage(x2, y2)
                    val expected = listOf(a.x, a.y, b.x, b.y)
                    assertTrue("Missing projected line $expected", canvas.lines.any { line ->
                        expected.indices.all { abs(expected[it] - line[it]) < .002 }
                    })
                }
                for (i in 0..21) {
                    assertLine(i - .5, -.5, i - .5, 20.5)
                    assertLine(-.5, i - .5, 20.5, i - .5)
                }
                assertLine(0.0, 6.0, 20.0, 6.0)
                assertLine(6.0, 0.0, 6.0, 20.0)
            }
        } finally {
            bitmap.recycle()
            controller.pause().stop().destroy()
        }
    }
}
