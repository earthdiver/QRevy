package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class ImageAdjustmentTest {
    @Test fun identityPreservesEveryChannelAndAlpha() {
        val neutral = ImageAdjustment()
        for (value in 0..255) {
            val pixel = (91 shl 24) or (value shl 16) or ((255 - value) shl 8) or value
            assertEquals(pixel, neutral.argb(pixel))
        }
    }

    @Test fun contrastSeparatesTonesAroundMidGrayAndBrightnessShiftsBoth() {
        val contrast = ImageAdjustment(contrast = 50)
        assertEquals(65, contrast.channel(96))
        assertEquals(193, contrast.channel(160))
        val bright = ImageAdjustment(brightness = 20)
        assertEquals(147, bright.channel(96))
        assertEquals(211, bright.channel(160))
    }

    @Test fun allSupportedAdjustmentsAreBoundedAndMonotonic() {
        for (contrast in -100..100 step 10) for (brightness in -100..100 step 10) {
            val normal = ImageAdjustment(contrast, brightness)
            val inverted = normal.copy(inverted = true)
            for (v in 0..255) {
                assertTrue(normal.channel(v) in 0..255)
                assertTrue(inverted.channel(v) in 0..255)
                assertTrue(kotlin.math.abs(normal.channel(v) + inverted.channel(v) - 255) <= 1)
                if (v > 0) {
                    assertTrue(normal.channel(v) >= normal.channel(v - 1))
                    assertTrue(inverted.channel(v) <= inverted.channel(v - 1))
                }
            }
        }
    }

    @Test fun invalidSettingsAreRejected() {
        for (value in listOf(Int.MIN_VALUE, -101, 101, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ImageAdjustment(contrast = value) }
            assertThrows(IllegalArgumentException::class.java) { ImageAdjustment(brightness = value) }
        }
    }
}
