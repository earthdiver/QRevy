package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class ImageResamplingTest {
    private fun gray(v: Int) = (255 shl 24) or (v shl 16) or (v shl 8) or v
    private fun rows(width: Int, pixels: IntArray): (Int, IntArray) -> Unit = { y, out ->
        pixels.copyInto(out, 0, y * width, (y + 1) * width)
    }

    @Test fun oddEdgesUseOnlyAvailablePixelsAndRoundHalvesUp() {
        val input = intArrayOf(0, 2, 10, 4, 8, 20, 30, 40, 50).map(::gray).toIntArray()
        val result = ImageResampling.downsample(3, 3, 2, rows(3, input))
        assertContentEquals(intArrayOf(4, 15, 35, 50).map(::gray).toIntArray(), result)
        assertEquals(2, ImageResampling.sampledSize(3, 2))
    }

    @Test fun importLimitsUsePowerOfTwoAndCeilingDimensions() {
        assertEquals(2, ImageResampling.importSample(2244, 2949))
        assertEquals(1475, ImageResampling.sampledSize(2949, 2))
        assertEquals(8, ImageResampling.importSample(10401, 1)) // 2600.25 still exceeds the limit.
    }

    @Test fun centerAlignedInterpolationClampsEdgesAndRoundsUp() {
        val input = intArrayOf(gray(0), gray(101))
        assertContentEquals(intArrayOf(0, 25, 76, 101).map(::gray).toIntArray(),
            ImageResampling.resize(2, 1, 4, 1, rows(2, input)))
        assertContentEquals(intArrayOf(gray(51)), ImageResampling.resize(2, 1, 1, 1, rows(2, input)))
    }

    @Test fun transparencyIsCompositedBeforeAveraging() {
        val pixels = intArrayOf(0x00ff0000, 0xff000000.toInt())
        assertContentEquals(intArrayOf(gray(128)), ImageResampling.downsample(2, 1, 2, rows(2, pixels)))
        assertEquals(0xffff7f7f.toInt(), ImageResampling.onWhite(0x80ff0000.toInt()))
    }

    @Test fun identityPreservesOpaquePixels() {
        val pixels = intArrayOf(0xff012345.toInt(), 0xffabcdef.toInt(), 0xfffedcba.toInt())
        assertContentEquals(pixels, ImageResampling.resize(3, 1, 3, 1, rows(3, pixels)))
        assertContentEquals(pixels, ImageResampling.downsample(3, 1, 1, rows(3, pixels)))
    }
}
