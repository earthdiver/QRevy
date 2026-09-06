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

    private fun bitmap(width: Int, height: Int, pixel: (Int, Int) -> Int): Bitmap {
        val pixels = IntArray(width * height) { index -> pixel(index % width, index / width) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
