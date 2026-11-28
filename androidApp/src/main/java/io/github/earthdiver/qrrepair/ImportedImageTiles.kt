package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Rect
import io.github.earthdiver.qrrepair.core.ImageResampling

internal const val IMPORT_REGION_PIXELS = 4 * 1024 * 1024

/** Full-width source bands bound memory and preserve JPEG chroma context at horizontal edges.
 * Band edges align with upright sample blocks, including mirrored odd image dimensions.
 */
internal fun decodeImportedTiles(width: Int, height: Int, orientation: Int,
    readRegion: (Rect) -> Bitmap): Bitmap {
    require(width in 1..65536 && height in 1..65536)
    val swapped = orientation in 5..8
    val w = if (swapped) height else width
    val h = if (swapped) width else height
    val sample = ImageResampling.importSample(w, h)
    // At the supported dimensions even one sample block across the full width fits.
    val band = maxOf(sample, IMPORT_REGION_PIXELS / width / sample * sample)
    val stepX = if (swapped) band else w
    val stepY = if (swapped) h else band
    val output = Bitmap.createBitmap(ImageResampling.sampledSize(w, sample),
        ImageResampling.sampledSize(h, sample), Bitmap.Config.ARGB_8888)
    fun raw(x: Int, y: Int): Pair<Int, Int> = when (orientation) {
        2 -> width - 1 - x to y
        3 -> width - 1 - x to height - 1 - y
        4 -> x to height - 1 - y
        5 -> y to x
        6 -> y to height - 1 - x
        7 -> width - 1 - y to height - 1 - x
        8 -> width - 1 - y to x
        else -> x to y
    }
    try {
        for (y in 0 until h step stepY) for (x in 0 until w step stepX) {
            val tw = minOf(stepX, w - x)
            val th = minOf(stepY, h - y)
            val a = raw(x, y)
            val b = raw(x + tw - 1, y + th - 1)
            val region = Rect(minOf(a.first, b.first), minOf(a.second, b.second),
                maxOf(a.first, b.first) + 1, maxOf(a.second, b.second) + 1)
            val tile = readRegion(region)
            try {
                if (tile.width != region.width() || tile.height != region.height()) throw ImageRegionDecodeException()
                val rawPixels = IntArray(tile.width * tile.height)
                tile.getPixels(rawPixels, 0, tile.width, 0, 0, tile.width, tile.height)
                // Read oriented rows from the buffer instead of allocating a rotated bitmap.
                val stride = when (orientation) {
                    2, 3 -> -1
                    5, 8 -> tile.width
                    6, 7 -> -tile.width
                    else -> 1
                }
                val pixels = ImageResampling.downsample(tw, th, sample) { row, buffer ->
                    val start = raw(x, y + row)
                    var index = (start.second - region.top) * tile.width + start.first - region.left
                    for (column in 0 until tw) {
                        buffer[column] = rawPixels[index]
                        index += stride
                    }
                }
                val ow = ImageResampling.sampledSize(tw, sample)
                output.setPixels(pixels, 0, ow, x / sample, y / sample, ow, ImageResampling.sampledSize(th, sample))
            } finally { tile.recycle() }
        }
        return output
    } catch (t: Throwable) {
        output.recycle()
        throw t
    }
}

/** A recoverable region-codec failure; callers may retry with bounded decoder sampling. */
internal class ImageRegionDecodeException(cause: Throwable? = null) : RuntimeException(
    localizedText("画像の領域をデコードできませんでした", "Could not decode an image region"), cause)

/** JPEG upsampling needs neighboring chroma samples at both horizontal crop edges. */
internal fun readImportedRegion(width: Int, region: Rect, read: (Rect) -> Bitmap?): Bitmap {
    val padded = Rect(maxOf(0, region.left - 16), region.top, minOf(width, region.right + 16), region.bottom)
    val expanded = try { read(padded) } catch (error: IllegalArgumentException) {
        throw ImageRegionDecodeException(error)
    } ?: throw ImageRegionDecodeException()
    var retained = false
    try {
        if (expanded.width != padded.width() || expanded.height != padded.height()) throw ImageRegionDecodeException()
        val tile = Bitmap.createBitmap(expanded, region.left - padded.left, 0, region.width(), region.height())
        retained = tile === expanded
        return tile
    } finally { if (!retained) expanded.recycle() }
}
