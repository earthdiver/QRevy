package io.github.earthdiver.qrrepair.core

import kotlin.math.pow
import kotlin.math.roundToInt

/** Reproducible image tones, independent of module decisions and decoder polarity. */
data class ImageAdjustment(
    val contrast: Int = 0,
    val brightness: Int = 0,
    val inverted: Boolean = false,
) {
    init {
        require(contrast in -100..100)
        require(brightness in -100..100)
    }

    val isIdentity: Boolean get() = contrast == 0 && brightness == 0 && !inverted
    private val gain: Double = 2.0.pow(contrast / 50.0)
    val scale: Float = (if (inverted) -gain else gain).toFloat()
    val offset: Float = run {
        val shift = 127.5 * (1.0 - gain) + brightness * 2.55
        (if (inverted) 255.0 - shift else shift).toFloat()
    }

    fun channel(value: Int): Int = (value * scale + offset).roundToInt().coerceIn(0, 255)

    fun argb(pixel: Int): Int = (pixel and -0x1000000) or
        (channel((pixel ushr 16) and 255) shl 16) or
        (channel((pixel ushr 8) and 255) shl 8) or channel(pixel and 255)
}
