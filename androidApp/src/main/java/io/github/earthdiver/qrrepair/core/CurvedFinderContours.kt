package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Nested dark/light/dark components measured at bounded image scales, without payload evidence. */
internal class CurvedFinderContours(
    private val image: LumaImage,
    private val inverted: Boolean,
    private val stopped: () -> Boolean,
) {
    data class Observation(val module: PointD, val image: PointD)
    data class Seed(val base: GridGeometry, val points: List<Observation>)
    private data class Component(val dark: Boolean, val parent: Int, val hull: List<PointD>, val border: Boolean)
    private data class Scale(val image: LumaImage, val sx: Double, val sy: Double)
    private val contours by lazy {
        listOf(384, 640, 960).map { minOf(it, max(image.width, image.height)) }.distinct().mapNotNull { limit ->
            if (stopped()) null else {
                val scale = resize(limit)
                val found = nestedQuads(scale.image)
                found.map { q -> q.map { PointD((it.x + .5) * scale.sx - .5, (it.y + .5) * scale.sy - .5) } }
            }
        }
    }

    fun seeds(base: GridGeometry): List<Seed> {
        val n = base.dimension
        val last = (n - 1).toDouble()
        return contours.mapNotNull { quads ->
            if (stopped()) return@mapNotNull null
            val available = quads.toMutableList()
            val observations = mutableListOf<Observation>()
            for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val target = PointD(ox + 3.0, oy + 3.0)
                val ordered = available.mapNotNull { q ->
                    val logical = q.map { base.imageToModule(it.x, it.y) }
                    if (logical.any { !it.x.isFinite() || !it.y.isFinite() }) return@mapNotNull null
                    val center = PointD(logical.map { it.x }.average(), logical.map { it.y }.average())
                    val distance = center.distanceTo(target)
                    val width = logical.indices.map { logical[it].distanceTo(logical[(it + 1) % 4]) }.average()
                    if (distance > n * .24 || width !in 3.5..11.0) null else Triple(q, logical, distance)
                }.sortedBy { it.third }
                val selected = ordered.firstOrNull() ?: return@mapNotNull null
                // Competing nested squares near the same role do not establish an assignment.
                if (ordered.getOrNull(1)?.third?.let { it - selected.third < .75 } == true) return@mapNotNull null
                available.remove(selected.first)
                val edges = listOf(PointD(ox - .5, oy - .5), PointD(ox + 6.5, oy - .5),
                    PointD(ox + 6.5, oy + 6.5), PointD(ox - .5, oy + 6.5))
                val permutations = (0..3).flatMap { start -> listOf(1, -1).map { direction ->
                    (0..3).map { (start + direction * it + 8) % 4 }
                } }
                val order = permutations.minBy { p -> p.indices.sumOf { i ->
                    val d = selected.second[p[i]].distanceTo(edges[i]); d * d
                } }
                val h = try { Homography.fromCorrespondences(edges, order.map { selected.first[it] }) }
                    catch (_: IllegalArgumentException) { return@mapNotNull null }
                // Inset half a module: stored landmarks use the existing module-center domain.
                for (p in listOf(PointD(ox.toDouble(), oy.toDouble()), PointD(ox + 6.0, oy.toDouble()),
                    PointD(ox + 6.0, oy + 6.0), PointD(ox.toDouble(), oy + 6.0))) {
                    observations += Observation(p, h.map(p))
                }
            }
            val h = fitProjective(observations, last) ?: return@mapNotNull null
            try {
                val corners = listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last)).map(h::map)
                val fitted = base.copy(imageQuad = ImageQuad(corners[0], corners[1], corners[2], corners[3])).reanchored()
                Seed(fitted, observations)
            } catch (_: IllegalArgumentException) { null }
        }
    }

    private fun resize(limit: Int): Scale {
        val factor = minOf(1.0, limit.toDouble() / max(image.width, image.height))
        val w = max(1, (image.width * factor).roundToInt()); val h = max(1, (image.height * factor).roundToInt())
        val sx = image.width.toDouble() / w; val sy = image.height.toDouble() / h
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            if (stopped()) return Scale(LumaImage(1, 1, intArrayOf(255)), 1.0, 1.0)
            for (x in 0 until w) {
                val x0 = x * sx; val x1 = (x + 1) * sx; val y0 = y * sy; val y1 = (y + 1) * sy
                var sum = 0.0
                for (iy in floor(y0).toInt() until ceil(y1).toInt().coerceAtMost(image.height)) {
                    val wy = minOf(y1, iy + 1.0) - maxOf(y0, iy.toDouble())
                    for (ix in floor(x0).toInt() until ceil(x1).toInt().coerceAtMost(image.width)) {
                        val weight = wy * (minOf(x1, ix + 1.0) - maxOf(x0, ix.toDouble()))
                        sum += image.luma(ix, iy) * weight
                    }
                }
                val value = (sum / (sx * sy)).roundToInt().coerceIn(0, 255)
                pixels[y * w + x] = if (inverted) 255 - value else value
            }
        }
        return Scale(LumaImage(w, h, pixels), sx, sy)
    }

    private fun nestedQuads(input: LumaImage): List<List<PointD>> {
        val w = input.width; val h = input.height
        // Separable Gaussian, matching the experiment's 41-pixel adaptive window.
        val weights = DoubleArray(41) { exp(-((it - 20.0) * (it - 20.0)) / (2 * 6.5 * 6.5)) }
        val total = weights.sum(); weights.indices.forEach { weights[it] /= total }
        val horizontal = DoubleArray(w * h)
        for (y in 0 until h) {
            if (stopped()) return emptyList()
            for (x in 0 until w) for (k in -20..20) horizontal[y * w + x] +=
                input.luma((x + k).coerceIn(0, w - 1), y) * weights[k + 20]
        }
        val dark = BooleanArray(w * h)
        for (y in 0 until h) {
            if (stopped()) return emptyList()
            for (x in 0 until w) {
                var mean = 0.0
                for (k in -20..20) mean += horizontal[(y + k).coerceIn(0, h - 1) * w + x] * weights[k + 20]
                dark[y * w + x] = input.luma(x, y) <= mean.roundToInt() - 5
            }
        }
        val labels = IntArray(w * h) { -1 }; val queue = IntArray(w * h)
        val components = mutableListOf<Component>()
        for (start in labels.indices) {
            if (labels[start] >= 0) continue
            if (stopped()) return emptyList()
            val color = dark[start]; val label = components.size
            val parent = if (start >= w) labels[start - w] else -1
            var head = 0; var tail = 1; queue[0] = start; labels[start] = label
            val boundary = mutableListOf<PointD>(); var border = false
            while (head < tail) {
                if (head % 4096 == 0 && stopped()) return emptyList()
                val i = queue[head++]; val x = i % w; val y = i / w
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) border = true
                var edge = false
                for (j in 0..3) {
                    val nx = x + if (j == 0) -1 else if (j == 1) 1 else 0
                    val ny = y + if (j == 2) -1 else if (j == 3) 1 else 0
                    if (nx !in 0 until w || ny !in 0 until h) { edge = true; continue }
                    val ni = ny * w + nx
                    if (dark[ni] != color) edge = true
                    else if (labels[ni] < 0) { labels[ni] = label; queue[tail++] = ni }
                }
                if (edge) boundary += PointD(x.toDouble(), y.toDouble())
            }
            components += Component(color, parent, if (tail >= 9 && !border) hull(boundary) else emptyList(), border)
        }
        val children = components.indices.groupBy { components[it].parent }
        return components.indices.mapNotNull { i ->
            val c = components[i]
            if (!c.dark || c.border || c.hull.size < 4) return@mapNotNull null
            val area = area(c.hull)
            if (area < 40) return@mapNotNull null
            val nested = children[i].orEmpty().any { child ->
                val white = components[child]
                !white.dark && area(white.hull) / area in .15..0.85 && children[child].orEmpty().any { grand ->
                    components[grand].dark && area(components[grand].hull) / area in .03..0.5
                }
            }
            if (!nested) return@mapNotNull null
            val q = c.hull.toMutableList()
            while (q.size > 4) {
                val remove = q.indices.minBy { k -> abs(cross(q[(k + q.size - 1) % q.size], q[k], q[(k + 1) % q.size])) }
                q.removeAt(remove)
            }
            if (area(q) / area < .90) null else q
        }
    }

    private fun hull(points: List<PointD>): List<PointD> {
        val sorted = points.distinct().sortedWith(compareBy<PointD> { it.x }.thenBy { it.y })
        if (sorted.size < 3) return sorted
        fun half(input: List<PointD>): List<PointD> {
            val out = mutableListOf<PointD>()
            for (p in input) {
                while (out.size >= 2 && cross(out[out.size - 2], out.last(), p) <= 0) out.removeAt(out.lastIndex)
                out += p
            }
            return out.dropLast(1)
        }
        return half(sorted) + half(sorted.reversed())
    }
    private fun cross(a: PointD, b: PointD, c: PointD) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    private fun area(p: List<PointD>): Double = abs(p.indices.sumOf { i ->
        val a = p[i]; val b = p[(i + 1) % p.size]; a.x * b.y - a.y * b.x
    }) / 2

    /** Normalized least-squares projective seed from all twelve image observations. */
    private fun fitProjective(points: List<Observation>, last: Double): Homography? {
        val scale = max(image.width, image.height).toDouble()
        val normal = Array(8) { DoubleArray(9) }
        for (p in points) {
            val x = p.module.x / last; val y = p.module.y / last
            val u = p.image.x / scale; val v = p.image.y / scale
            for (r in listOf(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u),
                doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v))) {
                for (i in 0..7) for (j in 0..8) normal[i][j] += r[i] * r[j]
            }
        }
        for (col in 0..7) {
            val pivot = (col..7).maxBy { abs(normal[it][col]) }
            if (abs(normal[pivot][col]) < 1e-12) return null
            val row = normal[pivot]; normal[pivot] = normal[col]; normal[col] = row
            val divisor = row[col]; for (j in col..8) row[j] /= divisor
            for (i in 0..7) if (i != col) {
                val factor = normal[i][col]; for (j in col..8) normal[i][j] -= factor * row[j]
            }
        }
        val a = DoubleArray(8) { normal[it][8] }
        return Homography(a[0] * scale / last, a[1] * scale / last, a[2] * scale,
            a[3] * scale / last, a[4] * scale / last, a[5] * scale, a[6] / last, a[7] / last)
    }
}
