package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import io.github.earthdiver.qrrepair.PreprocessDialogState
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceImageTransformTest {
    private val quad = ImageQuad(PointD(18.25, 15.5), PointD(124.75, 24.25),
        PointD(116.5, 111.75), PointD(25.0, 104.5))

    private fun source() = BitmapUtils.prepare(Bitmap.createBitmap(IntArray(144 * 128) { i ->
        Color.rgb((i * 37) % 256, (i * 13) % 256, (i / 144 * 17) % 256)
    }, 144, 128, Bitmap.Config.ARGB_8888))

    @Test fun equivalentAccumulatedAnglesProduceIdenticalPixelsAndResetRestoresTheSource() {
        val source = source()
        try {
            for (perspective in listOf(null, quad)) {
                val initial = PreprocessSettings(perspectiveCorrectionQuad = perspective)
                val state = PreprocessDialogState(initial)
                val original = BitmapUtils.forSettings(source, initial)
                state.rotateBy(10f)
                val intermediate = BitmapUtils.forSettings(source, state.imageSettings(1))
                if (intermediate.bitmap !== source.bitmap) intermediate.bitmap.recycle()
                state.rotateBy(20f)
                val successive = BitmapUtils.forSettings(source, state.imageSettings(1))
                val direct = BitmapUtils.forSettings(source, initial.copy(rotationDegrees = 30f))
                assertTrue(successive.bitmap.sameAs(direct.bitmap))
                assertArrayEquals(successive.luma.pixels, direct.luma.pixels)
                successive.bitmap.recycle(); direct.bitmap.recycle()
                state.rotateBy(-30f)
                val restored = BitmapUtils.forSettings(source, state.imageSettings(1))
                assertTrue(original.bitmap.sameAs(restored.bitmap))
                if (original.bitmap !== source.bitmap) original.bitmap.recycle()
                if (restored.bitmap !== source.bitmap) restored.bitmap.recycle()
            }
            assertFalse(source.bitmap.isRecycled)
        } finally { source.bitmap.recycle() }
    }

    @Test fun selectingTheSameSourceQuadBeforeOrAfterRotationProducesIdenticalImages() {
        val source = source()
        try {
            for (degrees in listOf(-90f, 17.3f, 90f, 179.9f)) {
                val rotated = PreprocessSettings(rotationDegrees = degrees)
                val selection = BitmapUtils.mapQuad(quad, BitmapUtils.imageTransform(source.bitmap, rotated).sourceToImage::map)
                val sourceSelection = BitmapUtils.sourceQuad(source.bitmap, rotated, selection)
                assertEquals(quad, sourceSelection)
                val first = BitmapUtils.forSettings(source, rotated.copy(perspectiveCorrectionQuad = quad))
                val second = BitmapUtils.forSettings(source, rotated.copy(perspectiveCorrectionQuad = sourceSelection))
                try { assertTrue(first.bitmap.sameAs(second.bitmap)) }
                finally { first.bitmap.recycle(); second.bitmap.recycle() }
            }
        } finally { source.bitmap.recycle() }
    }

    @Test fun combinedTransformSamplesTheSourceInsteadOfResamplingARectifiedBitmap() {
        val source = source()
        val settings = PreprocessSettings(perspectiveCorrectionQuad = quad, rotationDegrees = 17.3f)
        val direct = BitmapUtils.forSettings(source, settings)
        val rectified = BitmapUtils.forSettings(source, settings.copy(rotationDegrees = 0f))
        val repeated = BitmapUtils.rotate(rectified.bitmap, settings.rotationDegrees)
        try {
            assertEquals(direct.bitmap.width, repeated.bitmap.width)
            assertEquals(direct.bitmap.height, repeated.bitmap.height)
            assertFalse("Two interpolation passes must not be used for the canonical image", direct.bitmap.sameAs(repeated.bitmap))
            val transform = BitmapUtils.imageTransform(source.bitmap, settings)
            // Rotation corners outside the rectified canvas stay white even if the source has pixels there.
            assertEquals(Color.WHITE, direct.bitmap.getPixel(0, 0))
            quad.asList().forEach { point ->
                assertTrue(transform.sourceToImage.inverse().map(transform.sourceToImage.map(point)).distanceTo(point) < 1e-8)
            }
        } finally {
            listOf(source, direct, rectified, repeated).forEach { it.bitmap.recycle() }
        }
    }

    @Test fun sourceTransformBoundsTheFinalImageAndPreservesModuleCenters() {
        val source = Bitmap.createBitmap(1800, 1800, Bitmap.Config.ARGB_8888)
        try {
            val large = ImageQuad(PointD(10.0, 10.0), PointD(1790.0, 10.0), PointD(1790.0, 1790.0), PointD(10.0, 1790.0))
            val settings = PreprocessSettings(perspectiveCorrectionQuad = large, rotationDegrees = 45f)
            val transform = BitmapUtils.imageTransform(source, settings)
            assertTrue(transform.width <= 1800 && transform.height <= 1800)
            val displayed = BitmapUtils.mapQuad(large, transform.sourceToImage::map)
            val reset = BitmapUtils.gridAfterRotation(source, displayed, settings, 0f)
            BitmapUtils.rectifiedGrid(large, 1).asList().zip(reset.asList()).forEach { (expected, actual) ->
                assertTrue(expected.distanceTo(actual) < 1e-6)
            }
        } finally { source.recycle() }
    }

    @Test fun unchangedLegacyCorrectionsReproduceTheHistoricalRaster() {
        val source = source()
        val rotation = Matrix().apply { setRotate(17.3f) }
        val bounds = RectF(0f, 0f, source.bitmap.width.toFloat(), source.bitmap.height.toFloat()).also(rotation::mapRect)
        val rotated = Bitmap.createBitmap(kotlin.math.ceil(bounds.width()).toInt(), kotlin.math.ceil(bounds.height()).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(rotated).apply {
            drawColor(Color.WHITE)
            translate(-bounds.left, -bounds.top)
            drawBitmap(source.bitmap, rotation, null)
        }
        try {
            for (perspective in listOf(null, quad)) {
                val legacy = if (perspective == null) BitmapUtils.prepare(rotated)
                    else BitmapUtils.rectify(rotated, perspective, 1, quietModules = 1.0).prepared
                val restored = BitmapUtils.forSettings(source, PreprocessSettings(rotationDegrees = 17.3f,
                    perspectiveCorrectionQuad = perspective, legacyImageRotationDegrees = 17.3f))
                try { assertTrue(legacy.bitmap.sameAs(restored.bitmap)) }
                finally {
                    if (legacy.bitmap !== rotated) legacy.bitmap.recycle()
                    restored.bitmap.recycle()
                }
            }
            val edited = BitmapUtils.forSettings(source, PreprocessSettings(rotationDegrees = 30f, legacyImageRotationDegrees = 17.3f))
            val canonical = BitmapUtils.forSettings(source, PreprocessSettings(rotationDegrees = 30f))
            try { assertTrue(edited.bitmap.sameAs(canonical.bitmap)) }
            finally { edited.bitmap.recycle(); canonical.bitmap.recycle() }
        } finally { source.bitmap.recycle(); rotated.recycle() }
    }
}
