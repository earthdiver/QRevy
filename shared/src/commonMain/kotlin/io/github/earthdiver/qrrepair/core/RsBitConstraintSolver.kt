package io.github.earthdiver.qrrepair.core

/** Exact known-bit constraints, not an unknown-error decoder or proof of payload correctness. */
class RsBitConstraintSolver(
    private val rs: ReedSolomonErasures = ReedSolomonErasures(),
    private val maximumCacheBytes: Long = 0,
) {
    init { require(maximumCacheBytes >= 0) }
    private class Shape(val masks: IntArray, val ecSymbols: Int,
        val rows: Array<LongArray>, val transform: Array<LongArray>, val pivots: IntArray,
        val free: IntArray, val bytes: Long)
    private val shapes = mutableListOf<Shape>()
    val cacheWorkingBytes: Long get() = shapes.sumOf { it.bytes }
    private fun retain(shape: Shape) {
        while (shapes.isNotEmpty() && (shapes.size >= 4 || cacheWorkingBytes + shape.bytes > maximumCacheBytes))
            shapes.removeAt(0)
        if (shape.bytes <= maximumCacheBytes) shapes += shape
    }

    /** Bit positions use the numeric (LSB=0) convention, unlike QR extraction coordinates. */
    data class Bit(val codeword: Int, val bit: Int)

    class Space internal constructor(
        val unknownBits: List<Bit>,
        val rank: Int,
        private val base: IntArray,
        private val pivots: IntArray,
        private val rows: Array<LongArray>,
        private val rhs: BooleanArray,
        private val free: IntArray,
    ) {
        val dimension: Int get() = free.size
        /** Conservative live storage estimate, including binary rows and coordinate metadata. */
        val workingBytes: Long get() = 1024L + base.size * 8L + unknownBits.size * 64L +
            rows.sumOf { 32L + it.size * 8L } + rhs.size * 8L + free.size * 8L +
            if (dimension in 1..20) (dimension + 1L) * (32L + base.size * 4L) else 0L
        private val pivotRow = IntArray(unknownBits.size) { -1 }.also { index ->
            pivots.forEachIndexed { row, column -> index[column] = row }
        }

        /** Free-variable coefficients of a coordinate; an empty vector means a fixed bit. */
        private fun expression(column: Int): LongArray {
            val result = LongArray((dimension + 63) / 64)
            val row = pivotRow[column]
            free.forEachIndexed { i, f ->
                if (f == column || (row >= 0 && rows[row].has(f))) result.set(i)
            }
            return result
        }

        /** Reuses this RS elimination; branch conditions operate only on its free variables. */
        fun restriction(): Restriction = Restriction()

        val restrictionWorkingBytes: Long get() = workingBytes + base.size * 32L +
            (dimension + unknownBits.size) * (48L + ((dimension + 63) / 64) * 8L) +
            32L + ((dimension + 63) / 64) * 8L

        inner class Restriction internal constructor() {
            private val columns = IntArray(base.size * 8) { -1 }.also { lookup ->
                unknownBits.forEachIndexed { column, bit -> lookup[bit.codeword * 8 + bit.bit] = column }
            }
            private val expressions = arrayOfNulls<LongArray>(unknownBits.size)
            private val equations = arrayOfNulls<LongArray>(dimension)
            private val activePivots = LongArray((dimension + 63) / 64)
            private val constants = BooleanArray(dimension)
            private val added = mutableListOf<Int>()
            val remaining: Int get() = dimension - added.size
            val checkpoint: Int get() = added.size

            fun restore(checkpoint: Int) {
                require(checkpoint in 0..added.size)
                while (added.size > checkpoint) {
                    val pivot = added.removeAt(added.lastIndex)
                    equations[pivot] = null
                    activePivots[pivot / 64] = activePivots[pivot / 64] and (1L shl (pivot % 64)).inv()
                }
            }

            private fun equation(bit: Bit): Pair<LongArray, Boolean> {
                require(bit.codeword in base.indices && bit.bit in 0..7)
                val column = columns[bit.codeword * 8 + bit.bit]
                if (column < 0) return LongArray((dimension + 63) / 64) to (base[bit.codeword] and (1 shl bit.bit) != 0)
                val vector = (expressions[column] ?: expression(column).also { expressions[column] = it }).copyOf()
                var constant = pivotRow[column].let { it >= 0 && rhs[it] }
                for (word in vector.indices) {
                    var pending = vector[word] and activePivots[word]
                    while (pending != 0L) {
                        val pivot = word * 64 + pending.countTrailingZeroBits()
                        val row = requireNotNull(equations[pivot])
                        // A row has no coefficients below its pivot. Re-read this word after
                        // elimination because XOR can expose another pivot in the same word.
                        for (index in word until vector.size) vector[index] = vector[index] xor row[index]
                        constant = constant xor constants[pivot]
                        pending = vector[word] and activePivots[word]
                    }
                }
                return vector to constant
            }

            fun value(bit: Bit): Int? {
                val (vector, constant) = equation(bit)
                return if (vector.all { it == 0L }) if (constant) 1 else 0 else null
            }

            /** False is a contradiction in this exact branch, not evidence against error correction. */
            fun constrain(bit: Bit, value: Int): Boolean {
                require(value in 0..1)
                val (vector, constant) = equation(bit)
                val pivot = vector.firstSetBit()
                if (pivot < 0) return constant == (value == 1)
                equations[pivot] = vector
                activePivots.set(pivot)
                constants[pivot] = constant xor (value == 1)
                added += pivot
                return true
            }

            fun assignments(maxDimension: Int, cancellation: RecoveryCancellation = RecoveryCancellation.NONE): Sequence<IntArray> = sequence {
                require(maxDimension in 0..20)
                if (remaining > maxDimension) return@sequence
                val independent = equations.indices.filter { equations[it] == null }
                fun wordFor(selected: Int?): IntArray {
                    cancellation.check()
                    val variables = LongArray((dimension + 63) / 64)
                    if (selected != null) variables.set(selected)
                    for (i in equations.indices.reversed()) {
                        if (i % 64 == 0) cancellation.check()
                        val row = equations[i] ?: continue
                        var value = constants[i]
                        for (j in row.indices) if ((row[j] and variables[j]).countOneBits() and 1 != 0) value = !value
                        if (value) variables.set(i)
                    }
                    val values = LongArray((unknownBits.size + 63) / 64)
                    free.forEachIndexed { i, column -> if (variables.has(i)) values.set(column) }
                    pivots.forEachIndexed { row, column ->
                        if (row % 64 == 0) cancellation.check()
                        var value = rhs[row]
                        for (j in rows[row].indices) if ((rows[row][j] and values[j]).countOneBits() and 1 != 0) value = !value
                        if (value) values.set(column)
                    }
                    val word = base.copyOf()
                    unknownBits.forEachIndexed { i, bit -> if (values.has(i)) word[bit.codeword] = word[bit.codeword] or (1 shl bit.bit) }
                    return word
                }
                val current = wordFor(null)
                // Reduce once per surviving branch, then change only one basis vector per
                // assignment (Gray order). Large original RS spaces are not re-solved per QR.
                val deltas = independent.map { variable -> wordFor(variable).also { word ->
                    for (i in word.indices) word[i] = word[i] xor current[i]
                } }
                for (assignment in 0 until (1 shl remaining)) {
                    cancellation.check()
                    if (assignment != 0) {
                        val delta = deltas[assignment.countTrailingZeroBits()]
                        for (i in current.indices) current[i] = current[i] xor delta[i]
                    }
                    yield(current.copyOf())
                }
            }
        }

        val fixedBits: Map<Bit, Int> by lazy {
            buildMap {
                unknownBits.forEachIndexed { column, bit ->
                    if (expression(column).all { it == 0L }) {
                        put(bit, if (rhs[pivotRow[column]]) 1 else 0)
                    }
                }
            }
        }

        /**
         * Independent measurements, in caller preference order. Every returned bit halves this
         * affine space even after all preceding bits have been measured (either value).
         */
        fun independentBits(preferred: List<Bit> = unknownBits,
            cancellation: RecoveryCancellation = RecoveryCancellation.NONE): List<Bit> {
            val index = unknownBits.withIndex().associate { it.value to it.index }
            val basis = arrayOfNulls<LongArray>(dimension)
            return buildList {
                for (bit in preferred) {
                    cancellation.check()
                    val column = index[bit] ?: continue
                    val vector = expression(column)
                    for (i in 0 until dimension) {
                        if (!vector.has(i)) continue
                        val existing = basis[i]
                        if (existing != null) vector.xorWith(existing) else {
                            basis[i] = vector
                            add(bit)
                            break
                        }
                    }
                    if (size == dimension) break
                }
            }
        }

        /** No sampling of a large space: either enumerate the entire space or leave it symbolic. */
        fun assignments(maxDimension: Int = PartialBitSearchLimit.DEFAULT, cancellation: RecoveryCancellation = RecoveryCancellation.NONE): Sequence<IntArray> {
            require(maxDimension in 0..20)
            if (dimension > maxDimension) return emptySequence()
            return sequence {
                cancellation.check()
                val current = base.copyOf()
                pivots.forEachIndexed { row, column ->
                    val bit = unknownBits[column]
                    if (rhs[row]) current[bit.codeword] = current[bit.codeword] or (1 shl bit.bit)
                }
                val deltas = free.map { column ->
                    cancellation.check()
                    IntArray(base.size).also { delta ->
                        val freeBit = unknownBits[column]
                        delta[freeBit.codeword] = 1 shl freeBit.bit
                        pivots.forEachIndexed { row, pivot ->
                            if (rows[row].has(column)) {
                                val bit = unknownBits[pivot]
                                delta[bit.codeword] = delta[bit.codeword] xor (1 shl bit.bit)
                            }
                        }
                    }
                }
                // Preserve binary enumeration order; a carry can toggle several basis vectors.
                for (assignment in 0 until (1 shl dimension)) {
                    cancellation.check()
                    var changed = if (assignment == 0) 0 else assignment xor (assignment - 1)
                    while (changed != 0) {
                        val delta = deltas[changed.countTrailingZeroBits()]
                        for (i in current.indices) current[i] = current[i] xor delta[i]
                        changed = changed and (changed - 1)
                    }
                    yield(current.copyOf())
                }
            }
        }
    }

    /** Null means inconsistent under the assumption that every observed data/ECC bit is correct. */
    fun analyze(block: RsBlockWork, ecSymbols: Int, cancellation: RecoveryCancellation = RecoveryCancellation.NONE): Space? {
        cancellation.check()
        require(ecSymbols > 0 && block.codewords.size in (ecSymbols + 1)..255)
        require(block.numDataCodewords == block.codewords.size - ecSymbols)
        require(block.unknownMasks.size == block.codewords.size)
        require(block.unknownMasks.all { it in 0..255 })
        val unknown = buildList {
            for (position in block.codewords.indices) for (bit in 0..7) {
                if ((block.unknownMasks[position] and (1 shl bit)) != 0) add(Bit(position, bit))
            }
        }
        val base = IntArray(block.codewords.size) { block.codewords[it] and block.unknownMasks[it].inv() and 255 }
        val syndrome = rs.calcSyndromes(base, ecSymbols)
        val cached = shapes.firstOrNull { it.ecSymbols == ecSymbols && it.masks.contentEquals(block.unknownMasks) }
        if (cached != null) {
            val packed = LongArray((ecSymbols * 8 + 63) / 64)
            for (bit in 0 until ecSymbols * 8)
                if (syndrome[bit / 8 + 1] and (1 shl (bit % 8)) != 0) packed.set(bit)
            val transformed = BooleanArray(cached.rows.size) { row ->
                if (row % 16 == 0) cancellation.check()
                var parity = 0
                for (word in packed.indices) parity = parity xor (packed[word] and cached.transform[row][word]).countOneBits()
                parity and 1 != 0
            }
            if ((cached.pivots.size until transformed.size).any { transformed[it] }) return null
            return Space(unknown, cached.pivots.size, base, cached.pivots, cached.rows, transformed, cached.free)
        }
        val equationCount = ecSymbols * 8
        val cacheBytes = 2048L + block.codewords.size * 8L + unknown.size * 96L +
            equationCount * (96L + ((unknown.size + 63) / 64) * 8L + ((equationCount + 63) / 64) * 8L)
        val cacheable = maximumCacheBytes > 0 && cacheBytes <= maximumCacheBytes
        // Make room before allocating the new transform, not only before retaining it.
        if (cacheable) while (shapes.isNotEmpty() &&
            (shapes.size >= 4 || cacheWorkingBytes + cacheBytes > maximumCacheBytes)) shapes.removeAt(0)
        val transform = if (cacheable)
            Array(equationCount) { row -> LongArray((equationCount + 63) / 64).also { it.set(row) } } else null
        val rows = Array(ecSymbols * 8) { LongArray((unknown.size + 63) / 64) }
        val rhs = BooleanArray(rows.size) { (syndrome[it / 8 + 1] and (1 shl (it % 8))) != 0 }
        for ((column, bit) in unknown.withIndex()) {
            if (column % 16 == 0) cancellation.check()
            val contribution = rs.bitSyndromes(base.size, bit.codeword, bit.bit, ecSymbols)
            for (row in rows.indices) if ((contribution[row / 8] and (1 shl (row % 8))) != 0) rows[row].set(column)
        }
        val pivots = mutableListOf<Int>()
        for (column in unknown.indices) {
            if (column % 16 == 0) cancellation.check()
            val rank = pivots.size
            val selected = (rank until rows.size).firstOrNull { rows[it].has(column) } ?: continue
            val temp = rows[rank]; rows[rank] = rows[selected]; rows[selected] = temp
            if (transform != null) {
                val old = transform[rank]; transform[rank] = transform[selected]; transform[selected] = old
            }
            val tempRhs = rhs[rank]; rhs[rank] = rhs[selected]; rhs[selected] = tempRhs
            for (row in rows.indices) if (row != rank && rows[row].has(column)) {
                rows[row].xorWith(rows[rank])
                if (transform != null) transform[row].xorWith(transform[rank])
                rhs[row] = rhs[row] xor rhs[rank]
            }
            pivots += column
            if (pivots.size == rows.size) break
        }
        val pivotSet = pivots.toSet()
        val pivotArray = pivots.toIntArray()
        val free = unknown.indices.filter { it !in pivotSet }.toIntArray()
        if (transform != null) retain(Shape(block.unknownMasks.copyOf(), ecSymbols,
            rows, transform, pivotArray, free, cacheBytes))
        if ((pivots.size until rows.size).any { rhs[it] }) return null
        return Space(unknown, pivots.size, base, pivotArray, rows, rhs, free)
    }
}

private fun LongArray.has(bit: Int): Boolean = (this[bit / 64] and (1L shl (bit % 64))) != 0L
private fun LongArray.set(bit: Int) { this[bit / 64] = this[bit / 64] or (1L shl (bit % 64)) }
private fun LongArray.firstSetBit(): Int {
    for (word in indices) if (this[word] != 0L) return word * 64 + this[word].countTrailingZeroBits()
    return -1
}
private fun LongArray.xorWith(other: LongArray) { for (i in indices) this[i] = this[i] xor other[i] }
