package io.github.earthdiver.qrrepair.ui

import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.PointD
import kotlin.math.ceil

/** Cache image-space polylines; screen scaling and magnification do not refit the warp. */
internal class MappedGridOutline {
    private data class Outline(val vertices: List<PointD>, val closed: Boolean)
    private var geometry: GridGeometry? = null
    private val cache = linkedMapOf<Outline, List<PointD>>()

    fun points(grid: GridGeometry, vertices: List<PointD>, closed: Boolean): List<PointD> {
        if (geometry != grid) { cache.clear(); geometry = grid }
        if (cache.size >= 512) cache.clear()
        return cache.getOrPut(Outline(vertices, closed)) {
            buildList {
                add(grid.moduleToImage(vertices.first().x, vertices.first().y))
                val segments = if (closed) vertices.size else vertices.size - 1
                repeat(segments) { index ->
                    val a = vertices[index]
                    val b = vertices[(index + 1) % vertices.size]
                    val steps = if (grid.warp == null) 1 else ceil(a.distanceTo(b)).toInt().coerceIn(1, 256)
                    for (step in 1..steps) {
                        val t = step.toDouble() / steps
                        add(grid.moduleToImage(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
                    }
                }
            }
        }
    }
}
