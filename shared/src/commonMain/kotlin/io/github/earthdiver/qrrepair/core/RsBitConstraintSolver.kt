package io.github.earthdiver.qrrepair.core

/** Exact known-bit constraints, not an unknown-error decoder or proof of payload correctness. */
class RsBitConstraintSolver(private val rs: ReedSolomonErasures = ReedSolomonErasures()) {
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
                for (assignment in 0 until (1 shl dimension)) {
                    cancellation.check()
                    val values = BooleanArray(unknownBits.size)
                    free.forEachIndexed { i, column -> values[column] = (assignment and (1 shl i)) != 0 }
                    pivots.forEachIndexed { row, column ->
                        var value = rhs[row]
                        for (f in free) if (rows[row].has(f) && values[f]) value = !value
                        values[column] = value
                    }
                    val corrected = base.copyOf()
                    unknownBits.forEachIndexed { i, bit ->
                        if (values[i]) corrected[bit.codeword] = corrected[bit.codeword] or (1 shl bit.bit)
                    }
                    yield(corrected)
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
            val tempRhs = rhs[rank]; rhs[rank] = rhs[selected]; rhs[selected] = tempRhs
            for (row in rows.indices) if (row != rank && rows[row].has(column)) {
                rows[row].xorWith(rows[rank])
                rhs[row] = rhs[row] xor rhs[rank]
            }
            pivots += column
            if (pivots.size == rows.size) break
        }
        if ((pivots.size until rows.size).any { rhs[it] }) return null
        val pivotSet = pivots.toSet()
        return Space(unknown, pivots.size, base, pivots.toIntArray(), rows, rhs,
            unknown.indices.filter { it !in pivotSet }.toIntArray())
    }
}

private fun LongArray.has(bit: Int): Boolean = (this[bit / 64] and (1L shl (bit % 64))) != 0L
private fun LongArray.set(bit: Int) { this[bit / 64] = this[bit / 64] or (1L shl (bit % 64)) }
private fun LongArray.xorWith(other: LongArray) { for (i in indices) this[i] = this[i] xor other[i] }
