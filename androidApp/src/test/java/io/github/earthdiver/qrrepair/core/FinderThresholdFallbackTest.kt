package io.github.earthdiver.qrrepair.core

import android.graphics.Bitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinderThresholdFallbackTest {
    private val code = Encoder.encode("Independent finder polarity comparison", ErrorCorrectionLevel.M,
        mapOf(EncodeHintType.QR_VERSION to 4)).matrix
    private val hint = PointD(45.0, 45.0)

    private fun image(damage: String, inverted: Boolean): LumaImage {
        val size = 246
        return LumaImage(size, size, IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val mx = (x - 24) / 6
            val my = (y - 24) / 6
            var value = if (x >= 24 && y >= 24 && mx in 0..32 && my in 0..32 &&
                code[mx, my].toInt() == 1) 20 else 235
            if (damage == "outer-erased" && x in 24..65 && y in 24..29) value = 128
            if (damage == "noisy") {
                val noise = ((index.toLong() * 1103515245 + 12345) ushr 16).toInt().and(31) - 15
                value = (value + noise).coerceIn(0, 255)
            }
            if (inverted) 255 - value else value
        })
    }

    private fun settings(source: LumaImage, shift: Double = 0.0): PreprocessSettings {
        val pixels = source.pixels.map { value ->
            (255 shl 24) or (value shl 16) or (value shl 8) or value
        }.toIntArray()
        val bitmap = Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
        val estimate = try { requireNotNull(InitialThresholdEstimator.estimate(bitmap)) } finally { bitmap.recycle() }
        return PreprocessSettings(
            version = 4, automaticThresholds = true, photographicSource = true,
            provisionalBlackMax = estimate.blackMax, provisionalWhiteMin = estimate.whiteMin,
            manualQuad = ImageQuad(PointD(27.0 + shift, 27.0), PointD(219.0, 27.0 + shift),
                PointD(219.0, 219.0), PointD(27.0, 219.0)),
        )
    }

    @Test fun finderPositionAndPolaritySurviveMisalignedGridThresholds() {
        for (damage in listOf("clean", "outer-erased", "noisy")) {
            for (shift in listOf(-12.0, 0.0, 12.0)) for (inverted in listOf(false, true)) {
                val label = "$damage, shift=$shift, inverted=$inverted"
                val source = image(damage, inverted)
                val found = refineFinder(source, settings(source, shift), hint)
                assertNotNull(label, found)
                assertEquals(label, if (inverted) FinderPolarity.INVERTED else FinderPolarity.NORMAL, found!!.polarity)
                assertEquals(label, hint.x, found.centerX, 3.0)
                assertEquals(label, hint.y, found.centerY, 3.0)
            }
        }
    }

    @Test fun manualThresholdsRemainAuthoritativeDespiteUsableProvisionalThresholds() {
        val source = image("clean", false)
        val manual = settings(source).copy(automaticThresholds = false, polarity = PolarityMode.NORMAL,
            blackMax = 1, whiteMin = 10)
        assertNull(refineFinder(source, manual, hint))
        assertEquals(FinderPolarity.NORMAL,
            refineFinder(source, manual.copy(automaticThresholds = true), hint)?.polarity)
    }

    @Test fun unassignedGridStillAllowsFinderAssignmentInEitherPolarity() {
        for (inverted in listOf(false, true)) {
            val source = image("clean", inverted)
            val found = refineFinder(source, settings(source).copy(logicalGridAssigned = false), hint)
            assertNotNull(found)
            assertEquals(if (inverted) FinderPolarity.INVERTED else FinderPolarity.NORMAL, found!!.polarity)
            assertEquals(hint.x, found.centerX, 3.0)
            assertEquals(hint.y, found.centerY, 3.0)
        }
    }
}
