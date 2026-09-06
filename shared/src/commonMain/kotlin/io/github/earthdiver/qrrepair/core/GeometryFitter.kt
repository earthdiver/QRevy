package io.github.earthdiver.qrrepair.core

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FittedGeometry(
    val finderIndices: List<Int>,
    val roleToFinderIndex: Map<FinderRole, Int>,
    val geometry: GridGeometry,
)

object GeometryFitter {
    fun twoFinderHypotheses(finders: List<FinderCandidate>): List<FittedGeometry> {
        if (finders.size < 2) return emptyList()
        val out = mutableListOf<FittedGeometry>()
        for (i in 0 until finders.size - 1) for (j in i + 1 until finders.size) {
            val a = finders[i]; val b = finders[j]
            for (roleA in FinderRole.entries) for (roleB in FinderRole.entries) {
                if (roleA == roleB) continue
                for (v in likelyVersionsForPair(a, b, roleA, roleB)) {
                    try {
                        out += FittedGeometry(
                            listOf(i, j),
                            mapOf(roleA to i, roleB to j),
                            GridGeometry.fromTwoFinders(v, roleA, a, roleB, b),
                        )
                    } catch (_: Exception) {}
                }
            }
        }
        return out
    }

    fun threeFinderHypotheses(finders: List<FinderCandidate>): List<FittedGeometry> {
        if (finders.size < 3) return emptyList()
        val out = mutableListOf<FittedGeometry>()
        val n = minOf(finders.size, 4)
        for (i in 0 until n - 2) for (j in i + 1 until n - 1) for (k in j + 1 until n) {
            val indices = listOf(i, j, k)
            val fs = listOf(finders[i], finders[j], finders[k])
            for (perm in permutations(FinderRole.entries.toList())) {
                val mapping = perm.indices.associate { perm[it] to fs[it] }
                val roleToIndex = perm.indices.associate { perm[it] to indices[it] }
                for (v in likelyVersionsForTriple(mapping)) {
                    try {
                        out += FittedGeometry(indices, roleToIndex, GridGeometry.fromAffine(v, FinderRole.TOP_LEFT, mapping))
                    } catch (_: Exception) {}
                }
            }
        }
        return out
    }

    private fun likelyVersionsForPair(a: FinderCandidate, b: FinderCandidate, ra: FinderRole, rb: FinderRole): List<Int> {
        val observedModules = hypot(a.centerX - b.centerX, a.centerY - b.centerY) / ((a.moduleSize + b.moduleSize) / 2.0).coerceAtLeast(0.5)
        val diagonal = (ra == FinderRole.TOP_RIGHT && rb == FinderRole.BOTTOM_LEFT) ||
            (ra == FinderRole.BOTTOM_LEFT && rb == FinderRole.TOP_RIGHT)
        val axisModules = if (diagonal) observedModules / sqrt(2.0) else observedModules
        val estimatedDimension = axisModules + 7.0
        val estimatedVersion = ((estimatedDimension - 17.0) / 4.0).roundToInt().coerceIn(1, 40)
        return (-2..2).map { (estimatedVersion + it).coerceIn(1, 40) }.distinct()
    }

    private fun likelyVersionsForTriple(mapping: Map<FinderRole, FinderCandidate>): List<Int> {
        val tl = mapping.getValue(FinderRole.TOP_LEFT)
        val tr = mapping.getValue(FinderRole.TOP_RIGHT)
        val bl = mapping.getValue(FinderRole.BOTTOM_LEFT)
        val module = listOf(tl.moduleSize, tr.moduleSize, bl.moduleSize).sorted()[1].coerceAtLeast(0.5)
        val axisX = hypot(tl.centerX - tr.centerX, tl.centerY - tr.centerY) / module
        val axisY = hypot(tl.centerX - bl.centerX, tl.centerY - bl.centerY) / module
        val estimatedDimension = (axisX + axisY) / 2.0 + 7.0
        val v = ((estimatedDimension - 17.0) / 4.0).roundToInt().coerceIn(1, 40)
        return (-2..2).map { (v + it).coerceIn(1, 40) }.distinct()
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.flatMapIndexed { i, item -> permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(item) + it } }
    }
}
