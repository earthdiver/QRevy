package io.github.earthdiver.qrrepair.core

enum class ListDecodingTrialSkip { TOO_MANY_ERASURES, NO_ADDITIONAL_RADIUS, MISMATCH_CAP, UNIQUE_NEARBY_WORD, KV_PARAMETERS_UNAVAILABLE }

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
    private val cache: ListDecodingTrialCache? = null,
    private val additionalErrors: Int = ListDecodingLimit.DEFAULT,
) {
    private val plan = PartialBitTrialPlan(block, maxEnumeratedBits, nonEnumerablePositions = nonEnumerablePositions)
    private val nsym = block.codewords.size - block.numDataCodewords
    private val decoder by lazy { GuruswamiSudanDecoder(block.codewords.size, block.numDataCodewords) }
    private val rs = ReedSolomonErasures()
    private val accumulated = BlockCandidateAccumulator()
    private var basePending = true
    private var baseExtra = 1
    private var assignmentExtra = 1
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
    var reusedTrials: Int = 0
        private set
    var memoryLimited: Boolean = false
        private set
    var mismatchRejections: Int = 0
        private set
    private val skipped = linkedSetOf<ListDecodingTrialSkip>()
    private val methods = linkedMapOf<ListDecoderMethod, Int>()
    private val radiusTrials = linkedMapOf<Int, Int>()
    private val completedRadiusTrials = linkedMapOf<Int, Int>()
    val hardRadiusTrials: Map<Int, Int> get() = radiusTrials.toMap()
    val completedHardRadiusTrials: Map<Int, Int> get() = completedRadiusTrials.toMap()
    val methodTrials: Map<ListDecoderMethod, Int> get() = methods.toMap()
    val skipReasons: Set<ListDecodingTrialSkip> get() = skipped.toSet()
    val hasNext: Boolean get() = basePending || softPending || assignment < assignments
    val workingBytes: Long get() = 8192L + block.codewords.size * 64L + accumulated.workingBytes
    val candidates: List<PartialCodewordSolver.Result> get() = accumulated.results()

    init {
        require(block.codewords.size == original.codewords.size && mismatchLimit >= 0)
        require(additionalErrors in ListDecodingLimit.supported)
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
        val index = if (base) -1 else assignment
        val extra = if (base) baseExtra++ else assignmentExtra++
        if (base) basePending = baseExtra <= additionalErrors
        else if (assignmentExtra > additionalErrors) { assignment++; assignmentExtra = 1 }
        val erased = if (base) plan.erased else plan.remainingErasures
        val n = block.codewords.size - erased.size
        val k = block.numDataCodewords
        val radius = (n - k) / 2 + extra
        if (n < k || (n - radius).toLong() * (n - radius) <= n.toLong() * (k - 1)) {
            skipped += if (n < k) ListDecodingTrialSkip.TOO_MANY_ERASURES else ListDecodingTrialSkip.NO_ADDITIONAL_RADIUS
            if (base) basePending = false
            else if (extra == 1) assignment = assignments
            else { assignment = index + 1; assignmentExtra = 1 }
            return false
        }
        val guessed = if (base) block.codewords.copyOf() else plan.assignment(index)
        val before = accumulated.workingBytes
        val beforeCount = accumulated.size
        val key = if (base && cache != null) ListDecodingTrialCache.Key(block,
            if (extra == 1) intArrayOf() else intArrayOf(extra)) else null
        var collected: ListDecodingTrialCache.Collector? = null
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
            collected?.fit(availableBytes - reserve - growth)
            if (growth > availableBytes - reserve) throw ListDecodingWorkingMemoryException()
            accumulated.add(result)
        }
        try {
            val cached = key?.let { cache?.get(it) }
            if (cached != null) {
                reusedTrials++
                for (candidate in cached) {
                    val positions = guessed.indices.filter { it in erased || candidate.corrected[it] != guessed[it] }.toIntArray()
                    offer(candidate.corrected, positions, candidate.derivation)
                }
                return accumulated.size != beforeCount
            }
            trials++
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
            // Earlier shells and ordinary RS cover the smaller distances. A new word
            // has exactly radius disagreements. A mismatch at an unchanged, fully known
            // original byte costs at least one known bit; assigned/inferred bytes may not.
            val freeErrorSymbols = guessed.indices.count { it !in erased &&
                (original.unknownMasks[it] != 0 || guessed[it] != original.codewords[it]) }
            if (radius - freeErrorSymbols > mismatchLimit) {
                skipped += ListDecodingTrialSkip.MISMATCH_CAP
                return accumulated.size != beforeCount
            }
            val available = availableBytes - (accumulated.workingBytes - before)
            val choice = ListDecodingPlanner.hard(block.codewords.size, k, erased.size, available, extra, check)
                ?: return accumulated.size != beforeCount
            val parameters = choice.parameters
            methods[choice.method] = (methods[choice.method] ?: 0) + 1
            radiusTrials[radius] = (radiusTrials[radius] ?: 0) + 1
            collected = key?.let { cache?.collect(it) }
            collected?.fit(availableBytes - (accumulated.workingBytes - before) - choice.workingBytes)
            ordinary?.let { collected?.add(it.corrected, null,
                availableBytes - (accumulated.workingBytes - before) - choice.workingBytes) }
            fun emit(corrected: IntArray) {
                val errata = guessed.indices.filter { it in erased || corrected[it] != guessed[it] }.toIntArray()
                val errors = errata.count { it !in erased }
                val derivation = ListDecodingDerivation(parameters.radius,
                    if (choice.method == ListDecoderMethod.GURUSWAMI_SUDAN) parameters.multiplicity else 0,
                    if (choice.method == ListDecoderMethod.GURUSWAMI_SUDAN) parameters.listDegree else 0,
                    erased.size, errors, index, choice.method)
                offer(corrected, errata, derivation, choice.workingBytes)
                // Retain every root, including those rejected by this trial's pins or limits.
                collected?.add(corrected, derivation, availableBytes - (accumulated.workingBytes - before) - choice.workingBytes)
            }
            if (choice.method == ListDecoderMethod.WU) {
                WuDecoder(block.codewords.size, k).decode(guessed, erased, radius, available, check, ::emit)
            } else {
                interpolations++
                decoder.decode(guessed, erased, parameters, available, check).forEach(::emit)
            }
            collected?.complete(availableBytes - (accumulated.workingBytes - before))
            completedRadiusTrials[radius] = (completedRadiusTrials[radius] ?: 0) + 1
        } catch (_: ListDecodingWorkingMemoryException) {
            memoryLimited = true
            if (base) basePending = false else assignment = assignments
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
        val parameters = decoder.parameters(softMasks, targetErrors, availableBytes, maximumWork, check) ?: run {
            skipped += ListDecodingTrialSkip.KV_PARAMETERS_UNAVAILABLE
            return null
        }
        val beforeCount = accumulated.size
        val before = accumulated.workingBytes
        val key = cache?.let { ListDecodingTrialCache.Key(block,
            softMasks + intArrayOf(parameters.multiplicity, parameters.listDegree, parameters.degreeBound)) }
        var collected: ListDecodingTrialCache.Collector? = null
        fun offer(corrected: IntArray, derivation: ListDecodingDerivation, reserve: Long) {
            check()
            val mismatches = mismatches(corrected)
            if (mismatches > mismatchLimit) { mismatchRejections++; return }
            if (!accepts(corrected)) return
            val errata = corrected.indices.filter { block.unknownMasks[it] != 0 || corrected[it] != block.codewords[it] }.toIntArray()
            val result = PartialCodewordSolver.Result(corrected, errata, 0, 0, intArrayOf(), 0,
                mismatches * 20, listDecoding = derivation, originalKnownMismatches = mismatches)
            val growth = accumulated.workingBytes - before + accumulated.additionalBytes(result)
            collected?.fit(availableBytes - reserve - growth)
            if (growth > availableBytes - reserve) throw ListDecodingWorkingMemoryException()
            accumulated.add(result)
        }
        try {
            val cached = key?.let { cache[it] }
            if (cached != null) {
                reusedTrials++
                cached.forEach { offer(it.corrected, requireNotNull(it.derivation), 0) }
                return accumulated.size != beforeCount
            }
            trials++
            interpolations++
            methods[ListDecoderMethod.KOETTER_VARDY] = 1
            collected = key?.let { cache.collect(it) }
            collected?.fit(availableBytes - parameters.workingBytes)
            decoder.decode(block.codewords, softMasks, parameters, availableBytes, check) { corrected, score ->
                val errors = corrected.indices.count { (corrected[it] xor block.codewords[it]) and softMasks[it].inv() and 255 != 0 }
                val derivation = ListDecodingDerivation(0, parameters.multiplicity, parameters.listDegree,
                    softMasks.count { it.countOneBits() > 4 }, errors, -1, ListDecoderMethod.KOETTER_VARDY,
                    score, parameters.degreeBound + 1)
                offer(corrected, derivation, parameters.workingBytes)
                collected?.add(corrected, derivation,
                    availableBytes - (accumulated.workingBytes - before) - parameters.workingBytes)
            }
            collected?.complete(availableBytes - (accumulated.workingBytes - before))
        } catch (_: ListDecodingWorkingMemoryException) {
            memoryLimited = true
        }
        return accumulated.size != beforeCount
    }
}
