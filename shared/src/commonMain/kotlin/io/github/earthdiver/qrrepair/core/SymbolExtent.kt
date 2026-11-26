package io.github.earthdiver.qrrepair.core

/**
 * Locates symbol content independently of how much surrounding margin a crop includes.
 *
 * Every pixel that stands out from the surround tone counts, including the gray
 * of unknown or erased modules, so a partly unknown symbol is not reduced to its known half. When the
 * outermost pixels are not a uniform surround, the content reaches the image edge and its extent is
 * the whole image.
 */
object SymbolExtent {
    /** Inclusive pixel bounds [left, top, right, bottom], ignoring isolated specks. */
    fun bounds(image: LumaImage): IntArray? {
        val border = surroundTone(image) ?: return intArrayOf(0, 0, image.width - 1, image.height - 1)
        val counted: (Int) -> Boolean = if (border >= LIGHT_BORDER) {
            { it < border - BACKGROUND_DIFFERENCE }
        } else {
            { it > border + BACKGROUND_DIFFERENCE }
        }
        val columns = IntArray(image.width); val rows = IntArray(image.height)
        var total = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (counted(image.luma(x, y))) {
            columns[x]++; rows[y]++; total++
        }
        if (total < MIN_CONTENT_PIXELS) return null
        val trim = (total * TRIM_FRACTION).toInt()
        fun low(counts: IntArray): Int { var sum = 0; for (i in counts.indices) { sum += counts[i]; if (sum > trim) return i }; return 0 }
        fun high(counts: IntArray): Int { var sum = 0; for (i in counts.indices.reversed()) { sum += counts[i]; if (sum > trim) return i }; return counts.lastIndex }
        return intArrayOf(low(columns), low(rows), high(columns), high(rows))
    }

    /**
     * Initial crop as fractions of the image [left, top, right, bottom]: the content bounds plus a
     * quiet-zone margin. Null when there is no uniform surround (including content that reaches the image
     * edge) or the result is nearly the whole image, in which case the whole image remains the initial crop.
     */
    fun initialCrop(image: LumaImage): DoubleArray? {
        surroundTone(image) ?: return null
        val b = bounds(image) ?: return null
        val side = maxOf(b[2] - b[0] + 1, b[3] - b[1] + 1)
        val margin = maxOf(1.0, side * INITIAL_CROP_MARGIN)
        val left = maxOf(0.0, b[0] - margin); val top = maxOf(0.0, b[1] - margin)
        val right = minOf(image.width.toDouble(), b[2] + 1 + margin); val bottom = minOf(image.height.toDouble(), b[3] + 1 + margin)
        if (right - left >= image.width * WHOLE_IMAGE_FRACTION && bottom - top >= image.height * WHOLE_IMAGE_FRACTION) return null
        return doubleArrayOf(left / image.width, top / image.height, right / image.width, bottom / image.height)
    }

    /**
     * Median tone of the outermost pixel ring when at least [UNIFORM_SURROUND] of it lies within
     * [BACKGROUND_DIFFERENCE] of that median; null when the ring crosses symbol content instead.
     */
    fun surroundTone(image: LumaImage): Int? {
        val ring = ArrayList<Int>(2 * (image.width + image.height))
        for (x in 0 until image.width) { ring += image.luma(x, 0); ring += image.luma(x, image.height - 1) }
        for (y in 0 until image.height) { ring += image.luma(0, y); ring += image.luma(image.width - 1, y) }
        ring.sort()
        val median = ring[ring.size / 2]
        val uniform = ring.count { kotlin.math.abs(it - median) <= BACKGROUND_DIFFERENCE }
        return if (uniform >= ring.size * UNIFORM_SURROUND) median else null
    }

    /** A border at least this light is read as a quiet zone. */
    const val LIGHT_BORDER = 128
    private const val BACKGROUND_DIFFERENCE = 40
    private const val UNIFORM_SURROUND = 0.9
    private const val MIN_CONTENT_PIXELS = 40
    private const val TRIM_FRACTION = 0.002
    /** Four modules of a version 1 symbol: 4 / 21 of its side. */
    private const val INITIAL_CROP_MARGIN = 0.2
    private const val WHOLE_IMAGE_FRACTION = 0.9
}
