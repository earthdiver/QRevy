package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.core.ListDecodingField as F

/**
 * Wu's locator formulation specialized to one symbol beyond unique decoding.
 * Lambda' = Lambda * lambda + xB * b, lambda(0) = 1. At this radius the sum
 * of the degree bounds for lambda and b is at most one. Grouping field ratios
 * solves the resulting constant/linear rational fitting problem exactly, without
 * constructing a bivariate interpolant. Infinity points are handled without division.
 * Reference: https://arxiv.org/abs/cs/0703105, section II.A and lemma 3.
 */
class WuDecoder(private val length: Int, private val dataLength: Int) {
    init { require(dataLength in 2 until length && length <= 255) }
    val workingBytes: Long get() = 32_768L + length * 512L

    /** Deterministic work estimate for selection, not a wall-clock guarantee. */
    fun estimatedWork(erasures: Int): Long {
        val parity = length - dataLength - erasures
        return length.toLong() * (parity * parity + if (parity % 2 == 0) 1024 else 32) +
            if (parity % 2 == 0) 65_536L else 0L
    }

    fun decode(received: IntArray, erased: IntArray, radius: Int, workingLimitBytes: Long,
        check: () -> Unit = {}, emit: (IntArray) -> Unit) {
        require(received.size == length && received.all { it in 0..255 })
        require(erased.distinct().size == erased.size && erased.all { it in received.indices })
        val parity = length - dataLength - erased.size
        require(parity >= 1 && radius == parity / 2 + 1)
        if (workingBytes > workingLimitBytes) throw ListDecodingWorkingMemoryException()
        check()
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
            if (positions.count { corrected[it] != received[it] } <= radius) emit(corrected)
        }
        if (bDegree < 0) {
            val roots = positions.indices.filter { a[it] == 0 }.map { positions[it] }.toIntArray()
            if (roots.size == locatorLength) offer(roots)
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
}
