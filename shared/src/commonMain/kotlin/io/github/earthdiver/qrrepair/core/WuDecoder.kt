package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.core.ListDecodingField as F

/**
 * Wu's locator formulation specialized to one, two or three extra erroneous symbols.
 * Lambda' = Lambda * lambda + xB * b, lambda(0) = 1. The first extra symbol
 * uses constant/linear ratio grouping. Wider shells solve at most six coefficients
 * directly from root constraints. Neither path assigns unknown data bits.
 * Equations also handle infinity points without division by xB.
 * Reference: https://arxiv.org/abs/cs/0703105, section II.A and lemma 3.
 */
class WuDecoder(private val length: Int, private val dataLength: Int) {
    init { require(dataLength in 2 until length && length <= 255) }
    val workingBytes: Long get() = 32_768L + length * 512L

    /** Deterministic work estimate for selection, not a wall-clock guarantee. */
    fun estimatedWork(erasures: Int, additionalErrors: Int = ListDecodingLimit.DEFAULT): Long {
        require(additionalErrors in ListDecodingLimit.supported)
        val parity = length - dataLength - erasures
        var work = length.toLong() * (parity * parity + if (parity % 2 == 0) 1024 else 32) +
            if (parity % 2 == 0) 65_536L else 0L
        for (extra in 2..additionalErrors) {
            val width = 2 * (parity / 2 + extra) - parity
            var subsets = 1L
            for (i in 1..width) subsets = subsets * (length - erasures - i + 1) / i
            work = listDecodingMemory(work, listDecodingWork(subsets,
                width.toLong() * width * width + (length - erasures).toLong() * width))
        }
        return work
    }

    fun decode(received: IntArray, erased: IntArray, radius: Int, workingLimitBytes: Long,
        check: () -> Unit = {}, emit: (IntArray) -> Unit) {
        require(received.size == length && received.all { it in 0..255 })
        require(erased.distinct().size == erased.size && erased.all { it in received.indices })
        val parity = length - dataLength - erased.size
        require(parity >= 1 && radius - parity / 2 in ListDecodingLimit.supported && radius <= parity)
        if (workingBytes > workingLimitBytes) throw ListDecodingWorkingMemoryException()
        check()
        // Each shell covers exactly its distance. Smaller words need their lower-degree
        // representations, which may leave a rank-deficient system at a larger radius.
        if (radius > parity / 2 + 1) decode(received, erased, radius - 1, workingLimitBytes, check, emit)
        val rs = ReedSolomonErasures()
        val omitted = BooleanArray(length).also { flags -> erased.forEach { flags[it] = true } }
        val input = received.copyOf().also { values -> erased.forEach { values[it] = 0 } }
        var syndromes = rs.calcSyndromes(input, length - dataLength).drop(1).toIntArray()
        for (position in erased) {
            check()
            val x = F.power(2, length - 1 - position)
            syndromes = IntArray(syndromes.size - 1) { F.multiply(syndromes[it], x) xor syndromes[it + 1] }
        }
        var locator = intArrayOf(1)
        var auxiliary = intArrayOf(1)
        var locatorLength = 0
        var auxiliaryLength = 0
        for (r in syndromes.indices) {
            check()
            var discrepancy = syndromes[r]
            for (j in 1..minOf(r, locator.lastIndex)) discrepancy = discrepancy xor F.multiply(locator[j], syndromes[r - j])
            val next = locator.copyOf(maxOf(locator.size, auxiliary.size + 1))
            for (j in auxiliary.indices) next[j + 1] = next[j + 1] xor F.multiply(discrepancy, auxiliary[j])
            if (discrepancy != 0 && 2 * locatorLength <= r) {
                auxiliary = IntArray(locator.size) { F.divide(locator[it], discrepancy) }
                val oldLength = locatorLength
                locatorLength = auxiliaryLength + 1
                auxiliaryLength = oldLength
            } else {
                auxiliary = intArrayOf(0) + auxiliary
                auxiliaryLength++
            }
            locator = next.copyOf(next.indexOfLast { it != 0 } + 1)
        }
        if (locatorLength > radius) return
        val lambdaDegree = radius - locatorLength
        val bDegree = radius - auxiliaryLength - 1
        val positions = received.indices.filterNot { omitted[it] }
        val xs = IntArray(positions.size) { F.power(2, -(length - 1 - positions[it])) }
        val a = IntArray(xs.size) { F.evaluate(locator, xs[it]) }
        val b = IntArray(xs.size) { F.multiply(xs[it], F.evaluate(auxiliary, xs[it])) }
        fun offer(roots: IntArray) {
            check()
            if (roots.size > radius || roots.size + erased.size > length - dataLength) return
            // With all proposed errors erased, ordinary RS computes magnitudes and verifies syndromes.
            val corrected = try { rs.decode(received, length - dataLength, erased + roots).corrected }
                catch (_: ReedSolomonErasures.DecodeException) { return }
            val errors = positions.count { corrected[it] != received[it] }
            if (if (radius > parity / 2 + 1) errors == radius else errors <= radius) emit(corrected)
        }
        if (bDegree < 0) {
            val roots = positions.indices.filter { a[it] == 0 }.map { positions[it] }.toIntArray()
            if (roots.size == locatorLength) offer(roots)
            return
        }
        if (radius > parity / 2 + 1) {
            fitRootConstraints(xs, a, b, lambdaDegree, bDegree, radius, check) { roots ->
                offer(IntArray(roots.size) { positions[roots[it]] })
            }
            return
        }
        check(lambdaDegree in 0..1 && bDegree in 0..1 && lambdaDegree + bDegree <= 1)
        // lambda = 1 + u*x, b = v, or lambda = 1, b = v + u*x.
        // For each v, a root imposes one linear equation on u. Count equal ratios;
        // a zero coefficient either contributes to every u or to none.
        val coefficient = IntArray(xs.size) { F.multiply(xs[it], if (lambdaDegree == 1) a[it] else b[it]) }
        val counts = IntArray(256)
        val required = IntArray(xs.size)
        for (v in 0..255) {
            check()
            counts.fill(0)
            var common = 0
            for (i in xs.indices) {
                val constant = a[i] xor F.multiply(v, b[i])
                required[i] = when {
                    lambdaDegree + bDegree == 0 || coefficient[i] == 0 -> if (constant == 0) -1 else -2
                    else -> F.divide(constant, coefficient[i])
                }
                if (required[i] == -1) common++ else if (required[i] >= 0) counts[required[i]]++
            }
            val uRange = if (lambdaDegree + bDegree == 0) 0..0 else 0..255
            for (u in uRange) if (counts[u] + common == radius) {
                // Exclude the non-coprime b=0, lambda!=1 representation.
                if (lambdaDegree == 1 && v == 0 && u != 0) continue
                val roots = positions.indices.filter { required[it] == -1 || required[it] == u }.map { positions[it] }.toIntArray()
                offer(roots)
            }
        }
        // Smaller-distance words may have fewer than radius roots and are not in the groups above.
        val ordinary = try { rs.decode(received, length - dataLength, erased).corrected }
            catch (_: ReedSolomonErasures.DecodeException) { null }
        ordinary?.let(emit)
    }

