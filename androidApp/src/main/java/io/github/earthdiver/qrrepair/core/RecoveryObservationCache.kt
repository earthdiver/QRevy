package io.github.earthdiver.qrrepair.core

import java.security.MessageDigest

/** Bounded, in-memory image observations; edits, classifications and decoder results are never cached. */
internal class RecoveryObservationCache(
    private val limitBytes: Long = 2L * 1024 * 1024,
    private val observe: (LumaImage, GridGeometry, Boolean, () -> Boolean) -> Array<ModuleObservation?> =
        GridThresholdEstimator::observe,
) {
    private data class ImageKey(val width: Int, val height: Int, val digest: List<Byte>)
    private data class GridKey(val geometry: GridGeometry, val inverted: Boolean)
    private class Entries {
        val values = LinkedHashMap<GridKey, Array<ModuleObservation?>>(16, .75f, true)
        var bytes = 0L
    }
    private var imageKey: ImageKey? = null
    private var entries = Entries()

    fun sampler(image: LumaImage, check: () -> Unit): (GridGeometry, SamplingProfile) -> Pair<QrMatrix, MatrixSampler.SamplingStats> {
        // Transformed images can be recreated between runs. Compare their complete luminance
        // content once per run; identity alone also misses mutations of LumaImage.pixels.
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(4096)
        var offset = 0
        while (offset < image.pixels.size) {
            check()
            val count = minOf(buffer.size / 4, image.pixels.size - offset)
            for (i in 0 until count) {
                val value = image.pixels[offset + i]
                for (b in 0..3) buffer[i * 4 + b] = (value ushr (b * 8)).toByte()
            }
            digest.update(buffer, 0, count * 4)
            offset += count
        }
        val key = ImageKey(image.width, image.height, digest.digest().toList())
        val current = synchronized(this) {
            if (key != imageKey) { imageKey = key; entries = Entries() }
            entries
        }
        return { geometry, profile ->
            check()
            val gridKey = GridKey(geometry, profile.inverted)
            val observations = synchronized(current) {
                current.values[gridKey] ?: observe(image, geometry, profile.inverted) { check(); false }.also { values ->
                    check() // Never retain partial/cancelled observations.
                    val bytes = cost(geometry)
                    if (bytes <= limitBytes) {
                        while (current.values.isNotEmpty() &&
                            (current.bytes + bytes > limitBytes || current.values.size >= 32)) {
                            val oldest = current.values.entries.iterator()
                            current.bytes -= cost(oldest.next().key.geometry)
                            oldest.remove()
                        }
                        current.values[gridKey] = values
                        current.bytes += bytes
                    }
                }
            }
            check()
            // Classify afresh, including automatic thresholds, color exclusions and image margins.
            MatrixSampler(image, profile).sampleObserved(geometry, profile, observations)
        }
    }

    private fun cost(geometry: GridGeometry): Long =
        512L + geometry.dimension.toLong() * geometry.dimension * 48L +
            (geometry.warp?.landmarks?.size ?: 0) * 128L
}
