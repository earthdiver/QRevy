package io.github.earthdiver.qrrepair.core

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InitialThresholdEstimatorTest {
    @Test fun emptyValleyProducesNoIntegerUnknownBand() {
        val bitmap = bitmap(100, 20) { x, _ -> if (x < 50) Color.rgb(32, 32, 32) else Color.rgb(224, 224, 224) }
        val estimate = InitialThresholdEstimator.estimate(bitmap)
        assertNotNull(estimate)
        estimate!!
        assertEquals(estimate.blackMax + 1, estimate.whiteMin)
    }

    @Test fun exactIntermediateRgbPeakBecomesUnknownColorCluster() {
        val marker = Color.rgb(220, 40, 30) and 0xFFFFFF
        val bitmap = bitmap(120, 20) { x, _ ->
            when {
                x < 40 -> Color.BLACK
                x < 80 -> marker or (0xFF shl 24)
                else -> Color.WHITE
            }
        }
        val estimate = InitialThresholdEstimator.estimate(bitmap)
        assertNotNull(estimate)
        estimate!!
        assertTrue(estimate.unknownColorClusters.any { it.contains(marker) })
    }

    @Test fun separatedGrayMiddlePeakSelectsThreeClassModel() {
        val bitmap = bitmap(150, 20) { x, _ ->
            when { x < 50 -> Color.rgb(20, 20, 20); x < 100 -> Color.rgb(128, 128, 128); else -> Color.rgb(235, 235, 235) }
        }
        val estimate = InitialThresholdEstimator.estimate(bitmap)
        assertNotNull(estimate)
        assertTrue(estimate!!.usedThreeClassModel)
        assertTrue(estimate.blackMax < 128 && estimate.whiteMin > 128)
    }

    @Test fun singleToneCannotProduceReliableBlackAndWhitePeaks() {
        val bitmap = bitmap(40, 40) { _, _ -> Color.GRAY }
        assertNull(InitialThresholdEstimator.estimate(bitmap))
    }

    @Test fun separatedTonesRemainBlackUnknownAndWhiteWithAutomaticSettings() {
        // Independent synthetic stripes, including gray beside both black and white.
        val tones = intArrayOf(0, 128, 255)
        val source = bitmap(90, 30) { x, _ -> tones[x / 30].let { Color.rgb(it, it, it) } }
        val estimate = InitialThresholdEstimator.estimate(source)!!
        assertTrue(estimate.blackMax in 60..68 && estimate.whiteMin in 188..196)
        val image = LumaImage(90, 30, IntArray(90 * 30) { tones[it % 90 / 30] })
        val profile = SamplingProfile(PreprocessSettings(
            blackMax = estimate.blackMax, whiteMin = estimate.whiteMin,
            automaticThresholds = false,
        ), inverted = false)
        for (x in 0 until 90) {
            val expected = when (x / 30) {
                0 -> ModuleValue.ONE
                1 -> ModuleValue.ERASED
                else -> ModuleValue.ZERO
            }
            assertEquals("Classification at x=$x", expected,
                profile.classify(image, tones[x / 30].toDouble(), x.toDouble(), 15.0, 1.0))
        }
    }

    @Test fun emptyValleySeparatesNoisyBlackAndWhiteWithAutomaticSettings() {
        val source = bitmap(100, 30) { x, y ->
            val value = (if (x < 50) 30 else 220) + y % 5
            Color.rgb(value, value, value)
        }
        val estimate = InitialThresholdEstimator.estimate(source)!!
        val image = LumaImage(100, 30, IntArray(3000) { index ->
            (if (index % 100 < 50) 30 else 220) + index / 100 % 5
        })
        val profile = SamplingProfile(PreprocessSettings(
            blackMax = estimate.blackMax, whiteMin = estimate.whiteMin,
            automaticThresholds = false,
        ), inverted = false)
        assertEquals(estimate.blackMax + 1, estimate.whiteMin)
        for (x in 0 until 100) {
            assertEquals("Classification at x=$x", if (x < 50) ModuleValue.ONE else ModuleValue.ZERO,
                profile.classify(image, image.luma(x, 15).toDouble(), x.toDouble(), 15.0, 1.0))
        }
    }

    @Test fun continuousTonesLeaveAnUnknownBand() {
        val source = bitmap(256, 20) { x, _ -> Color.rgb(x, x, x) }
        val estimate = InitialThresholdEstimator.estimate(source)!!
        assertTrue(estimate.whiteMin > estimate.blackMax + 1)
    }

    private fun bitmap(width: Int, height: Int, pixel: (Int, Int) -> Int): Bitmap {
        val pixels = IntArray(width * height) { index -> pixel(index % width, index / width) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
