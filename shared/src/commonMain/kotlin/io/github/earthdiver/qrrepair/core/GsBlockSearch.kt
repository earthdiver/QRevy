package io.github.earthdiver.qrrepair.core

enum class GsTrialSkip { TOO_MANY_ERASURES, NO_ADDITIONAL_RADIUS, MISMATCH_CAP, UNIQUE_NEARBY_WORD }

/** Incremental GS trials: the caller can validate QR combinations after every complete root list. */
class GsBlockSearch(
    private val block: RsBlockWork,
    private val original: RsBlockWork,
    initialCandidates: List<PartialCodewordSolver.Result>,
    maxEnumeratedBits: Int,
    private val allowEnumeration: Boolean,
    nonEnumerablePositions: Set<Int>,
    private val mismatchLimit: Int,
    private val accepts: (IntArray) -> Boolean = { true },
    private val check: () -> Unit = {},
) {
    private val plan = PartialBitTrialPlan(block, maxEnumeratedBits, nonEnumerablePositions = nonEnumerablePositions)
    private val nsym = block.codewords.size - block.numDataCodewords
    private val decoder by lazy { GuruswamiSudanDecoder(block.codewords.size, block.numDataCodewords) }
    private val rs = ReedSolomonErasures()
    private val accumulated = BlockCandidateAccumulator()
    private var basePending = true
    private var assignment = 0
    private val assignments = if (allowEnumeration && plan.selected.isNotEmpty()) 1 shl plan.bitCount else 0
    var trials: Int = 0
        private set
    var interpolations: Int = 0
        private set
    var memoryLimited: Boolean = false
        private set
    var mismatchRejections: Int = 0
        private set
    private val skipped = linkedSetOf<GsTrialSkip>()
    val skipReasons: Set<GsTrialSkip> get() = skipped.toSet()
    val hasNext: Boolean get() = basePending || assignment < assignments
    val workingBytes: Long get() = 8192L + block.codewords.size * 64L + accumulated.workingBytes
    val candidates: List<PartialCodewordSolver.Result> get() = accumulated.results()

    init {
        require(block.codewords.size == original.codewords.size && mismatchLimit >= 0)
        initialCandidates.forEach { result ->
            val mismatches = mismatches(result.corrected)
            if (mismatches <= mismatchLimit && accepts(result.corrected)) {
                accumulated.add(result.copy(originalKnownMismatches = mismatches))
            } else if (mismatches > mismatchLimit) mismatchRejections++
        }
    }

    private fun mismatches(values: IntArray): Int = values.indices.sumOf {
        ((values[it] xor original.codewords[it]) and original.unknownMasks[it].inv() and 255).countOneBits()
    }

    /** availableBytes excludes all live block-search states, including this one's current state. */
    fun advance(availableBytes: Long): Boolean {
        check()
        if (!hasNext) return false
        val base = basePending
        basePending = false
        val index = if (base) -1 else assignment++
        val erased = if (base) plan.erased else plan.remainingErasures
        val n = block.codewords.size - erased.size
        val k = block.numDataCodewords
        val radius = (n - k) / 2 + 1
        if (n < k || (n - radius).toLong() * (n - radius) <= n.toLong() * (k - 1)) {
            skipped += if (n < k) GsTrialSkip.TOO_MANY_ERASURES else GsTrialSkip.NO_ADDITIONAL_RADIUS
            if (!base) assignment = assignments
            return false
        }
        trials++
        val guessed = if (base) block.codewords.copyOf() else plan.assignment(index)
        val before = accumulated.workingBytes
        val beforeCount = accumulated.size
        fun offer(corrected: IntArray, positions: IntArray, gs: GsDerivation?, reserve: Long = 0) {
            check()
            val mismatches = mismatches(corrected)
            if (mismatches > mismatchLimit) { mismatchRejections++; return }
            if (!accepts(corrected)) return
            val errors = positions.count { it !in erased }
            val selected = if (base) intArrayOf() else plan.selected
            val guessedDisagreements = selected.count { corrected[it] != guessed[it] }
            val margin = if (gs == null) nsym - erased.size - 2 * errors else 0
            val result = PartialCodewordSolver.Result(corrected, positions,
                if (base) 0 else plan.bitCount, selected.size, selected, margin,
                guessedDisagreements * 100 + mismatches * 20 - margin,
                gs = gs, originalKnownMismatches = mismatches)
            val growth = accumulated.workingBytes - before + accumulated.additionalBytes(result)
            if (growth > availableBytes - reserve) throw GsWorkingMemoryException()
            accumulated.add(result)
        }
        try {
            // Triangle inequality proves a close ordinary result is the only codeword in this
            // GS ball. Healthy blocks and already-disproved QR payloads need no interpolation.
            val ordinary = try { rs.decode(guessed, nsym, erased) } catch (_: ReedSolomonErasures.DecodeException) { null }
            if (ordinary != null) {
                val errors = guessed.indices.count { it !in erased && ordinary.corrected[it] != guessed[it] }
                offer(ordinary.corrected, ordinary.errataPositions, null)
                if (errors + radius < n - k + 1) {
                    skipped += GsTrialSkip.UNIQUE_NEARBY_WORD
                    return accumulated.size != beforeCount
                }
            }
            // Any additional word has exactly radius disagreements: ordinary RS already
            // covers every smaller distance. A disagreement at an unchanged, fully known
            // original byte costs at least one known bit; assigned/inferred bytes may not.
            val freeErrorSymbols = guessed.indices.count { it !in erased &&
                (original.unknownMasks[it] != 0 || guessed[it] != original.codewords[it]) }
            if (radius - freeErrorSymbols > mismatchLimit) {
                skipped += GsTrialSkip.MISMATCH_CAP
                return accumulated.size != beforeCount
            }
            val parameters = decoder.parameters(erased.size, availableBytes - (accumulated.workingBytes - before), check)
                ?: return accumulated.size != beforeCount
            interpolations++
            val results = decoder.decode(guessed, erased, parameters,
                availableBytes - (accumulated.workingBytes - before), check)
            for (corrected in results) {
                val errata = guessed.indices.filter { it in erased || corrected[it] != guessed[it] }.toIntArray()
                val errors = errata.count { it !in erased }
                offer(corrected, errata, GsDerivation(parameters.radius, parameters.multiplicity,
                    parameters.listDegree, erased.size, errors, index), parameters.workingBytes)
            }
        } catch (_: GsWorkingMemoryException) {
            memoryLimited = true
            if (!base) assignment = assignments
        }
        return accumulated.size != beforeCount
    }
}
