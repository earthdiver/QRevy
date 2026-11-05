package io.github.earthdiver.qrrepair.core

import kotlin.time.TimeSource

/** One operation's cumulative allowance, shared across formats and sampling attempts. */
class KnownBitErrorBudget(val maxTrials: Long = 1_000_000, val maxMillis: Long = 30_000) {
    init { require(maxTrials >= 0 && maxMillis >= 0) }
    var trials = 0L
        private set
    private var elapsed = 0L
    private var start: TimeSource.Monotonic.ValueTimeMark? = null
    fun check() {
        if (elapsed + (start?.elapsedNow()?.inWholeMilliseconds ?: 0) >= maxMillis) throw KnownBitSearchLimit(timedOut = true)
    }
    fun trial() { check(); if (trials >= maxTrials) throw KnownBitSearchLimit(); trials++ }
    fun <T> measure(action: () -> T): T {
        check()
        if (start != null) return action()
        start = TimeSource.Monotonic.markNow()
        try { return action() } finally { elapsed += start!!.elapsedNow().inWholeMilliseconds; start = null }
    }
}
class KnownBitSearchLimit(val timedOut: Boolean = false) : RuntimeException()

/** Projects parity checks modulo erased columns before trying known-bit error positions. */
class KnownBitErrorSearch(private val block: RsBlockWork, private val ec: Int,
    private val cancellation: RecoveryCancellation = RecoveryCancellation.NONE) {
    data class Hypothesis(val block: RsBlockWork, val space: RsBitConstraintSolver.Space, val errors: List<RsBitConstraintSolver.Bit>)
    companion object {
        fun workingBytes(length: Int, ec: Int): Long = 4096L + length * 8L * 128 +
            ec * 8L * (64L + ((length * 8 + 63) / 64) * 16L + ((ec * 8 + 63) / 64) * 8L) + (length * 8L + ec * 8L) * (64L + ((ec * 8 + 63) / 64) * 8L)
    }
    init {
        require(ec > 0 && block.numDataCodewords > 0 && block.codewords.size <= 255)
        require(block.codewords.size == block.numDataCodewords + ec)
        require(block.unknownMasks.size == block.codewords.size)
        require(block.codewords.all { it in 0..255 } && block.unknownMasks.all { it in 0..255 })
    }
    private val rs = ReedSolomonErasures()
    private val width = (ec * 8 + 63) / 64
    private fun xor(a: LongArray, b: LongArray) { for (i in a.indices) a[i] = a[i] xor b[i] }
    private fun parity(words: IntArray): LongArray = LongArray(width).also { v ->
        for (i in 0 until ec * 8) if (words[block.numDataCodewords + i / 8] and (1 shl (i % 8)) != 0)
            v[i / 64] = v[i / 64] or (1L shl (i % 64))
    }
    private val columns = Array(block.codewords.size * 8) { pos ->
        cancellation.check()
        if (pos / 8 < block.numDataCodewords) {
            val data = IntArray(block.numDataCodewords); data[pos / 8] = 1 shl (pos % 8)
            parity(rs.encode(data, ec))
        } else LongArray(width).also { v -> val bit = pos - block.numDataCodewords * 8
            v[bit / 64] = 1L shl (bit % 64) }
    }
    private val basis = arrayOfNulls<LongArray>(ec * 8)
    private fun reduce(input: LongArray): LongArray = input.copyOf().also { v ->
        for (i in basis.indices.reversed()) if (v[i / 64] and (1L shl (i % 64)) != 0L)
            basis[i]?.let { xor(v, it) }
    }
    private val known = columns.indices.filter { block.unknownMasks[it / 8] and (1 shl (it % 8)) == 0 }
    private val target: LongArray
    init {
        for (pos in columns.indices) if (block.unknownMasks[pos / 8] and (1 shl (pos % 8)) != 0) {
            cancellation.check()
            val v = reduce(columns[pos])
            val pivot = basis.indices.reversed().firstOrNull { v[it / 64] and (1L shl (it % 64)) != 0L }
            if (pivot != null) basis[pivot] = v
        }
        val syndrome = LongArray(width)
        for (pos in known) if (block.codewords[pos / 8] and (1 shl (pos % 8)) != 0) xor(syndrome, columns[pos])
        target = reduce(syndrome)
        for (pos in known) columns[pos] = reduce(columns[pos])
    }
    // Hash the projected column or its required partner without allocating a temporary vector.
    private fun bucket(column: LongArray, complement: Boolean, mask: Int): Int {
        var hash = -3750763034362895579L
        for (i in column.indices) {
            val word = if (complement) column[i] xor target[i] else column[i]
            hash = (hash xor word) * 1099511628211L
            hash = hash xor (hash ushr 32)
        }
        return (hash xor (hash ushr 16)).toInt() and mask
    }

    fun hypotheses(maxErrors: Int, protected: Set<RsBitConstraintSolver.Bit>, budget: KnownBitErrorBudget,
        reservePairIndex: (Long) -> Boolean = { true }): Sequence<Hypothesis> = sequence {
        require(maxErrors in 0..2)
        val eligible = known.filter { RsBitConstraintSolver.Bit(it / 8, it % 8) !in protected }
        val solver = RsBitConstraintSolver()
        suspend fun SequenceScope<Hypothesis>.offer(a: Int = -1, b: Int = -1) {
            cancellation.check(); budget.trial()
            if (target.indices.any { target[it] xor (if (a < 0) 0L else columns[a][it]) xor
                    (if (b < 0) 0L else columns[b][it]) != 0L }) return
            val changed = block.copyDeep()
            val errors = listOf(a, b).filter { it >= 0 }.map { pos ->
                changed.codewords[pos / 8] = changed.codewords[pos / 8] xor (1 shl (pos % 8))
                RsBitConstraintSolver.Bit(pos / 8, pos % 8)
            }
            val space = solver.analyze(changed, ec, cancellation) ?: return
            yield(Hypothesis(changed, space, errors))
        }
        offer()
        if (maxErrors >= 1) for (a in eligible) offer(a)
        if (maxErrors < 2 || eligible.size < 2) return@sequence
        val bucketCount = (eligible.size - 1).takeHighestOneBit() * 2
        val indexBytes = 64L + Int.SIZE_BYTES * (bucketCount + eligible.size)
        if (!reservePairIndex(indexBytes)) {
            for (i in eligible.indices) for (j in 0 until i) offer(eligible[i], eligible[j])
            return@sequence
        }
        val heads = IntArray(bucketCount) { -1 }
        val next = IntArray(eligible.size)
        val mask = bucketCount - 1
        // Reverse insertion leaves each chain in ascending input order.
        for (j in eligible.indices.reversed()) {
            cancellation.check()
            budget.check()
            val key = bucket(columns[eligible[j]], false, mask)
            next[j] = heads[key]
            heads[key] = j
        }
        for (i in eligible.indices) {
            cancellation.check()
            budget.check()
            val a = eligible[i]
            var j = heads[bucket(columns[a], true, mask)]
            while (j >= 0 && j < i) {
                offer(a, eligible[j]) // offer compares the full vectors, including on hash collisions.
                j = next[j]
            }
        }
    }
}
