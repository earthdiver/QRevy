package io.github.earthdiver.qrrepair.core

/** Saturating estimates must never turn an oversized plan into an apparently cheap one. */
internal fun listDecodingWork(vararg factors: Long): Long {
    var value = 1L
    for (factor in factors) {
        require(factor >= 0)
        if (factor == 0L) return 0
        if (value > Long.MAX_VALUE / factor) return Long.MAX_VALUE
        value *= factor
    }
    return value
}

internal fun listDecodingMemory(vararg parts: Long): Long {
    var total = 0L
    for (part in parts) {
        require(part >= 0)
        if (total > Long.MAX_VALUE - part) return Long.MAX_VALUE
        total += part
    }
    return total
}

/** GF(256), primitive polynomial 0x11D. Polynomial coefficients are in ascending order. */
internal object ListDecodingField {
    private val exp = IntArray(512)
    private val log = IntArray(256)
    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x; log[x] = i
            x = x shl 1
            if (x and 256 != 0) x = x xor 0x11D
        }
        for (i in 255 until exp.size) exp[i] = exp[i - 255]
    }
    fun multiply(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    fun divide(a: Int, b: Int): Int {
        require(b != 0)
        return if (a == 0) 0 else exp[(log[a] - log[b] + 255) % 255]
    }
    fun power(a: Int, exponent: Int): Int = if (exponent == 0) 1 else if (a == 0) 0
        else exp[((log[a] * exponent) % 255 + 255) % 255]
    fun evaluate(poly: IntArray, x: Int): Int {
        var value = 0
        for (i in poly.lastIndex downTo 0) value = multiply(value, x) xor poly[i]
        return value
    }
}

/** Original shortened-code coordinates must also be used for erased or ambiguous positions. */
internal class ListDecodingCoordinates(length: Int) {
    val points = IntArray(length) { ListDecodingField.power(2, length - 1 - it) }
    val denominators = IntArray(length) { i ->
        var product = 1
        for (j in points.indices) if (i != j) product = ListDecodingField.multiply(product, points[i] xor points[j])
        product
    }
    fun codeword(coefficients: IntArray): IntArray = IntArray(points.size) {
        ListDecodingField.divide(ListDecodingField.evaluate(coefficients, points[it]), denominators[it])
    }
}

/** Roth--Ruckenstein prefixes. Callers validate each completed polynomial against their score/radius. */
internal fun listDecodingRoots(q: Array<IntArray>, coefficientCount: Int, check: () -> Unit, emit: (IntArray) -> Unit) {
    val coefficients = IntArray(coefficientCount)
    fun visit(polynomial: Array<IntArray>, depth: Int) {
        check()
        if (depth == coefficientCount) { emit(coefficients); return }
        val shift = polynomial.minOf { p -> p.indexOfFirst { it != 0 }.let { if (it < 0) Int.MAX_VALUE else it } }
        check(shift != Int.MAX_VALUE)
        val atZero = IntArray(polynomial.size) { polynomial[it].getOrElse(shift) { 0 } }
        for (root in 0..255) {
            check()
            if (ListDecodingField.evaluate(atZero, root) != 0) continue
            coefficients[depth] = root
            val nextSize = polynomial.maxOf { it.size }.coerceAtLeast(shift + 1) - shift + polynomial.size - 1
            val next = Array(polynomial.size) { IntArray(nextSize) }
            val powers = IntArray(polynomial.size).also {
                it[0] = 1
                for (j in 1 until it.size) it[j] = ListDecodingField.multiply(it[j - 1], root)
            }
            for (j in polynomial.indices) {
                check()
                for (t in 0..j) if ((t and j) == t) {
                    val scale = powers[j - t]
                    if (scale == 0) continue
                    for (x in shift until polynomial[j].size) {
                        val value = polynomial[j][x]
                        if (value != 0) next[t][x - shift + t] = next[t][x - shift + t] xor ListDecodingField.multiply(value, scale)
                    }
                }
            }
            visit(next, depth + 1)
        }
    }
    visit(q, 0)
}
