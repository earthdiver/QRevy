package io.github.earthdiver.qrrepair.core

enum class ListDecodingTrialSkip { TOO_MANY_ERASURES, NO_ADDITIONAL_RADIUS, MISMATCH_CAP, UNIQUE_NEARBY_WORD }

/** Incremental list decoding; QR validation runs after each complete list, before more assignments. */
class ListDecodingBlockSearch(
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
    private val softMasks = block.unknownMasks.copyOf().also { masks ->
        nonEnumerablePositions.forEach { if (masks[it] != 0) masks[it] = 255 }
    }
    private var softPending = allowEnumeration && softMasks.any { it.countOneBits() in 1..4 }
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
    private val skipped = linkedSetOf<ListDecodingTrialSkip>()
    private val methods = linkedMapOf<ListDecoderMethod, Int>()
    val methodTrials: Map<ListDecoderMethod, Int> get() = methods.toMap()
    val skipReasons: Set<ListDecodingTrialSkip> get() = skipped.toSet()
    val hasNext: Boolean get() = basePending || softPending || assignment < assignments
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
        if (!basePending && softPending) {
            softPending = false
            advanceSoft(availableBytes)?.let { return it }
        }
        if (!hasNext) return false
        val base = basePending
        basePending = false
        val index = if (base) -1 else assignment++
        val erased = if (base) plan.erased else plan.remainingErasures
        val n = block.codewords.size - erased.size
        val k = block.numDataCodewords
        val radius = (n - k) / 2 + 1
        if (n < k || (n - radius).toLong() * (n - radius) <= n.toLong() * (k - 1)) {
            skipped += if (n < k) ListDecodingTrialSkip.TOO_MANY_ERASURES else ListDecodingTrialSkip.NO_ADDITIONAL_RADIUS
            if (!base) assignment = assignments
            return false
        }
        trials++
        val guessed = if (base) block.codewords.copyOf() else plan.assignment(index)
        val before = accumulated.workingBytes
        val beforeCount = accumulated.size
        fun offer(corrected: IntArray, positions: IntArray, listDecoding: ListDecodingDerivation?, reserve: Long = 0) {
            check()
            val mismatches = mismatches(corrected)
            if (mismatches > mismatchLimit) { mismatchRejections++; return }
            if (!accepts(corrected)) return
            val errors = positions.count { it !in erased }
            val selected = if (base) intArrayOf() else plan.selected
            val guessedDisagreements = selected.count { corrected[it] != guessed[it] }
            val margin = if (listDecoding == null) nsym - erased.size - 2 * errors else 0
            val result = PartialCodewordSolver.Result(corrected, positions,
                if (base) 0 else plan.bitCount, selected.size, selected, margin,
                guessedDisagreements * 100 + mismatches * 20 - margin,
                listDecoding = listDecoding, originalKnownMismatches = mismatches)
            val growth = accumulated.workingBytes - before + accumulated.additionalBytes(result)
            if (growth > availableBytes - reserve) throw ListDecodingWorkingMemoryException()
            accumulated.add(result)
        }
        try {
            // Triangle inequality proves a close ordinary result is the only codeword in this
            // hard-decision ball. Healthy blocks and already-disproved QR payloads need no interpolation.
            val ordinary = try { rs.decode(guessed, nsym, erased) } catch (_: ReedSolomonErasures.DecodeException) { null }
            if (ordinary != null) {
                val errors = guessed.indices.count { it !in erased && ordinary.corrected[it] != guessed[it] }
                offer(ordinary.corrected, ordinary.errataPositions, null)
                if (errors + radius < n - k + 1) {
                    skipped += ListDecodingTrialSkip.UNIQUE_NEARBY_WORD
                    return accumulated.size != beforeCount
                }
            }
            // Any additional word has exactly radius disagreements: ordinary RS already
            // covers every smaller distance. A disagreement at an unchanged, fully known
            // original byte costs at least one known bit; assigned/inferred bytes may not.
            val freeErrorSymbols = guessed.indices.count { it !in erased &&
                (original.unknownMasks[it] != 0 || guessed[it] != original.codewords[it]) }
            if (radius - freeErrorSymbols > mismatchLimit) {
                skipped += ListDecodingTrialSkip.MISMATCH_CAP
                return accumulated.size != beforeCount
            }
            val available = availableBytes - (accumulated.workingBytes - before)
            val choice = ListDecodingPlanner.hard(block.codewords.size, k, erased.size, available, check)
                ?: return accumulated.size != beforeCount
            val parameters = choice.parameters
            methods[choice.method] = (methods[choice.method] ?: 0) + 1
            fun emit(corrected: IntArray) {
                val errata = guessed.indices.filter { it in erased || corrected[it] != guessed[it] }.toIntArray()
                val errors = errata.count { it !in erased }
                offer(corrected, errata, ListDecodingDerivation(parameters.radius,
                    if (choice.method == ListDecoderMethod.GURUSWAMI_SUDAN) parameters.multiplicity else 0,
                    if (choice.method == ListDecoderMethod.GURUSWAMI_SUDAN) parameters.listDegree else 0,
                    erased.size, errors, index, choice.method), choice.workingBytes)
            }
            if (choice.method == ListDecoderMethod.WU) {
                WuDecoder(block.codewords.size, k).decode(guessed, erased, radius, available, check, ::emit)
            } else {
                interpolations++
                decoder.decode(guessed, erased, parameters, available, check).forEach(::emit)
            }
        } catch (_: ListDecodingWorkingMemoryException) {
            memoryLimited = true
            if (!base) assignment = assignments
        }
        return accumulated.size != beforeCount
    }

    /** Optional KV pass before enumerating partial-bit assignments. Null means it adds no useful plan. */
    private fun advanceSoft(availableBytes: Long): Boolean? {
        val k = block.numDataCodewords
        val hard = ListDecodingPlanner.hard(block.codewords.size, k, plan.remainingErasures.size, Long.MAX_VALUE, check)
        val maximumWork = hard?.estimatedWork?.let { listDecodingWork(it, assignments.coerceAtLeast(1).toLong()) } ?: Long.MAX_VALUE
        val decoder = KoetterVardyDecoder(block.codewords.size, k)
        val targetErrors = maxOf(1, (softMasks.count { it == 0 } - k) / 2 + 1)
        val parameters = decoder.parameters(softMasks, targetErrors, availableBytes, maximumWork, check) ?: return null
        trials++
        interpolations++
        methods[ListDecoderMethod.KOETTER_VARDY] = 1
        val beforeCount = accumulated.size
        val before = accumulated.workingBytes
        try {
            decoder.decode(block.codewords, softMasks, parameters, availableBytes, check) { corrected, score ->
                check()
                val mismatches = mismatches(corrected)
                if (mismatches > mismatchLimit) { mismatchRejections++; return@decode }
                if (!accepts(corrected)) return@decode
                val errata = corrected.indices.filter { block.unknownMasks[it] != 0 || corrected[it] != block.codewords[it] }.toIntArray()
                val errors = corrected.indices.count { (corrected[it] xor block.codewords[it]) and softMasks[it].inv() and 255 != 0 }
                val derivation = ListDecodingDerivation(0, parameters.multiplicity, parameters.listDegree,
                    softMasks.count { it.countOneBits() > 4 }, errors, -1, ListDecoderMethod.KOETTER_VARDY,
                    score, parameters.degreeBound + 1)
                val result = PartialCodewordSolver.Result(corrected, errata, 0, 0, intArrayOf(), 0,
                    mismatches * 20, listDecoding = derivation, originalKnownMismatches = mismatches)
                if (accumulated.workingBytes - before + accumulated.additionalBytes(result) > availableBytes - parameters.workingBytes) {
                    throw ListDecodingWorkingMemoryException()
                }
                accumulated.add(result)
            }
        } catch (_: ListDecodingWorkingMemoryException) {
            memoryLimited = true
        }
        return accumulated.size != beforeCount
    }
}
