package io.github.earthdiver.qrrepair.core

import kotlin.math.abs

/** Resolve a periodic quiet-zone alias using surviving image structure, without decoding. */
internal object PeriodicityStructure {
    internal fun resolveQuietZoneAlias(
        image: LumaImage, inverse: LumaImage, normal: GridPeriodicityEstimate, inverted: GridPeriodicityEstimate,
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE
    ): InitialGridEstimate? {
        cancellation.check()
        // A four-module quiet zone adds eight modules: exactly two QR versions.
        if (abs(normal.version - inverted.version) != 2 ||
            abs(normal.modulePitch - inverted.modulePitch) > normal.modulePitch * .05) return null
        val small = if (normal.version < inverted.version) normal else inverted
        val large = if (normal.version < inverted.version) inverted else normal
        val last = (16 + 4 * small.version).toDouble()
        val map = Homography.fromCorrespondences(
            listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last)),
            small.moduleCenterQuad.asList())
        val expanded = listOf(map.map(-4.0, -4.0), map.map(last + 4, -4.0),
            map.map(last + 4, last + 4), map.map(-4.0, last + 4))
        if (expanded.zip(large.moduleCenterQuad.asList()).any { (a, b) ->
            a.distanceTo(b) > small.modulePitch * .25 }) return null
        val normalSupported = supported(image, normal, cancellation)
        val invertedSupported = supported(inverse, inverted, cancellation)
        if (normalSupported == invertedSupported) return null
        val selected = if (normalSupported) normal else inverted
        // One surviving Finder does not establish the logical orientation.
        return InitialGridEstimate(selected.version, selected.moduleCenterQuad, InitialGridSource.PERIODICITY)
    }

    fun hasTimingSupport(image: LumaImage, estimate: InitialGridEstimate): Boolean {
        val dimension = 17 + 4 * estimate.version
        val last = (dimension - 1).toDouble()
        val mapping = runCatching {
            Homography.fromCorrespondences(
                listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last)),
                estimate.moduleCenterQuad.asList())
        }.getOrNull() ?: return false
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            val supported = listOf(false, true).all { vertical ->
                var known = 0
                var matches = 0
                for (index in 8..dimension - 9) {
                    val column = if (vertical) 6 else index
                    val row = if (vertical) index else 6
                    val point = mapping.map(if (right) last - column else column.toDouble(),
                        if (bottom) last - row else row.toDouble())
                    val value = image.sampleMean(point.x, point.y, 0.0) ?: continue
                    val black = when { value <= 64 -> true; value >= 192 -> false; else -> continue }
                    known++
                    if (black == (index % 2 == 0)) matches++
                }
                known >= maxOf(4, (dimension - 16) / 2) && matches.toDouble() / known >= .9
            }
            if (supported) return true
        }
        return false
    }

    private fun supported(image: LumaImage, estimate: GridPeriodicityEstimate, cancellation: RecoveryCancellation): Boolean {
        val n = 17 + 4 * estimate.version
        val last = (n - 1).toDouble()
        val map = Homography.fromCorrespondences(
            listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last)),
            estimate.moduleCenterQuad.asList())
        fun value(x: Int, y: Int): Boolean? {
            val p = map.map(x.toDouble(), y.toDouble())
            val v = image.sampleMean(p.x, p.y, 0.0) ?: return null
            return when { v <= 64 -> true; v >= 192 -> false; else -> null }
        }
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            cancellation.check()
            fun read(x: Int, y: Int) = value(if (right) n - 1 - x else x, if (bottom) n - 1 - y else y)
            var finderKnown = 0
            var finderCorrect = 0
            for (y in 0..6) for (x in 0..6) {
                val v = read(x, y) ?: continue
                finderKnown++
                if (v == (x == 0 || x == 6 || y == 0 || y == 6 || (x in 2..4 && y in 2..4))) finderCorrect++
            }
            // Allow occluded modules, while requiring most of the Finder and its separator.
            if (finderKnown < 40 || finderCorrect < finderKnown - 1) continue
            val separator = (0..7).map { read(7, it) } + (0..6).map { read(it, 7) }
            if (separator.count { it == false } < 9 || separator.any { it == true }) continue
            var known = 0
            var matches = 0
            for (i in 8..n - 9) for (v in listOf(read(i, 6), read(6, i))) if (v != null) {
                known++
                if (v == (i % 2 == 0)) matches++
            }
            if (known >= 4 && matches.toDouble() / known >= .9) return true
        }
        return false
    }
}
