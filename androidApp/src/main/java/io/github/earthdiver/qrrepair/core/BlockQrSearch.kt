package io.github.earthdiver.qrrepair.core

/** Searches bounded block lists while the caller validates and retains complete QR candidates. */
internal class BlockQrSearch(private val rs: ReedSolomonErasures) {
    data class Input(
        val blocks: DeinterleavedQr,
        val version: Int,
        val matrix: QrMatrix,
        val extraction: CodewordExtraction,
        val conventional: ConventionalRsDiagnostic,
        val priorityRawIndices: Set<Int>,
        val phase: RecoveryPhase,
        val basicOnly: Boolean,
        val allowBitEnumeration: Boolean,
        val exactOnly: Boolean,
        val partialBitSearchLimit: Int,
        val useConventionalMargins: Boolean,
    )

    data class Outcome(val parsed: Boolean = false, val failure: Failure? = null)

    sealed interface Failure {
        data class Block(
            val index: Int,
            val erasures: Int,
            val reason: RsFailureReason?,
            val partialGuessedBits: Int,
        ) : Failure
        data object CombinationMemory : Failure
        data class Payload(val stops: Set<CombinationSearchStop>) : Failure
    }

    private val partialSolver = PartialCodewordSolver(rs)

    fun run(
        input: Input,
        trialSpaces: () -> List<RsBitConstraintSolver.Space?>,
        originalBlock: (Int) -> ReedSolomonErasures.Result?,
        availableWorkingBytes: () -> Long,
        cancellation: RecoveryCancellation,
        payloadChecks: RecoveryPayloadChecks,
        onBlockAttempt: () -> Unit,
        onSearch: (SearchCompleteness) -> Unit,
        onStops: (Set<CombinationSearchStop>) -> Unit,
        evaluate: (List<PartialCodewordSolver.Result>) -> Boolean,
    ): Outcome {
        val de = input.blocks
        val blockCandidateResults = ArrayList<List<PartialCodewordSolver.Result>>(de.blocks.size)
        val imageRetry = input.phase == RecoveryPhase.IMAGE_ERASURES
        val imagePlans = if (imageRetry) {
            val margins = imageWordMargins(de, input.extraction, input.matrix)
            de.blocks.mapIndexed { index, block ->
                ImageErasurePlan(margins[index], de.ecSymbolsPerBlock - block.unknownMasks.count { it != 0 })
            }
        } else emptyList()
        var blockWorkingBytes = 0L
        if (imageRetry) {
            // Allow for the original result, all retry outputs, and transient RS work.
            blockWorkingBytes = 8192L + de.blocks.indices.sumOf { index ->
                val bytesPerWord = maxOf(640L, 192L + 64L * (1 + imagePlans[index].trialCount))
                de.blocks[index].codewords.size * bytesPerWord
            }
            if (blockWorkingBytes > availableWorkingBytes()) {
                onStops(setOf(CombinationSearchStop.WORKING_MEMORY_LIMIT))
                return Outcome()
            }
        }
        for ((blockIndex, block) in de.blocks.withIndex()) {
            onBlockAttempt()
            cancellation.check()
            val blockErasures = block.unknownMasks.count { it != 0 }
            if (imageRetry) {
                val results = retryImageErasures(block, de.ecSymbolsPerBlock, imagePlans[blockIndex],
                    input.conventional.blocks[blockIndex], rs, cancellation, originalBlock(blockIndex))
                if (results.isEmpty()) return Outcome()
                blockCandidateResults += results
                continue
            }
            val results = try {
                val nonEnumerable = input.priorityRawIndices.mapNotNullTo(hashSetOf()) { raw ->
                    de.rawLocations.getOrNull(raw)?.takeIf { it.blockIndex == blockIndex }?.localIndex
                }
                val space = if (input.basicOnly) null else trialSpaces()[blockIndex]
                val requiredBytes = blockCandidateWorkingBytes(block, space?.dimension,
                    input.allowBitEnumeration, nonEnumerable, input.exactOnly,
                    de.ecSymbolsPerBlock, input.conventional.blocks.getOrNull(blockIndex)?.margin
                        .takeIf { input.useConventionalMargins },
                    partialBitSearchLimit = input.partialBitSearchLimit)
                // Initializing the spaces can grow the coefficient cache; read its remaining budget afterwards.
                if (requiredBytes > availableWorkingBytes() - blockWorkingBytes) {
                    onStops(setOf(CombinationSearchStop.WORKING_MEMORY_LIMIT))
                    throw ReedSolomonErasures.DecodeException("Exact RS space exceeds working-memory allowance",
                        RsFailureReason.BIT_SPACE_LIMIT)
                }
                blockWorkingBytes += requiredBytes
                partialSolver.decodeWithBitConstraints(
                    block,
                    de.ecSymbolsPerBlock,
                    space = space?.takeIf { !input.basicOnly && (input.phase != RecoveryPhase.ORIGINAL_RS ||
                        (it.dimension == 0 && input.conventional.blocks[blockIndex].status == ConventionalRsStatus.SUCCESS)) },
                    exactOnly = input.exactOnly,
                    allowBitEnumeration = input.allowBitEnumeration,
                    nonEnumerablePositions = nonEnumerable,
                    cancellation = cancellation,
                    maxEnumeratedBits = input.partialBitSearchLimit,
                    reportSearch = onSearch,
                )
            } catch (error: ReedSolomonErasures.DecodeException) {
                return Outcome(failure = Failure.Block(blockIndex, blockErasures, error.reason,
                    blockCandidateResults.sumOf { it.firstOrNull()?.guessedBits ?: 0 }))
            }
            blockCandidateResults += results
        }

        // Validate the best ordered combinations lazily within expanding, memory-bounded windows.
        val combinationStops = linkedSetOf<CombinationSearchStop>()
        var parsedCandidates = 0
        val affordableNodes = ((availableWorkingBytes() - blockWorkingBytes) /
            (256L + de.blocks.size * 16L)).coerceIn(0L, MAX_QR_COMBINATION_TRIALS.toLong()).toInt()
        if (affordableNodes == 0) {
            onStops(setOf(CombinationSearchStop.WORKING_MEMORY_LIMIT))
            return Outcome(failure = Failure.CombinationMemory)
        }
        val combinations = bestFirstCartesianProductSequence(blockCandidateResults, { it.orderingPenalty },
            maxExpandedNodes = affordableNodes,
            onLimitReached = { combinationStops += if (affordableNodes < MAX_QR_COMBINATION_TRIALS)
                CombinationSearchStop.WORKING_MEMORY_LIMIT else CombinationSearchStop.NODE_LIMIT },
        ) { prefix ->
            cancellation.check()
            val prefixData = buildList<Int> {
                for (i in prefix.indices) {
                    val corrected = prefix[i].corrected
                    for (j in 0 until de.blocks[i].numDataCodewords) add(corrected[j])
                }
            }
            payloadChecks.acceptPrefix(ByteArray(prefixData.size) { prefixData[it].toByte() }, input.version)
        }.takeInExpandingWindows(
            initialLimit = INITIAL_QR_COMBINATION_TRIALS,
            maxLimit = MAX_QR_COMBINATION_TRIALS,
            // RS-consistent combinations are diagnostic evidence until the evaluator parses their payload.
            shouldExpand = { parsedCandidates == 0 },
        ) {
            combinationStops += if (parsedCandidates > 0) CombinationSearchStop.SUCCESS_WINDOW
                else CombinationSearchStop.COMBINATION_LIMIT
        }
        for (combination in combinations) {
            cancellation.check()
            if (evaluate(combination)) parsedCandidates++
        }
        onStops(combinationStops)
        return if (parsedCandidates > 0) Outcome(parsed = true)
            else Outcome(failure = Failure.Payload(combinationStops))
    }

    private companion object {
        const val INITIAL_QR_COMBINATION_TRIALS = 4096
        const val MAX_QR_COMBINATION_TRIALS = 65_536
    }
}