    /**
     * At exact distance r, at least one degree bound is attained and lambda,b are
     * coprime (Wu lemma 3). Any d=degBound(lambda)+degBound(b)+1 true roots uniquely
     * determine the normalized pair: the cross product of two pairs has degree <d.
     * Consequently every such true-root system has full rank. Lower-distance
     * pairs are handled separately, never by arbitrarily filling free coefficients.
     */
    private fun fitRootConstraints(xs: IntArray, a: IntArray, b: IntArray, lambdaDegree: Int,
        bDegree: Int, radius: Int, check: () -> Unit, emit: (IntArray) -> Unit) {
        val width = lambdaDegree + bDegree + 1
        check(width in 3..6)
        val rows = Array(xs.size) { i ->
            IntArray(width + 1).also { row ->
                var power = xs[i]
                for (j in 0 until lambdaDegree) { row[j] = F.multiply(a[i], power); power = F.multiply(power, xs[i]) }
                power = 1
                for (j in 0..bDegree) { row[lambdaDegree + j] = F.multiply(b[i], power); power = F.multiply(power, xs[i]) }
                row[width] = a[i]
            }
        }
        val chosen = IntArray(width) { it }
        val matrix = Array(width) { IntArray(width + 1) }
        val solution = IntArray(width)
        val roots = IntArray(xs.size)
        while (true) {
            check()
            for (i in 0 until width) rows[chosen[i]].copyInto(matrix[i])
            var fullRank = true
            for (column in 0 until width) {
                var pivot = column
                while (pivot < width && matrix[pivot][column] == 0) pivot++
                if (pivot == width) { fullRank = false; break }
                val old = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = old
                val inverse = F.divide(1, matrix[column][column])
                for (j in column..width) matrix[column][j] = F.multiply(matrix[column][j], inverse)
                for (i in column + 1 until width) {
                    val factor = matrix[i][column]
                    if (factor != 0) for (j in column..width)
                        matrix[i][j] = matrix[i][j] xor F.multiply(factor, matrix[column][j])
                }
            }
            if (fullRank) {
                for (i in width - 1 downTo 0) {
                    var value = matrix[i][width]
                    for (j in i + 1 until width) value = value xor F.multiply(matrix[i][j], solution[j])
                    solution[i] = value
                }
                var count = 0
                for (i in rows.indices) {
                    var value = rows[i][width]
                    for (j in 0 until width) value = value xor F.multiply(rows[i][j], solution[j])
                    if (value == 0) roots[count++] = i
                    if (count + rows.size - i - 1 < radius) break
                }
                // A canonical seed avoids storing or emitting the same locator for every
                // subset of its roots. A nonzero locator of degree <=r has at most r roots.
                if (count == radius && chosen.indices.all { chosen[it] == roots[it] }) emit(roots.copyOf(count))
            }
            var next = width - 1
            while (next >= 0 && chosen[next] == xs.size - width + next) next--
            if (next < 0) return
            chosen[next]++
            for (i in next + 1 until width) chosen[i] = chosen[i - 1] + 1
        }
    }
}
