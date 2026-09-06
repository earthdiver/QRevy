package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version
import kotlin.math.abs
import kotlin.math.max

/** Searches QR alignment patterns near locations predicted by an affine/similarity grid. */
data class AlignmentCandidate(
    val moduleX: Int,
    val moduleY: Int,
    val imageX: Double,
    val imageY: Double,
    val mismatchRate: Double,
    val contrast: Double,
) {
    val score: Double get() = mismatchRate * 20.0 - contrast * 0.01
}

class AlignmentDetector(
    private val image: LumaImage,
    private val profile: SamplingProfile? = null,
) {
    fun findBest(geometry: GridGeometry, maxSearchModules: Double = 4.0): AlignmentCandidate? {
        if (geometry.version <= 1) return null
        val centers = Version.getVersionForNumber(geometry.version).alignmentPatternCenters
        if (centers.isEmpty()) return null
        val expected = buildList {
            for (mx in centers) for (my in centers) {
                val nearTopLeft = mx <= 8 && my <= 8
                val nearTopRight = mx >= geometry.dimension - 9 && my <= 8
                val nearBottomLeft = mx <= 8 && my >= geometry.dimension - 9
                if (!nearTopLeft && !nearTopRight && !nearBottomLeft) add(mx to my)
            }
        }
        // Farther points add more projective information.
        val prioritized = expected.sortedByDescending { (x, y) -> (x - 3) * (x - 3) + (y - 3) * (y - 3) }
        var best: AlignmentCandidate? = null
        for ((mx, my) in prioritized.take(8)) {
            val predicted = geometry.moduleToImage(mx.toDouble(), my.toDouble())
            val pitch = geometry.localModulePitch(mx.toDouble(), my.toDouble()).coerceAtLeast(1.0)
            val radius = pitch * maxSearchModules
            val step = max(0.5, pitch * 0.25)
            var oy = -radius
            while (oy <= radius) {
                var ox = -radius
                while (ox <= radius) {
                    val candidate = scoreTemplate(mx, my, predicted.x + ox, predicted.y + oy, pitch)
                    if (candidate != null && (best == null || candidate.score < best.score)) best = candidate
                    ox += step
                }
                oy += step
            }
        }
        return best?.takeIf { it.mismatchRate <= 0.28 && it.contrast >= 18.0 }
    }

    private fun scoreTemplate(mx: Int, my: Int, cx: Double, cy: Double, pitch: Double): AlignmentCandidate? {
        val samples = ArrayList<Pair<Boolean, Double>>(25)
        for (dy in -2..2) for (dx in -2..2) {
            val sampled = image.sampleMean(cx + dx * pitch, cy + dy * pitch, pitch * 0.12) ?: return null
            val lum = profile?.normalize(sampled) ?: sampled
            val r = max(abs(dx), abs(dy))
            val expectedBlack = r == 2 || r == 0
            samples += expectedBlack to lum
        }
        val black = samples.filter { it.first }.map { it.second }.sorted()
        val white = samples.filterNot { it.first }.map { it.second }.sorted()
        if (black.isEmpty() || white.isEmpty()) return null
        val b = black[black.size / 2]
        val w = white[white.size / 2]
        if (w <= b) return null
        val threshold = (b + w) / 2.0
        val mismatches = samples.count { (expectedBlack, lum) -> (lum < threshold) != expectedBlack }
        return AlignmentCandidate(mx, my, cx, cy, mismatches / 25.0, w - b)
    }
}
