package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.core.ListDecodingField as F

/**
 * KV algebraic soft decoding with multiplicity shared equally among values compatible
 * with known bits. These weights express ambiguity, not measured channel probabilities.
 * Fully erased and very ambiguous bytes have zero multiplicity. No known-bit flips are
 * enumerated. Incorrect known bits can still be corrected by the resulting codewords.
 * Interpolation: https://arxiv.org/abs/1005.5734, section III (Koetter's algorithm).
 */
class KoetterVardyDecoder(private val length: Int, private val dataLength: Int) {
    init { require(dataLength in 2 until length && length <= 255) }
    data class Parameters(val multiplicity: Int, val listDegree: Int, val degreeBound: Int,
        val targetErrors: Int, val constraints: Int, val workingBytes: Long, val estimatedWork: Long)

    private fun ambiguity(mask: Int): Int = mask.countOneBits()
    private fun active(mask: Int) = ambiguity(mask) <= 4

    /** Smallest proportional assignment with a guaranteed score at the requested error count. */
    fun parameters(unknownMasks: IntArray, targetErrors: Int, workingLimitBytes: Long,
        maximumWork: Long = Long.MAX_VALUE, check: () -> Unit = {}): Parameters? {
        require(unknownMasks.size == length && unknownMasks.all { it in 0..255 } && targetErrors >= 0)
        val uncertain = unknownMasks.filter { ambiguity(it) in 1..4 }
        if (uncertain.isEmpty()) return null
        val quantum = 1 shl uncertain.maxOf(::ambiguity)
        val included = unknownMasks.filter(::active)
        val weight = dataLength - 1
        val sum = included.sumOf { quantum shr ambiguity(it) }
        val score = sum - quantum * targetErrors
        val quadratic = quantum.toLong() * sum
        val linear = quantum.toLong() * included.size
        val delta = score.toLong() * score - weight * quadratic
        if (score <= 0 || delta <= 0) return null
        val last = (weight * linear / delta + 1).toInt()
        fun monomials(degree: Int): Long {
            val ell = degree / weight
            return (ell + 1L) * (degree + 1) - weight * ell.toLong() * (ell + 1) / 2
        }
        for (scale in 1..last) {
            check()
            val constraints = (quadratic * scale * scale + linear * scale) / 2
            val boundLong = score.toLong() * scale - 1
            if (boundLong > Int.MAX_VALUE || constraints > Int.MAX_VALUE) return null
            val bound = boundLong.toInt()
            if (monomials(bound) <= constraints) continue
            var low = 0
            var high = bound
            while (low < high) {
                val mid = (low + high) / 2
                if (monomials(mid) > constraints) high = mid else low = mid + 1
            }
            val ell = low / weight
            val width = ell + 1L
            val polynomial = 32L + 4L * (low + 2L)
            val roots = listDecodingWork(2, dataLength + 2L, width, 32L + 4L * (low + dataLength.toLong() * ell + 2))
            val componentDegreesBytes = 32L + listDecodingWork(width, 32L + 4L * width)
            val bytes = listDecodingMemory(32_768L + length * 256L, listDecodingWork(2, width, width, polynomial),
                roots, componentDegreesBytes)
            val work = listDecodingWork(constraints, width, width, low + 1L)
            if (bytes > workingLimitBytes || work > maximumWork) return null
            return Parameters(quantum * scale, ell, low, targetErrors, constraints.toInt(), bytes, work)
        }
        error("No KV assignment despite a positive score margin")
    }

    fun decode(received: IntArray, unknownMasks: IntArray, p: Parameters, workingLimitBytes: Long,
        check: () -> Unit = {}, emit: (IntArray, Int) -> Unit) {
        require(received.size == length && received.all { it in 0..255 })
        require(p == parameters(unknownMasks, p.targetErrors, Long.MAX_VALUE, check = check))
        if (p.workingBytes > workingLimitBytes) throw ListDecodingWorkingMemoryException()
        check()
        val coordinates = ListDecodingCoordinates(length)
        val multiplicities = IntArray(length) { if (active(unknownMasks[it])) p.multiplicity shr ambiguity(unknownMasks[it]) else 0 }
        val q = interpolate(received, unknownMasks, multiplicities, coordinates, p, check)
        listDecodingRoots(q, dataLength, check) { coefficients ->
            val corrected = coordinates.codeword(coefficients)
            val score = corrected.indices.sumOf {
                if ((corrected[it] xor received[it]) and unknownMasks[it].inv() and 255 == 0) multiplicities[it] else 0
            }
            if (score > p.degreeBound) {
                check(ReedSolomonErasures().calcSyndromes(corrected, length - dataLength).all { it == 0 })
                emit(corrected, score)
            }
        }
    }

