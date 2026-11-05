package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

internal data class PatternObservation(
    val x: Double, val y: Double, val weight: Double, val target: Double,
)

/** Only the in-symbol finder/separator and alignment footprints have known luminance here. */
internal class ProjectivePatternTemplate(
    private val dimension: Int,
    alignmentCenters: IntArray,
) {
    private data class Cell(val x: Int, val y: Int)
    private data class Probe(val cell: Cell, val dx: Int, val dy: Int, val value: Double)

    private val luminance = IntArray(dimension * dimension) { -1 }
    private val finders = mutableListOf<List<Cell>>()
    private val alignments = mutableListOf<List<Cell>>()

    init {
        fun patch(cx: Int, cy: Int, radius: Int, white: (Int) -> Boolean) = buildList {
            for (y in maxOf(0, cy - radius)..minOf(dimension - 1, cy + radius))
                for (x in maxOf(0, cx - radius)..minOf(dimension - 1, cx + radius)) {
                    val light = white(max(abs(x - cx), abs(y - cy)))
                    add(Cell(x, y))
                    luminance[y * dimension + x] = if (light) 1 else 0
                }
        }
        for ((x, y) in listOf(3 to 3, dimension - 4 to 3, 3 to dimension - 4))
            finders += patch(x, y, 4) { it == 2 || it == 4 }
        for (x in alignmentCenters) for (y in alignmentCenters) {
            if ((x == 6 && (y == 6 || y == dimension - 7)) || (x == dimension - 7 && y == 6)) continue
            alignments += patch(x, y, 2) { it == 1 }
        }
    }

    /** Build fixed observations once per scale. Missing samples cannot disappear as a trial moves. */
    fun observations(seed: GridGeometry, image: ProjectivePatternImage, sigma: Double): List<PatternObservation> {
        val kernel = cellWeights(sigma)
        val value = DoubleArray(3)
        val result = mutableListOf<PatternObservation>()
        for (family in listOf(finders, alignments)) for (cells in family) {
            val probes = buildList {
                for (cell in cells) for (dy in -1..1 step 2) for (dx in -1..1 step 2) {
                    val p = seed.moduleToImage(cell.x + dx * OFFSET, cell.y + dy * OFFSET)
                    if (image.sample(p.x, p.y, value)) add(Probe(cell, dx, dy, value[0]))
                }
            }
            if (probes.size < 8) continue
            val samples = probes.map { it.value }.sorted()
            val contrast = samples[(samples.lastIndex * .9).toInt()] - samples[(samples.lastIndex * .1).toInt()]
            val weight = ((contrast - .08) / .35).coerceIn(0.0, 1.0) / (4 * cells.size * family.size)
            if (weight == 0.0) continue
            for ((cell, dx, dy) in probes) {
                var support = 0.0
                var white = 0.0
                for (j in -1..1) for (i in -1..1) {
                    val x = cell.x + i
                    val y = cell.y + j
                    if (x !in 0 until dimension || y !in 0 until dimension) continue
                    val expected = luminance[y * dimension + x]
                    if (expected < 0) continue
                    val mass = kernel[1 + dx * i] * kernel[1 + dy * j]
                    support += mass
                    white += mass * expected
                }
                if (support >= .97) {
                    val x = 2 * (cell.x + dx * OFFSET) / (dimension - 1) - 1
                    val y = 2 * (cell.y + dy * OFFSET) / (dimension - 1) - 1
                    result += PatternObservation(x, y, weight, white / support)
                }
            }
        }
        val total = result.sumOf { it.weight }
        return result.map { it.copy(weight = it.weight / total) }
    }

    // Integrate a Gaussian over the left, central and right cells for a positive sub-cell offset.
    private fun cellWeights(sigma: Double): DoubleArray {
        val weights = DoubleArray(3) { i ->
            (0 until 32).sumOf { s ->
                val distance = (i - 1.5 + (s + .5) / 32 - OFFSET) / sigma
                exp(-.5 * distance * distance)
            }
        }
        val sum = weights.sum()
        return DoubleArray(3) { weights[it] / sum }
    }

    private companion object { const val OFFSET = .38 }
}
