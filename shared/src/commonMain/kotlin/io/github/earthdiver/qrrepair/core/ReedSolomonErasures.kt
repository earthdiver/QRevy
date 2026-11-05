package io.github.earthdiver.qrrepair.core

/**
 * QR-Code Reed-Solomon decoder over GF(256), primitive polynomial 0x11D, generator 2,
 * first consecutive root 0. Supports simultaneous unknown-position errors and known-position
 * erasures. Polynomial arrays use highest-degree coefficient first.
 *
 * The algorithm is the standard syndrome -> Forney syndrome -> Berlekamp-Massey ->
 * Chien search -> Forney magnitude pipeline.
 */
class ReedSolomonErasures {
    data class Result(
        val corrected: IntArray,
        val errataPositions: IntArray,
    )

    class DecodeException(
        message: String,
        val reason: RsFailureReason,
    ) : Exception(message) {
        constructor(message: String) : this(message, RsFailureReason.CORRECTION_FAILED)
    }

    private object GF {
        private const val PRIMITIVE = 0x11D
        private val exp = IntArray(512)
        private val log = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                exp[i] = x
                log[x] = i
                x = x shl 1
                if ((x and 0x100) != 0) x = x xor PRIMITIVE
            }
            for (i in 255 until exp.size) exp[i] = exp[i - 255]
        }

        fun mul(a: Int, b: Int): Int {
            if (a == 0 || b == 0) return 0
            return exp[log[a and 0xFF] + log[b and 0xFF]]
        }

        fun div(a: Int, b: Int): Int {
            if (b == 0) throw ArithmeticException("GF division by zero")
            if (a == 0) return 0
            var e = log[a and 0xFF] - log[b and 0xFF]
            if (e < 0) e += 255
            return exp[e]
        }

        fun inverse(a: Int): Int {
            if (a == 0) throw ArithmeticException("GF inverse of zero")
            return exp[255 - log[a and 0xFF]]
        }

        fun pow(a: Int, power: Int): Int {
            if (power == 0) return 1
            if (a == 0) return 0
            var e = (log[a and 0xFF] * power) % 255
            if (e < 0) e += 255
            return exp[e]
        }
    }

    fun decode(received: IntArray, ecSymbols: Int, erasePositions: IntArray = intArrayOf()): Result {
        require(ecSymbols > 0)
        require(received.size <= 255) { "RS block cannot exceed 255 symbols" }
        val msg = IntArray(received.size) { received[it] and 0xFF }
        val erasures = erasePositions.distinct().sorted().toIntArray()
        if (erasures.any { it !in msg.indices }) throw DecodeException("Erasure position outside block")
        if (erasures.size > ecSymbols) throw DecodeException("Too many erasures: ${erasures.size} > $ecSymbols",
            RsFailureReason.ERASURE_CAPACITY_EXCEEDED)
        erasures.forEach { msg[it] = 0 }

        return decodeNormalized(msg, ecSymbols, erasures, calcSyndromes(msg, ecSymbols))
    }

    private fun decodeNormalized(msg: IntArray, ecSymbols: Int, erasures: IntArray, initialSyndromes: IntArray): Result {
        var synd = initialSyndromes
        if (synd.drop(1).all { it == 0 }) return Result(msg, erasures)

        val fsynd = forneySyndromes(synd, erasures, msg.size)
        val errorLocator = findErrorLocator(fsynd, ecSymbols, erasures.size)
        val errorPositions = findErrors(errorLocator.reversedArray(), msg.size)
        val errata = (erasures.asList() + errorPositions.asList()).distinct().sorted().toIntArray()

        if ((errorPositions.size * 2) + erasures.size > ecSymbols) {
            throw DecodeException("Error/erasure capacity exceeded", RsFailureReason.ERROR_ERASURE_CAPACITY_EXCEEDED)
        }

        val corrected = correctErrata(msg, synd, errata)
        synd = calcSyndromes(corrected, ecSymbols)
        if (synd.drop(1).any { it != 0 }) throw DecodeException("Could not correct RS block")
        return Result(corrected, errata)
    }

    /** Prepared state owns its syndromes; callers cannot supply unchecked syndrome arrays. */
    internal inner class BitTrials(
        initial: IntArray,
        private val ecSymbols: Int,
        erasePositions: IntArray,
        private val coordinates: List<RsBitConstraintSolver.Bit>,
    ) {
        private val erasures = erasePositions.sortedArray()
        private val input = IntArray(initial.size) { initial[it] and 255 }
        private var index = 0
        private val contributions: Array<IntArray>
        private val syndromes: IntArray
        init {
            require(ecSymbols > 0 && initial.size <= 255 && coordinates.size <= 20)
            require(erasures.size <= ecSymbols && erasures.distinct().size == erasures.size)
            require(erasures.all { it in input.indices })
            require(coordinates.distinct().size == coordinates.size)
            require(coordinates.all { it.codeword in input.indices && it.bit in 0..7 && it.codeword !in erasures })
            require(coordinates.all { input[it.codeword] and (1 shl it.bit) == 0 })
            val normalized = input.copyOf().also { values -> erasures.forEach { values[it] = 0 } }
            syndromes = calcSyndromes(normalized, ecSymbols)
            contributions = Array(coordinates.size) { i ->
                bitSyndromes(input.size, coordinates[i].codeword, coordinates[i].bit, ecSymbols)
            }
        }
        fun assignment(next: Int): IntArray {
            require(next in 0 until (1 shl coordinates.size))
            var changed = index xor next
            while (changed != 0) {
                val bit = changed.countTrailingZeroBits()
                val coordinate = coordinates[bit]
                input[coordinate.codeword] = input[coordinate.codeword] xor (1 shl coordinate.bit)
                for (root in 0 until ecSymbols) syndromes[root + 1] = syndromes[root + 1] xor contributions[bit][root]
                changed = changed and (changed - 1)
            }
            index = next
            return input.copyOf()
        }
        fun decode(): Result {
            val normalized = input.copyOf().also { values -> erasures.forEach { values[it] = 0 } }
            return decodeNormalized(normalized, ecSymbols, erasures.copyOf(), syndromes)
        }
    }

    /** Encoder is included for deterministic unit tests and diagnostics. */
    fun encode(data: IntArray, ecSymbols: Int): IntArray {
        require(data.size + ecSymbols <= 255)
        val generator = generatorPolynomial(ecSymbols)
        val work = IntArray(data.size + ecSymbols)
        data.forEachIndexed { i, v -> work[i] = v and 0xFF }
        for (i in data.indices) {
            val coef = work[i]
            if (coef != 0) {
                for (j in 1 until generator.size) {
                    work[i + j] = work[i + j] xor GF.mul(generator[j], coef)
                }
            }
        }
        val out = IntArray(work.size)
        for (i in data.indices) out[i] = data[i] and 0xFF
        for (i in 0 until ecSymbols) out[data.size + i] = work[data.size + i]
        return out
    }

    private fun generatorPolynomial(nsym: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until nsym) g = polyMul(g, intArrayOf(1, GF.pow(2, i)))
        return g
    }

    internal fun bitSyndromes(length: Int, position: Int, bit: Int, nsym: Int): IntArray =
        IntArray(nsym) { root -> GF.mul(1 shl bit, GF.pow(2, root * (length - 1 - position))) }

    internal fun calcSyndromes(msg: IntArray, nsym: Int): IntArray {
        val out = IntArray(nsym + 1)
        out[0] = 0
        for (i in 0 until nsym) out[i + 1] = polyEval(msg, GF.pow(2, i))
        return out
    }

    /** Removes the known erasures from the syndrome sequence before Berlekamp-Massey. */
    private fun forneySyndromes(synd: IntArray, erasePos: IntArray, messageLength: Int): IntArray {
        val f = synd.copyOfRange(1, synd.size)
        for (pos in erasePos) {
            val coefficientDegree = messageLength - 1 - pos
            val x = GF.pow(2, coefficientDegree)
            for (j in 0 until f.size - 1) {
                f[j] = GF.mul(f[j], x) xor f[j + 1]
            }
        }
        return f
    }

    private fun findErrorLocator(synd: IntArray, nsym: Int, eraseCount: Int): IntArray {
        var errLoc = intArrayOf(1)
        var oldLoc = intArrayOf(1)
        val syndShift = if (synd.size > nsym) synd.size - nsym else 0

        for (i in 0 until (nsym - eraseCount)) {
            val k = i + syndShift
            if (k !in synd.indices) break
            var delta = synd[k]
            for (j in 1 until errLoc.size) {
                val sIndex = k - j
                if (sIndex >= 0) {
                    delta = delta xor GF.mul(errLoc[errLoc.size - 1 - j], synd[sIndex])
                }
            }

            oldLoc = oldLoc + 0
            if (delta != 0) {
                if (oldLoc.size > errLoc.size) {
                    val newLoc = polyScale(oldLoc, delta)
                    oldLoc = polyScale(errLoc, GF.inverse(delta))
                    errLoc = newLoc
                }
                errLoc = polyAdd(errLoc, polyScale(oldLoc, delta))
            }
        }

        errLoc = trimLeadingZeros(errLoc)
        val errors = errLoc.size - 1
        if (errors * 2 + eraseCount > nsym) {
            throw DecodeException("Too many unknown errors: $errors with $eraseCount erasures for $nsym ECC symbols",
                RsFailureReason.ERROR_ERASURE_CAPACITY_EXCEEDED)
        }
        return errLoc
    }

    private fun findErrors(errorLocator: IntArray, messageLength: Int): IntArray {
        val expected = errorLocator.size - 1
        val positions = ArrayList<Int>(expected)
        for (i in 0 until messageLength) {
            if (polyEval(errorLocator, GF.pow(2, i)) == 0) {
                positions += messageLength - 1 - i
            }
        }
        if (positions.size != expected) {
            throw DecodeException("Chien search found ${positions.size} errors, expected $expected",
                RsFailureReason.NO_CONSISTENT_ASSIGNMENT)
        }
        return positions.toIntArray()
    }

    private fun correctErrata(msg: IntArray, synd: IntArray, errataPositions: IntArray): IntArray {
        if (errataPositions.isEmpty()) return msg.copyOf()
        val coefficientPositions = errataPositions.map { msg.size - 1 - it }.toIntArray()
        val errataLocator = findErrataLocator(coefficientPositions)
        val errataEvaluator = findErrorEvaluator(synd.reversedArray(), errataLocator, errataLocator.size - 1).reversedArray()

        val xValues = IntArray(coefficientPositions.size) { GF.pow(2, coefficientPositions[it]) }
        val errorMagnitudes = IntArray(msg.size)

        for (i in xValues.indices) {
            val xi = xValues[i]
            val xiInv = GF.inverse(xi)
            var denominator = 1
            for (j in xValues.indices) {
                if (j == i) continue
                denominator = GF.mul(denominator, 1 xor GF.mul(xiInv, xValues[j]))
            }
            if (denominator == 0) throw DecodeException("Forney denominator is zero")
            var y = polyEval(errataEvaluator.reversedArray(), xiInv)
            // QR uses fcr=0, therefore Xi^(1-fcr) == Xi.
            y = GF.mul(xi, y)
            val magnitude = GF.div(y, denominator)
            errorMagnitudes[errataPositions[i]] = magnitude
        }

        return polyAdd(msg, errorMagnitudes)
    }

    private fun findErrataLocator(coefficientPositions: IntArray): IntArray {
        var locator = intArrayOf(1)
        for (p in coefficientPositions) {
            val factor = polyAdd(intArrayOf(1), intArrayOf(GF.pow(2, p), 0))
            locator = polyMul(locator, factor)
        }
        return locator
    }

    private fun findErrorEvaluator(synd: IntArray, errorLocator: IntArray, nsym: Int): IntArray {
        val product = polyMul(synd, errorLocator)
        val keep = nsym + 1
        return if (product.size <= keep) product else product.copyOfRange(product.size - keep, product.size)
    }

    private fun polyScale(p: IntArray, x: Int): IntArray = IntArray(p.size) { GF.mul(p[it], x) }

    private fun polyAdd(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(maxOf(a.size, b.size))
        val ao = out.size - a.size
        val bo = out.size - b.size
        for (i in a.indices) out[i + ao] = out[i + ao] xor a[i]
        for (i in b.indices) out[i + bo] = out[i + bo] xor b[i]
        return out
    }

    private fun polyMul(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size + b.size - 1)
        for (i in a.indices) for (j in b.indices) {
            out[i + j] = out[i + j] xor GF.mul(a[i], b[j])
        }
        return out
    }

    private fun polyEval(poly: IntArray, x: Int): Int {
        if (poly.isEmpty()) return 0
        var y = poly[0]
        for (i in 1 until poly.size) y = GF.mul(y, x) xor poly[i]
        return y
    }

    private fun trimLeadingZeros(p: IntArray): IntArray {
        var i = 0
        while (i < p.size - 1 && p[i] == 0) i++
        return if (i == 0) p else p.copyOfRange(i, p.size)
    }
}