    private fun interpolate(received: IntArray, masks: IntArray, multiplicities: IntArray,
        coordinates: ListDecodingCoordinates, p: Parameters, check: () -> Unit): Array<IntArray> {
        val size = p.listDegree + 1
        val capacity = p.degreeBound + 2
        val rows = Array(size) { row -> Array(size) { y -> IntArray(capacity).also { if (row == y) it[0] = 1 } } }
        val enabled = BooleanArray(size) { true }
        val degrees = IntArray(size) { it * (dataLength - 1) }
        val leadingY = IntArray(size) { it }
        // Fixed-size metadata for the interpolation basis, independent of emitted candidates.
        val componentDegrees = Array(size) { row -> IntArray(size) { y -> if (row == y) 0 else -1 } }
        fun updateDegree(row: Int) {
            var best = -1
            for (j in 0 until size) {
                val x = componentDegrees[row][j]
                val degree = if (x < 0) -1 else x + j * (dataLength - 1)
                if (degree >= best && x >= 0) { best = degree; leadingY[row] = j }
            }
            degrees[row] = best
            // Koetter updates never decrease a basis element's leading term. Such a row
            // cannot participate in a solution within the chosen weighted-degree bound.
            if (best > p.degreeBound) enabled[row] = false
        }
        val discrepancies = IntArray(size)
        for (position in received.indices) {
            val m = multiplicities[position]
            if (m == 0) continue
            val x = coordinates.points[position]
            val xp = IntArray(capacity).also { it[0] = 1; for (i in 1 until it.size) it[i] = F.multiply(it[i - 1], x) }
            for (value in 0..255) {
                check()
                if ((value xor received[position]) and masks[position].inv() and 255 != 0) continue
                val y = F.multiply(value, coordinates.denominators[position])
                val yp = IntArray(size).also { it[0] = 1; for (i in 1 until it.size) it[i] = F.multiply(it[i - 1], y) }
                for (a in 0 until m) for (b in 0 until m - a) {
                    check()
                    discrepancies.fill(0)
                    for (row in 0 until size) if (enabled[row]) {
                        var delta = 0
                        for (j in b until size) if ((j and b) == b) {
                            var part = 0
                            for (i in a..componentDegrees[row][j]) if ((i and a) == a && rows[row][j][i] != 0) {
                                part = part xor F.multiply(rows[row][j][i], xp[i - a])
                            }
                            delta = delta xor F.multiply(part, yp[j - b])
                        }
                        discrepancies[row] = delta
                    }
                    var pivot = -1
                    for (row in 0 until size) if (enabled[row] && discrepancies[row] != 0 &&
                        (pivot < 0 || degrees[row] < degrees[pivot] ||
                            (degrees[row] == degrees[pivot] && leadingY[row] < leadingY[pivot]))) pivot = row
                    if (pivot < 0) continue
                    for (row in 0 until size) if (row != pivot && enabled[row] && discrepancies[row] != 0) {
                        check()
                        val factor = F.divide(discrepancies[row], discrepancies[pivot])
                        for (j in 0 until size) {
                            val pivotDegree = componentDegrees[pivot][j]
                            if (pivotDegree < 0) continue
                            for (i in 0..pivotDegree)
                                rows[row][j][i] = rows[row][j][i] xor F.multiply(factor, rows[pivot][j][i])
                            var degree = maxOf(componentDegrees[row][j], pivotDegree)
                            while (degree >= 0 && rows[row][j][degree] == 0) degree--
                            componentDegrees[row][j] = degree
                        }
                        updateDegree(row)
                    }
                    for (j in 0 until size) {
                        val poly = rows[pivot][j]
                        val last = componentDegrees[pivot][j]
                        if (last < 0) continue
                        for (i in minOf(poly.lastIndex, last + 1) downTo 1)
                            poly[i] = poly[i - 1] xor F.multiply(x, poly[i])
                        poly[0] = F.multiply(x, poly[0])
                        componentDegrees[pivot][j] = last + 1
                    }
                    // Multiplication by (X - x) raises the leading degree by exactly one
                    // and preserves its Y component; no coefficient scan is necessary.
                    degrees[pivot]++
                    if (degrees[pivot] > p.degreeBound) enabled[pivot] = false
                }
            }
        }
        val best = (0 until size).filter { enabled[it] }.minBy { degrees[it] }
        return Array(size) { rows[best][it].copyOf(componentDegrees[best][it] + 1) }
    }
}
