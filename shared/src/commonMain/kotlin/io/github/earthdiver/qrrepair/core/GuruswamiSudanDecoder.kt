package io.github.earthdiver.qrrepair.core

/**
 * Shortened QR RS list decoding over GF(256), primitive polynomial 0x11D, first root zero.
 * Interpolation uses a weighted polynomial-module reduction; roots use Roth--Ruckenstein.
 * References: Guruswami/Sudan (1999), Nielsen/Zeh, arXiv:1404.3022, sections 2--3.
 * Polynomial coefficients in this implementation are stored in increasing degree order.
 */
class GuruswamiSudanDecoder internal constructor(val length: Int, val dataLength: Int,
    coordinateFactory: () -> ListDecodingCoordinates) {
    constructor(length: Int, dataLength: Int) : this(length, dataLength, { ListDecodingCoordinates(length) })
    private val coordinates by lazy(coordinateFactory)
    private val parameterCache = mutableListOf<Pair<Pair<Int, Int>, Parameters>>()
    init { require(dataLength in 2 until length && length <= 255) }

    data class Parameters(val points: Int, val radius: Int, val multiplicity: Int, val listDegree: Int,
        val degreeBound: Int, val workingBytes: Long)

    // Keep original shortened-code coordinates when puncturing erasures.
    private val points get() = coordinates.points
    private val denominators get() = coordinates.denominators

    fun parameters(erasures: Int, workingLimitBytes: Long, check: () -> Unit = {}): Parameters? =
        parameters(erasures, workingLimitBytes, ListDecodingLimit.DEFAULT, check)

    /** A finite integer radius strictly inside the Johnson bound. */
    fun parameters(erasures: Int, workingLimitBytes: Long, additionalErrors: Int, check: () -> Unit = {}): Parameters? {
        require(erasures in 0..length)
        require(additionalErrors in ListDecodingLimit.supported)
        parameterCache.firstOrNull { it.first == (erasures to additionalErrors) }?.second?.let {
            check()
            if (it.workingBytes > workingLimitBytes) throw ListDecodingWorkingMemoryException()
            return it
        }
        val n = length - erasures
        if (n < dataLength) return null
        val radius = (n - dataLength) / 2 + additionalErrors
        val agreement = n - radius
        val weight = dataLength - 1
        val delta = agreement * agreement - n * weight
        if (delta <= 0) return null // Strict Johnson inequality, evaluated without floating point.
        // Counting all allowed monomials gives E >= m*(m*delta/weight - radius)/2.
        // Thus this finite bound contains a feasible multiplicity; no arbitrary degree cutoff.
        val lastMultiplicity = weight * radius / delta + 1
        for (m in 1..lastMultiplicity) {
            check()
            val maximumY = (m * agreement - 1) / weight
            for (ell in m..maximumY) {
                val coefficients = (ell + 1L) * m * agreement - ell.toLong() * (ell + 1) / 2 * weight
                val constraints = n.toLong() * m * (m + 1) / 2
                if (coefficients <= constraints) continue
                val degree = maxOf(m * n, m * (n - 1) + (ell - m) * weight)
                val bytes = workspaceBytes(m, ell, degree)
                if (bytes > workingLimitBytes) throw ListDecodingWorkingMemoryException()
                return Parameters(n, radius, m, ell, degree, bytes).also {
                    if (parameterCache.size == 4) parameterCache.removeAt(0)
                    parameterCache += (erasures to additionalErrors) to it
                }
            }
        }
        error("No GS parameters despite a strict Johnson inequality")
    }

    /** Includes matrix, products/copies, a depth-first root stack, outputs, and array overhead. */
    private fun workspaceBytes(m: Int, ell: Int, degree: Int): Long {
        val polynomial = 32L + 4L * (degree + 1)
        val matrix = (ell + 1L) * (ell + 1L) * polynomial
        val rootPolynomial = 32L + 4L * (degree + dataLength * ell + 1)
        val roots = (dataLength + 2L) * (ell + 1L) * rootPolynomial
        return 16_384L + length * 64L + 2 * matrix + 2 * roots +
            (m + 2L) * 4 * polynomial + (ell + 1L) * (512L + length * 16L)
    }

    fun decode(received: IntArray, erased: IntArray, parameters: Parameters,
        workingLimitBytes: Long, check: () -> Unit = {}): List<IntArray> {
        require(received.size == length && received.all { it in 0..255 })
        require(erased.distinct().size == erased.size && erased.all { it in received.indices })
        require(parameters.points == length - erased.size)
        require(parameters == parameters(erased.size, Long.MAX_VALUE,
            parameters.radius - (length - erased.size - dataLength) / 2, check))
        if (parameters.workingBytes > workingLimitBytes) throw ListDecodingWorkingMemoryException()
        check()
        val omitted = BooleanArray(length).also { flags -> erased.forEach { flags[it] = true } }
        val indices = received.indices.filterNot { omitted[it] }
        val xs = IntArray(indices.size) { points[indices[it]] }
        val ys = IntArray(indices.size) { Field.multiply(received[indices[it]], denominators[indices[it]]) }
        val q = interpolate(xs, ys, parameters, check)
        val result = mutableListOf<IntArray>()
        listDecodingRoots(q, dataLength, check) { coefficients ->
            val corrected = IntArray(length) { Field.divide(evaluate(coefficients, points[it]), denominators[it]) }
            if (indices.count { corrected[it] != received[it] } <= parameters.radius) {
                check(ReedSolomonErasures().calcSyndromes(corrected, length - dataLength).all { it == 0 })
                if (result.none { it.contentEquals(corrected) }) result += corrected
            }
        }
        return result
    }

    private fun interpolate(xs: IntArray, ys: IntArray, p: Parameters, check: () -> Unit): Array<IntArray> {
        val size = p.listDegree + 1
        val capacity = p.degreeBound + 1
        var g = intArrayOf(1)
        for (x in xs) { check(); g = multiply(g, intArrayOf(x, 1), check) }
        val r = IntArray(xs.size)
        for (i in xs.indices) {
            check()
            val quotient = IntArray(xs.size)
            quotient[quotient.lastIndex] = g.last()
            for (j in quotient.lastIndex - 1 downTo 0) quotient[j] = g[j + 1] xor Field.multiply(xs[i], quotient[j + 1])
            val scale = Field.divide(ys[i], evaluate(quotient, xs[i]))
            for (j in r.indices) r[j] = r[j] xor Field.multiply(scale, quotient[j])
        }
        val gPowers = Array(p.multiplicity + 1) { intArrayOf(1) }
        for (i in 1..p.multiplicity) gPowers[i] = multiply(gPowers[i - 1], g, check)
        val rPowers = Array(p.multiplicity + 1) { intArrayOf(1) }
        for (i in 1..p.multiplicity) rPowers[i] = multiply(rPowers[i - 1], r, check)
        val rows = Array(size) { Array(size) { IntArray(capacity) } }
        for (i in 0 until size) {
            check()
            val power = minOf(i, p.multiplicity)
            val yShift = (i - p.multiplicity).coerceAtLeast(0)
            for (j in 0..power) if ((j and power) == j) {
                val value = multiply(gPowers[p.multiplicity - power], rPowers[power - j], check)
                value.copyInto(rows[i][j + yShift])
            }
        }
        val degrees = Array(size) { i -> IntArray(size) { degree(rows[i][it]) } }
        fun leading(row: Int): Int = degrees[row].indices.filter { degrees[row][it] >= 0 }
            .maxWithOrNull(compareBy<Int> { degrees[row][it] + it * (dataLength - 1) }.thenBy { it }) ?: error("Zero GS basis row")
        val leads = IntArray(size) { leading(it) }
        while (true) {
            check()
            var first = -1
            var second = -1
            loop@ for (i in 0 until size) for (j in 0 until i) if (leads[i] == leads[j]) {
                first = i; second = j; break@loop
            }
            if (first < 0) break
            val column = leads[first]
            if (degrees[first][column] < degrees[second][column]) {
                val old = first; first = second; second = old
            }
            val shift = degrees[first][column] - degrees[second][column]
            val scale = Field.divide(rows[first][column][degrees[first][column]], rows[second][column][degrees[second][column]])
            for (j in 0 until size) {
                check()
                for (x in 0..degrees[second][j]) {
                    rows[first][j][x + shift] = rows[first][j][x + shift] xor Field.multiply(scale, rows[second][j][x])
                }
                degrees[first][j] = degree(rows[first][j])
            }
            leads[first] = leading(first)
        }
        val row = (0 until size).minBy { degrees[it][leads[it]] + leads[it] * (dataLength - 1) }
        check(degrees[row][leads[row]] + leads[row] * (dataLength - 1) < p.multiplicity * (p.points - p.radius))
        return Array(size) { rows[row][it].copyOf(degrees[row][it] + 1) }
    }

    private fun degree(poly: IntArray) = poly.indexOfLast { it != 0 }
    private fun evaluate(poly: IntArray, x: Int): Int {
        var value = 0
        for (i in poly.lastIndex downTo 0) value = Field.multiply(value, x) xor poly[i]
        return value
    }
    private fun multiply(a: IntArray, b: IntArray, check: () -> Unit): IntArray {
        val out = IntArray(a.size + b.size - 1)
        for (i in a.indices) {
            check()
            if (a[i] != 0) for (j in b.indices) if (b[j] != 0) out[i + j] = out[i + j] xor Field.multiply(a[i], b[j])
        }
        return out
    }

    private object Field {
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
        fun power(a: Int, exponent: Int) = if (exponent == 0) 1 else if (a == 0) 0 else exp[log[a] * exponent % 255]
    }
}
