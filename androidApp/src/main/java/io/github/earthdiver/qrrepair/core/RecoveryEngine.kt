package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.PriorityQueue

data class CodewordRepairInfo(
    val rawIndex: Int,
    val blockIndex: Int,
    val localIndex: Int,
    val originalUnknownMask: Int,
    val postConstraintUnknownMask: Int,
    val rsErrata: Boolean,
    val partialEnumerated: Boolean,
    /** bit index is 0=MSB .. 7=LSB */
    val constraintKindsByBit: Map<Int, PayloadConstraintSolver.ConstraintKind> = emptyMap(),
    val bitConstraintSolved: Boolean = false,
    val listDecoding: ListDecodingDerivation? = null,
    /** Unmasked values assigned by structure constraints, before any later RS correction. */
    val constraintValuesByBit: Map<Int, Int> = emptyMap(),
)

data class RecoveryCandidate(
    val version: Int,
    val ecLevel: ErrorCorrectionLevel,
    val dataMask: Int,
    val text: String?,
    val dataBytes: ByteArray,
    val formatMatches: Int,
    val formatMismatches: Int,
    val originalErasedCodewords: Int,
    val erasedCodewords: Int,
    val correctedErrataSymbols: Int,
    val knownBitMismatchesAfterCorrection: Int,
    val knownBitsCompared: Int,
    val correctedInterleaved: IntArray,
    val extraction: CodewordExtraction,
    val constraintFilledBits: Int = 0,
    val constraintPlans: Int = 0,
    val constraintEvidenceBits: Int = 0,
    val inferredMode: Int? = null,
    val inferredCount: Int? = null,
    val partialGuessedBits: Int = 0,
    val minRsMargin: Int = 0,
    val repairInfo: List<CodewordRepairInfo> = emptyList(),
    val constraintForcedByUserSetting: Boolean = false,
    val singleSegmentStatus: SingleSegmentStatus? = null,
    val rsBitDiagnostic: RsBitDiagnostic? = null,
    /** Explicit priority guesses, never original observations or permanent manual pins. */
    val priorityAssignments: Map<Coord, ModuleValue> = emptyMap(),
    /** Other derivations of exactly the same QR; each retains its own metrics and provenance. */
    val alternativeDerivations: List<RecoveryCandidate> = emptyList(),
    /** Only blocks with equivalent routes; route 1 is the representative used by repairInfo. */
    val blockDerivations: Map<Int, List<PartialCodewordSolver.Derivation>> = emptyMap(),
    val combinationSearch: CombinationSearchSummary = CombinationSearchSummary(),
    val formatSelection: FormatSelection? = null,
    val formatRoute: FormatSelectionReason? = null,
    val fnc1ApplicationIndicator: Int? = null,
    val structuredAppend: StructuredAppendInfo? = null,
    val displayConditions: RecoveryDisplayConditions? = null,
    val manualDataEccMismatches: Int? = null,
    val manualDataEccCompared: Int? = null,
    val conventionalRsDiagnostic: ConventionalRsDiagnostic? = null,
    val actualBlockMethods: List<RecoveryBlockMethod> = emptyList(),
    val searchDiagnostic: RecoverySearchDiagnostic? = null,
    val derivationsOmitted: Boolean = false,
    val listDecodingMismatchLimit: Int? = null,
) {
    val priorityGuessedBits: Int get() = priorityAssignments.size
    val payloadParsed: Boolean get() = text != null
    val knownAgreementScore: Int get() = knownBitsCompared - knownBitMismatchesAfterCorrection
    val knownAgreementRatio: Double get() = if (knownBitsCompared == 0) 0.0 else knownAgreementScore.toDouble() / knownBitsCompared
    val score: Int
        get() = (if (payloadParsed) -200_000 else 0) +
            formatMismatches * 500 - formatMatches * 6 +
            knownBitMismatchesAfterCorrection * 300 - (knownBitsCompared / 12) +
            correctedErrataSymbols * 5 + erasedCodewords * 2 +
            partialGuessedBits * 2 - constraintFilledBits * 3 - minRsMargin * 12
}

enum class RecoveryFailureStage { EXTRACTION, DEINTERLEAVE, REED_SOLOMON, PAYLOAD_VALIDATION }

enum class RecoveryFailureReason {
    ERASURE_CAPACITY_EXCEEDED,
    BOUNDED_BIT_SEARCH_LIMIT,
    ERROR_ERASURE_CAPACITY_EXCEEDED,
    NO_RS_CONSISTENT_ASSIGNMENT,
    RS_CORRECTION_FAILED,
    QR_COMBINATION_SEARCH_LIMIT,
    KNOWN_DATA_ECC_MISMATCH_LIMIT,
    KNOWN_BIT_CONSTRAINT_CONFLICT,
    RS_BIT_SPACE_LIMIT,
    FORMAT_EDIT_CONFLICT,
    FORMAT_MISMATCH_LIMIT,
    MANUAL_PIN_CONFLICT,
    SINGLE_SEGMENT_CONDITION_UNAVAILABLE,
}

internal fun classifyRsFailure(reason: RsFailureReason?, erasureShortfallBits: Int): RecoveryFailureReason = when (reason) {
    RsFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT -> RecoveryFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT
    RsFailureReason.BIT_SPACE_LIMIT -> RecoveryFailureReason.RS_BIT_SPACE_LIMIT
    RsFailureReason.BOUNDED_BIT_SEARCH_LIMIT -> RecoveryFailureReason.BOUNDED_BIT_SEARCH_LIMIT
    RsFailureReason.NO_CONSISTENT_ASSIGNMENT -> RecoveryFailureReason.NO_RS_CONSISTENT_ASSIGNMENT
    RsFailureReason.ERROR_ERASURE_CAPACITY_EXCEEDED -> RecoveryFailureReason.ERROR_ERASURE_CAPACITY_EXCEEDED
    RsFailureReason.ERASURE_CAPACITY_EXCEEDED -> RecoveryFailureReason.ERASURE_CAPACITY_EXCEEDED
    RsFailureReason.CORRECTION_FAILED, null -> if (erasureShortfallBits > 0)
        RecoveryFailureReason.ERASURE_CAPACITY_EXCEEDED else RecoveryFailureReason.RS_CORRECTION_FAILED
}

/** Best diagnostic from attempts that did not produce an RS-consistent candidate. */
data class RecoveryFailure(
    val ecLevel: ErrorCorrectionLevel,
    val dataMask: Int,
    val formatMatches: Int,
    val formatMismatches: Int,
    val formatKnownBits: Int,
    val stage: RecoveryFailureStage,
    val reason: RecoveryFailureReason? = null,
    val failedBlockIndex: Int? = null,
    val rsBlockCount: Int? = null,
    val failedBlockErasures: Int? = null,
    val ecSymbolsPerBlock: Int? = null,
    val originalErasedCodewords: Int = 0,
    val erasedCodewords: Int = 0,
    val rsShortfallBits: Int? = null,
    val constraintFilledBits: Int = 0,
    val partialGuessedBits: Int = 0,
    val knownBitMismatches: Int? = null,
    val knownBitsCompared: Int? = null,
    val knownBitMismatchLimit: Int? = null,
    val formatMismatchLimit: Int? = null,
    val rsBitDiagnostic: RsBitDiagnostic? = null,
    val manualConflicts: List<ManualCellConflict> = emptyList(),
    val combinationSearch: CombinationSearchSummary = CombinationSearchSummary(),
    val displayConditions: RecoveryDisplayConditions? = null,
    val conventionalRsDiagnostic: ConventionalRsDiagnostic? = null,
    val searchDiagnostic: RecoverySearchDiagnostic? = null,
)

class RecoveryEngine(private val rs: ReedSolomonErasures = ReedSolomonErasures()) {
    private companion object {
        const val INITIAL_QR_COMBINATION_TRIALS = 4096
        const val MAX_QR_COMBINATION_TRIALS = 65_536
        const val EARLY_LIST_ALLOWANCE_MILLIS = 1_000L
    }

    private val partialSolver = PartialCodewordSolver(rs)
    private val bitSolver = RsBitConstraintSolver(rs)

    /** General diagnostic recognition includes control prefixes and Hanzi. */
    fun hasCompletePayloadHeader(
        matrix: QrMatrix,
        formatConstraint: QrFormatConstraint? = null,
        maxFormatMismatches: Int? = null,
        manualEdits: Map<Coord, ModuleValue> = emptyMap(),
    ): Boolean = hasPayloadHeader(matrix, formatConstraint, maxFormatMismatches, manualEdits, trustOnly = false)

    /** Eligibility for suffix forcing; not proof that an erased second segment is absent. */
    fun hasTrustablePayloadHeader(
        matrix: QrMatrix,
        formatConstraint: QrFormatConstraint? = null,
        maxFormatMismatches: Int? = null,
        manualEdits: Map<Coord, ModuleValue> = emptyMap(),
    ): Boolean = hasPayloadHeader(matrix, formatConstraint, maxFormatMismatches, manualEdits, trustOnly = true)

    private fun hasPayloadHeader(
        matrix: QrMatrix,
        formatConstraint: QrFormatConstraint?,
        maxFormatMismatches: Int?,
        manualEdits: Map<Coord, ModuleValue>,
        trustOnly: Boolean,
    ): Boolean {
        require(maxFormatMismatches == null || maxFormatMismatches in 0..30)
        val version = (matrix.dimension - 17) / 4
        val effectiveLimit = maxFormatMismatches
        val ignored = FormatEditPolicy.ignoredComparisonCoords(version, formatConstraint,
            FormatEditPolicy.manualValues(matrix) + manualEdits)
        var formats = FormatSolver.ranked(matrix, ignored)
            .filter { formatConstraint?.permits(it.ecLevel, it.dataMask) != false }
            .filter { effectiveLimit == null || it.mismatches <= effectiveLimit }
        if (trustOnly) {
            if (FormatEditPolicy.conflict(version, formatConstraint,
                    FormatEditPolicy.manualValues(matrix) + manualEdits) != null) return false
            // Match recovery's automatic BCH confirmation; unrelated masks must not enable the option.
            if (formatConstraint == null) {
                FormatSolver.trustedCandidate(matrix)?.takeIf {
                    nonRsManualConflicts(matrix, version, it.ecLevel, it.dataMask).isEmpty()
                }?.let { trusted -> formats = formats.filter { it.maskedCodeword == trusted.maskedCodeword } }
            }
        }
        val assignedPriority = if (trustOnly && matrix.prioritySearchCoordinates().isNotEmpty()) matrix.copy().apply {
            matrix.prioritySearchCoordinates().forEach { (x, y) -> set(x, y, ModuleValue.ZERO, 1f, ModuleSource.USER) }
        } else matrix
        for (format in formats) {
            val extraction = runCatching { CodewordExtractor.extract(matrix, version, format.dataMask) }.getOrNull() ?: continue
            val de = runCatching { QrBlockInterleaver.deinterleave(extraction, version, format.ecLevel) }.getOrNull() ?: continue
            val dataBytes = de.blocks.flatMap { block ->
                (0 until block.numDataCodewords).map { block.codewords[it] }
            }.toIntArray()
            val unknownMasks = de.blocks.flatMap { block ->
                (0 until block.numDataCodewords).map { block.unknownMasks[it] }
            }.toIntArray()
            val priorityMasks = if (assignedPriority !== matrix) {
                val assigned = QrBlockInterleaver.deinterleave(
                    CodewordExtractor.extract(assignedPriority, version, format.dataMask), version, format.ecLevel)
                val assignedUnknown = assigned.blocks.flatMap { it.unknownMasks.take(it.numDataCodewords) }
                IntArray(unknownMasks.size) { unknownMasks[it] and assignedUnknown[it].inv() }
            } else IntArray(unknownMasks.size)
            if (if (trustOnly) possibleSingleSegmentHeader(dataBytes, unknownMasks, priorityMasks, version)
                else completePayloadHeaderAvailable(dataBytes, unknownMasks, version)) return true
        }
        return false
    }

    /** Candidate-only convenience for callers that do not display search diagnostics. */
    fun recover(
        matrix: QrMatrix,
        maxFormatCandidates: Int = 32,
        trustKnownPayloadCount: Boolean = false,
        formatConstraint: QrFormatConstraint? = null,
        maxFormatMismatches: Int? = null,
        maxKnownDataEccMismatches: Int? = null,
        manualEdits: Map<Coord, ModuleValue> = emptyMap(),
        retentionBudget: RecoveryRetentionBudget = RecoveryRetentionBudget(),
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        displayLockManualBits: Boolean? = null,
        partialBitSearchLimit: Int = PartialBitSearchLimit.DEFAULT,
        listDecodingAdditionalErrors: Int = ListDecodingLimit.DEFAULT,
        listDecodingBudget: ListDecodingBudget = ListDecodingBudget(),
        onPriorityProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<RecoveryCandidate> = recoverWithDiagnostics(
        matrix, maxFormatCandidates, trustKnownPayloadCount, formatConstraint, maxFormatMismatches,
        maxKnownDataEccMismatches, manualEdits, retentionBudget, cancellation, displayLockManualBits, partialBitSearchLimit = partialBitSearchLimit, listDecodingAdditionalErrors = listDecodingAdditionalErrors, listDecodingBudget = listDecodingBudget, onPriorityProgress = onPriorityProgress,
    ).candidates

    /** Returns the candidates and diagnostic evidence from this invocation as one value. */
    fun recoverWithDiagnostics(
        matrix: QrMatrix,
        maxFormatCandidates: Int = 32,
        trustKnownPayloadCount: Boolean = false,
        formatConstraint: QrFormatConstraint? = null,
        maxFormatMismatches: Int? = null,
        maxKnownDataEccMismatches: Int? = null,
        manualEdits: Map<Coord, ModuleValue> = emptyMap(),
        retentionBudget: RecoveryRetentionBudget = RecoveryRetentionBudget(),
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        displayLockManualBits: Boolean? = null,
        stopRequested: () -> Boolean = { false },
        partialBitSearchLimit: Int = PartialBitSearchLimit.DEFAULT,
        listDecodingAdditionalErrors: Int = ListDecodingLimit.DEFAULT,
        listDecodingBudget: ListDecodingBudget = ListDecodingBudget(),
        onPriorityProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): RecoveryOutcome {
        require(partialBitSearchLimit in PartialBitSearchLimit.supported)
        require(listDecodingAdditionalErrors in ListDecodingLimit.supported)
        require(maxFormatMismatches == null || maxFormatMismatches in 0..30)
        require(maxKnownDataEccMismatches == null || maxKnownDataEccMismatches in 0..MAX_QR_DATA_ECC_BITS)
        val priority = matrix.prioritySearchCoordinates()
        require(priority.size <= 14) { "Priority search is limited to fourteen modules" }
        if (priority.isNotEmpty()) {
            val codewordCoords = CodewordExtractor.extract(matrix, (matrix.dimension - 17) / 4, 0).codewords
                .flatMapTo(hashSetOf()) { word -> word.coordinates.map { it.x to it.y } }
            require(priority.all { it in codewordCoords }) { "Priority search is only valid for data/ECC modules" }
        }
        val run = RecoveryRunState(matrix, retentionBudget, RecoveryCancellation {
            cancellation.check()
            stopRequested()
        }, partialBitSearchLimit, listDecodingBudget, listDecodingAdditionalErrors)
        val conditions = RecoveryDisplayConditions(trustKnownPayloadCount, displayLockManualBits,
            formatConstraint, maxFormatMismatches, maxKnownDataEccMismatches, partialBitSearchLimit, listDecodingAdditionalErrors)
        val manual = FormatEditPolicy.manualValues(matrix) + manualEdits
        // Share one immutable summary across potentially thousands of retained derivations.
        val publishedDiagnostic by lazy(LazyThreadSafetyMode.NONE) { run.searchDiagnostic() }
        fun annotate(candidate: RecoveryCandidate): RecoveryCandidate {
            cancellation.check()
            var manualMismatches = 0
            var manualCompared = 0
            candidate.extraction.codewords.forEachIndexed { raw, word ->
                cancellation.check()
                word.coordinates.forEachIndexed bitLoop@{ bit, coord ->
                    val observed = manual[coord]?.bitOrNull() ?: return@bitLoop
                    manualCompared++
                    val expected = ((candidate.correctedInterleaved[raw] shr (7 - bit)) and 1) xor
                        if (CodewordExtractor.maskApplies(candidate.dataMask, coord.x, coord.y)) 1 else 0
                    if (observed != expected) manualMismatches++
                }
            }
            return candidate.copy(combinationSearch = run.combinationSearch, displayConditions = conditions,
                searchDiagnostic = publishedDiagnostic,
                formatSelection = FormatSelection(formatConstraint?.ecLevel != null, formatConstraint?.dataMask != null),
                manualDataEccMismatches = manualMismatches, manualDataEccCompared = manualCompared,
                alternativeDerivations = candidate.alternativeDerivations.map(::annotate))
        }
        fun search(phase: RecoveryPhase) {
            run.phase = phase
            recoverRun(run, matrix, maxFormatCandidates, trustKnownPayloadCount, formatConstraint,
                maxKnownDataEccMismatches = maxKnownDataEccMismatches, maxFormatMismatches = maxFormatMismatches,
                manualEdits = manualEdits, onPriorityProgress = onPriorityProgress)
        }
        fun skipLists(reason: ListDecodingStatus) {
            run.listDecodingStatuses.keys.forEach { stage ->
                if (run.listDecodingStatuses[stage] == ListDecodingStatus.NOT_REACHED)
                    run.listDecodingStatuses[stage] = reason
            }
        }
        fun searchLists(early: Boolean) {
            val stage = if (early) ListDecodingStage.WITHOUT_ASSIGNMENTS else ListDecodingStage.ADDITIONAL
            val phase = if (early) RecoveryPhase.EARLY_LIST else RecoveryPhase.ADDITIONAL_LIST
            val cap = minOf(maxKnownDataEccMismatches ?: Int.MAX_VALUE,
                ErrorLimitPolicy.listDecodingData((matrix.dimension - 17) / 4))
            val previous = run.listDecodingSummary ?: ListDecodingSummary()
            run.listDecodingSummary = previous.copy(mismatchLimits = previous.mismatchLimits + cap,
                extensionLimits = previous.extensionLimits + run.listDecodingAdditionalErrors)
            run.listDecodingStatuses[stage] = ListDecodingStatus.RUNNING
            run.listDecodingStops.clear()
            val before = listDecodingBudget.elapsedMillis
            try {
                if (early) listDecodingBudget.measureSlice(EARLY_LIST_ALLOWANCE_MILLIS) { search(phase) }
                else listDecodingBudget.measure { search(phase) }
            } catch (_: ListDecodingSliceLimitException) {
                run.listDecodingStatuses[stage] = ListDecodingStatus.DEFERRED
            } catch (_: ListDecodingTimeLimitException) {
                run.listDecodingStatuses[stage] = ListDecodingStatus.TIME_LIMIT
                run.incompleteReasons += SearchIncompleteReason.LIST_DECODING_TIME_LIMIT
            } catch (error: RecoveryCancelledException) {
                run.listDecodingStatuses[stage] = ListDecodingStatus.INTERRUPTED
                throw error
            } catch (error: Exception) {
                run.listDecodingStatuses[stage] = ListDecodingStatus.INTERNAL_ERROR
                throw error
            } finally {
                run.listDecodingSummary = run.listDecodingSummary?.let {
                    it.copy(elapsedMillis = it.elapsedMillis + listDecodingBudget.elapsedMillis - before)
                }
                run.listDecodingNewTrials[stage] = (run.listDecodingSummary?.trials ?: previous.trials) - previous.trials
                if (run.listDecodingStatuses[stage] == ListDecodingStatus.RUNNING) {
                    val current = requireNotNull(run.listDecodingSummary)
                    run.listDecodingStatuses[stage] = when {
                        CombinationSearchStop.WORKING_MEMORY_LIMIT in run.listDecodingStops -> ListDecodingStatus.WORKING_MEMORY_LIMIT
                        run.listDecodingStops.isNotEmpty() -> ListDecodingStatus.SEARCH_LIMIT
                        // Count this stage independently; reusing a completed result is applicable work too.
                        current.trials == previous.trials && current.reusedTrials == previous.reusedTrials ->
                            ListDecodingStatus.INELIGIBLE
                        else -> ListDecodingStatus.COMPLETED
                    }
                }
            }
        }
        cancellation.check()
        val found = try {
            search(RecoveryPhase.ORIGINAL_RS)
            // This pass uses the original observations: no forced suffix or tentative bits.
            // Keep its validated result when its success suppresses further searching.
            val originalRsParsed = run.candidates.results().any { candidate ->
                candidate.payloadParsed && candidate.conventionalRsDiagnostic?.let {
                    it.succeeded && it.matches(candidate.correctedInterleaved) == true
                } == true
            }
            when {
                run.earlyBlocker != null ||
                    run.searchDiagnostic().inputBlocker == RecoveryInputBlocker.STRUCTURAL_FIXED_CELLS ->
                    skipLists(ListDecodingStatus.INPUT_CONSTRAINT)
                originalRsParsed -> skipLists(if (maxKnownDataEccMismatches == 0)
                    ListDecodingStatus.ZERO_ERROR_LIMIT else ListDecodingStatus.ORIGINAL_RS_SUCCEEDED)
                else -> {
                    if (maxKnownDataEccMismatches == 0) skipLists(ListDecodingStatus.ZERO_ERROR_LIMIT)
                    else searchLists(early = true)
                    // Independent passes keep a large tentative RS list from starving exact spaces.
                    search(RecoveryPhase.BIT_CONSTRAINTS)
                    if (maxKnownDataEccMismatches != 0) {
                        search(RecoveryPhase.PARTIAL_RS)
                        searchLists(early = false)
                    }
                }
            }
            run.finished = true
            run.completedSteps++
            finish(run, run.candidates.results())
        } catch (error: RecoveryCancelledException) {
            cancellation.check()
            if (!stopRequested()) throw error
            skipLists(ListDecodingStatus.INTERRUPTED)
            run.incompleteReasons += SearchIncompleteReason.USER_INTERRUPTED
            run.combinationSearch = run.combinationSearch.afterPass(setOf(CombinationSearchStop.USER_INTERRUPTED))
            run.candidates.results()
        } catch (_: Exception) {
            skipLists(ListDecodingStatus.INTERNAL_ERROR)
            run.incompleteReasons += SearchIncompleteReason.INTERNAL_ERROR
            run.candidates.results()
        }
        val candidates = found.map(::annotate)
        return RecoveryOutcome(
            candidates = candidates,
            failure = run.failure?.copy(combinationSearch = run.combinationSearch, displayConditions = conditions,
                searchDiagnostic = publishedDiagnostic),
            searchDiagnostic = publishedDiagnostic,
            formatAttempts = run.formatAttempts,
        )
    }

    private fun recoverRun(
        run: RecoveryRunState,
        matrix: QrMatrix,
        maxFormatCandidates: Int,
        trustKnownPayloadCount: Boolean,
        formatConstraint: QrFormatConstraint?,
        maxFormatMismatches: Int?,
        maxKnownDataEccMismatches: Int?,
        manualEdits: Map<Coord, ModuleValue>,
        onPriorityProgress: ((done: Int, total: Int) -> Unit)?,
    ): List<RecoveryCandidate> {
        val inputVersion = (matrix.dimension - 17) / 4
        val manual = FormatEditPolicy.manualValues(matrix) + manualEdits
        run.ignoredFormatCoords = FormatEditPolicy.ignoredComparisonCoords(inputVersion, formatConstraint, manual)
        FormatEditPolicy.conflict(inputVersion, formatConstraint, manual)?.let { conflict ->
            run.earlyBlocker = RecoveryInputBlocker.FORMAT_SELECTION
            val format = FormatSolver.ranked(matrix, run.ignoredFormatCoords)
                .first { formatConstraint!!.permits(it.ecLevel, it.dataMask) }
            considerFailure(run, format, RecoveryFailureStage.PAYLOAD_VALIDATION,
                reason = RecoveryFailureReason.FORMAT_EDIT_CONFLICT, manualConflicts = conflict.cells)
            return emptyList()
        }
        val effectiveLimit = maxFormatMismatches
        val permittedFormats = FormatSolver.ranked(matrix, run.ignoredFormatCoords)
            .filter { formatConstraint?.permits(it.ecLevel, it.dataMask) != false }
        if (effectiveLimit != null && permittedFormats.none { it.mismatches <= effectiveLimit }) {
            run.earlyBlocker = RecoveryInputBlocker.FORMAT_LIMIT
            permittedFormats.firstOrNull()?.let { format ->
                considerFailure(run, format, RecoveryFailureStage.EXTRACTION,
                    reason = RecoveryFailureReason.FORMAT_MISMATCH_LIMIT, formatMismatchLimit = effectiveLimit)
            }
            return emptyList()
        }
        run.expectedFormats = permittedFormats.filter { effectiveLimit == null || it.mismatches <= effectiveLimit }
            .mapTo(linkedSetOf()) { it.ecLevel to it.dataMask }
        val priority = matrix.prioritySearchCoordinates()
        val forcedFormats = formatConstraint?.let { constraint ->
            FormatSolver.ranked(matrix, run.ignoredFormatCoords)
                .filter { constraint.permits(it.ecLevel, it.dataMask) }
                .filter { effectiveLimit == null || it.mismatches <= effectiveLimit }
                .take(maxFormatCandidates)
        }
        if (priority.isEmpty() || !run.phase.usesAssignments) {
            recoverInternal(run, matrix, maxFormatCandidates, forcedFormats, allowBitEnumeration = run.phase.usesAssignments, trustKnownPayloadCount, maxFormatMismatches = effectiveLimit, maxKnownDataEccMismatches = maxKnownDataEccMismatches)
            return finish(run, run.candidates.results())
        }
        // First preserve ordinary QR behavior: every priority bit is still an erasure.
        val ordinaryParsed = recoverInternal(run, matrix, maxFormatCandidates, forcedFormats, allowBitEnumeration = false, trustKnownPayloadCount, maxFormatMismatches = effectiveLimit, maxKnownDataEccMismatches = maxKnownDataEccMismatches)
        if (ordinaryParsed) {
            return finish(run, run.candidates.results())
        }

        // Then try only the explicitly authorized bits. Ordinary '?' modules remain erasures.
        var priorityParsed = false
        val totalAssignments = 1 shl priority.size
        onPriorityProgress?.invoke(0, totalAssignments)
        for (assignment in 0 until totalAssignments) {
            run.cancellation.check()
            val guessed = matrix.copy()
            priority.forEachIndexed { index, coord ->
                val value = if (((assignment shr index) and 1) == 1) ModuleValue.ONE else ModuleValue.ZERO
                guessed.set(coord.first, coord.second, value, 1f, ModuleSource.USER)
            }
            // Priority cells are exact 0/1 values in this copy, so the ordinary bounded solver can
            // safely handle other partial codewords at the same time. Keeping it disabled here
            // leaves a gap where neither the priority pass nor the final pass can combine both
            // kinds of authorized search.
            val guessedForcedFormats = formatConstraint?.let { constraint ->
                FormatSolver.ranked(guessed, run.ignoredFormatCoords)
                    .filter { constraint.permits(it.ecLevel, it.dataMask) }
                    .filter { effectiveLimit == null || it.mismatches <= effectiveLimit }
                    .take(maxFormatCandidates)
            }
            val assignments = priority.associate { (x, y) -> Coord(x, y) to guessed[x, y].value }
            priorityParsed = recoverInternal(run, guessed, maxFormatCandidates, guessedForcedFormats,
                allowBitEnumeration = true, trustKnownPayloadCount, maxFormatMismatches = effectiveLimit,
                maxKnownDataEccMismatches = maxKnownDataEccMismatches, priorityAssignments = assignments) || priorityParsed
            onPriorityProgress?.invoke(assignment + 1, totalAssignments)
        }
        if (priorityParsed) {
            return finish(run, run.candidates.results())
        }

        // No priority assignment produced a validated payload; retain the existing bounded '?'
        // search as the final fallback.
        onPriorityProgress?.invoke(0, 0)
        recoverInternal(run, matrix, maxFormatCandidates, forcedFormats, allowBitEnumeration = true, trustKnownPayloadCount, maxFormatMismatches = effectiveLimit, maxKnownDataEccMismatches = maxKnownDataEccMismatches)
        return finish(run, run.candidates.results())
    }

    /** A format-consistent near miss is more useful than an unparsed RS coincidence. */
    private fun finish(run: RecoveryRunState, candidates: List<RecoveryCandidate>): List<RecoveryCandidate> {
        if (candidates.any { it.isSelectableRecovery }) return candidates
        val bestCandidate = candidates.firstOrNull() ?: return candidates
        val failure = run.failure ?: return candidates
        val failureHasStrongerFormatEvidence =
            failure.formatMismatches < bestCandidate.formatMismatches ||
                (failure.formatMismatches == bestCandidate.formatMismatches && failure.formatMatches > bestCandidate.formatMatches)
        return if (failureHasStrongerFormatEvidence) {
            run.candidates.clear()
            emptyList()
        } else candidates
    }

    private fun recoverInternal(
        run: RecoveryRunState,
        matrix: QrMatrix,
        maxFormatCandidates: Int,
        forcedFormats: List<FormatCandidate>?,
        allowBitEnumeration: Boolean,
        trustKnownPayloadCount: Boolean,
        maxFormatMismatches: Int? = null,
        maxKnownDataEccMismatches: Int? = null,
        formatRoute: FormatSelectionReason = FormatSelectionReason.SEARCH,
        priorityAssignments: Map<Coord, ModuleValue> = emptyMap(),
    ): Boolean {
        run.cancellation.check()
        val version = (matrix.dimension - 17) / 4
        val rankedFormats = FormatSolver.ranked(matrix, run.ignoredFormatCoords)
            .filter { maxFormatMismatches == null || it.mismatches <= maxFormatMismatches }
            .take(maxFormatCandidates)
        var formats = forcedFormats ?: rankedFormats
        var parsedFound = false

        if (forcedFormats == null && maxFormatCandidates > 1) {
            // With manual-value exclusion enabled, conflicting non-codeword pins prevent
            // BCH confirmation; other formats remain eligible. This is distinct from an
            // RS/payload failure after confirmation, which must not expand the format search.
            val trusted = FormatSolver.trustedCandidate(matrix)?.let { trusted ->
                rankedFormats.firstOrNull { it.maskedCodeword == trusted.maskedCodeword }
            }?.takeIf { nonRsManualConflicts(matrix, version, it.ecLevel, it.dataMask).isEmpty() }
            if (trusted != null) {
                // BCH confirmation is a hard format constraint for this input, including retries.
                run.expectedFormats = setOf(trusted.ecLevel to trusted.dataMask)
                val fastResult = recoverInternal(run, matrix, 1, forcedFormats = listOf(trusted), allowBitEnumeration = allowBitEnumeration, trustKnownPayloadCount, maxFormatMismatches, maxKnownDataEccMismatches, FormatSelectionReason.OBSERVED, priorityAssignments)
                // A BCH-trusted format is a hard constraint for this geometry. RS or payload
                // failure must not cause unrelated ECC-level/mask combinations to be tried.
                return fastResult
            } else {
                val provisional = FormatSolver.matchingInformationCandidate(matrix)?.let { provisional ->
                    rankedFormats.firstOrNull { it.maskedCodeword == provisional.maskedCodeword }
                }?.takeIf { nonRsManualConflicts(matrix, version, it.ecLevel, it.dataMask).isEmpty() }
                if (provisional != null) {
                    val fastResult = recoverInternal(run, matrix, 1, forcedFormats = listOf(provisional), allowBitEnumeration = allowBitEnumeration, trustKnownPayloadCount, maxFormatMismatches, maxKnownDataEccMismatches, FormatSelectionReason.OBSERVED, priorityAssignments)
                    // Matching information bits are weaker than BCH agreement. They only provide
                    // ordering; an invalid payload must always fall back to the other 31 formats.
                    if (fastResult) return true
                    formats = rankedFormats.filterNot { it.maskedCodeword == provisional.maskedCodeword }
                }
            }
        }

        for (format in formats) {
            run.cancellation.check()
            run.formatAttempts++
            val formatKey = format.ecLevel to format.dataMask
            run.inputBlockers.putIfAbsent(formatKey, null)
            val pinConflicts = nonRsManualConflicts(matrix, version, format.ecLevel, format.dataMask)
            if (pinConflicts.isNotEmpty()) {
                run.inputBlockers[formatKey] = RecoveryInputBlocker.STRUCTURAL_FIXED_CELLS
                considerFailure(run, format, RecoveryFailureStage.PAYLOAD_VALIDATION,
                    reason = RecoveryFailureReason.MANUAL_PIN_CONFLICT, manualConflicts = pinConflicts)
                continue
            }
            val extraction = try {
                CodewordExtractor.extract(matrix, version, format.dataMask)
            } catch (_: Exception) {
                run.unresolvedCalculations = true
                run.incompleteReasons += SearchIncompleteReason.INTERNAL_ERROR
                considerFailure(run, format, RecoveryFailureStage.EXTRACTION)
                continue
            }
            val de = try {
                QrBlockInterleaver.deinterleave(extraction, version, format.ecLevel)
            } catch (_: Exception) {
                run.unresolvedCalculations = true
                run.incompleteReasons += SearchIncompleteReason.INTERNAL_ERROR
                considerFailure(run, format, RecoveryFailureStage.DEINTERLEAVE)
                continue
            }
            run.completedSteps++
            // Preserve the pre-trial observation for metrics and Before/provenance views.
            // The trial extraction and blocks still drive RS and enforce temporary guesses.
            val evidenceExtraction = if (matrix === run.diagnosticInput) extraction else
                CodewordExtractor.extract(run.diagnosticInput, version, format.dataMask)
            val conventionalDiagnostic = run.conventionalDiagnostics.getOrPut(format.ecLevel to format.dataMask) {
                val inputBlocks = QrBlockInterleaver.deinterleave(evidenceExtraction, version, format.ecLevel)
                val diagnosis = ConventionalRsDiagnostic.analyze(inputBlocks, rs, run.cancellation)
                val raw = diagnosis.correctedInterleaved
                diagnosis.copy(
                    knownBitMismatches = raw?.let { RecoveryCandidateEvidence.countKnownBitMismatches(run.diagnosticInput, evidenceExtraction, it, format.dataMask).second },
                    knownBitMismatchLimit = maxKnownDataEccMismatches,
                    manualConflicts = raw?.let { RecoveryCandidateEvidence.userPinConflicts(run.diagnosticInput, evidenceExtraction, it, format.dataMask) }.orEmpty(),
                    qrStructureValid = raw?.let {
                        val restored = inputBlocks.blocks.map { IntArray(it.codewords.size) }
                        inputBlocks.rawLocations.forEachIndexed { position, location ->
                            val loc = requireNotNull(location)
                            restored[loc.blockIndex][loc.localIndex] = raw[position]
                        }
                        val data = inputBlocks.blocks.indices.flatMap { block ->
                            restored[block].take(inputBlocks.blocks[block].numDataCodewords).map { it.toByte() }
                        }.toByteArray()
                        StrictQrPayloadValidator.isValid(data, version)
                    },
                )
            }
            val originalErasures = evidenceExtraction.codewords.count { it.unknownBits != 0 }
            val priorityCoordinates = matrix.prioritySearchCoordinates().toSet()
            val priorityRawIndices = extraction.codewords.indices.filterTo(hashSetOf()) { raw ->
                extraction.codewords[raw].coordinates.any { (it.x to it.y) in priorityCoordinates }
            }

            val originalSpaces = de.blocks.map { bitSolver.analyze(it, de.ecSymbolsPerBlock, run.cancellation) }
            val bitDiagnostic = run.bitDiagnostics.getOrPut(format.ecLevel to format.dataMask) {
                if (matrix === run.diagnosticInput) RsBitDiagnostic.build(matrix, extraction, de, originalSpaces, run.cancellation)
                else {
                    // Priority trials contain temporary 0/1 guesses. Never describe those as
                    // originally observed evidence in the diagnostic or confirmation guidance.
                    val originalExtraction = CodewordExtractor.extract(run.diagnosticInput, version, format.dataMask)
                    val originalDe = QrBlockInterleaver.deinterleave(originalExtraction, version, format.ecLevel)
                    RsBitDiagnostic.build(run.diagnosticInput, originalExtraction, originalDe,
                        originalDe.blocks.map { bitSolver.analyze(it, originalDe.ecSymbolsPerBlock, run.cancellation) }, run.cancellation)
                }
            }
            // This proof uses the original observation, never forced suffixes or priority guesses.
            if (maxKnownDataEccMismatches == 0 && bitDiagnostic.dimension == null) {
                run.inputBlockers[formatKey] = RecoveryInputBlocker.ZERO_MISMATCH
            }
            val trackSingleSegment = trustKnownPayloadCount
            val headerData = if (trackSingleSegment) de.blocks.flatMap { it.codewords.take(it.numDataCodewords) }.toIntArray() else intArrayOf()
            val headerUnknown = if (trackSingleSegment) de.blocks.flatMap { it.unknownMasks.take(it.numDataCodewords) }.toIntArray() else intArrayOf()
            val preserveObservations = !run.phase.usesAssignments
            val inferred = de.copyDeep()
            val constraints = if (!preserveObservations || trackSingleSegment) {
                // The first RS/list passes validate the assumption without changing observations.
                val result = PayloadConstraintSolver.apply(inferred, version, trustKnownCount = trustKnownPayloadCount, cancellation = run.cancellation)
                if (preserveObservations) result.copy(filledBits = 0, filled = emptyList()) else result
            } else {
                PayloadConstraintSolver.Result(0, 0, null, null)
            }
            val singleSegmentStatus = if (trackSingleSegment) {
                val originalDe = if (priorityAssignments.isNotEmpty())
                    QrBlockInterleaver.deinterleave(evidenceExtraction, version, format.ecLevel) else null
                val originalUnknown = originalDe?.blocks?.flatMap { it.unknownMasks.take(it.numDataCodewords) }?.toIntArray()
                val hypothesis = originalUnknown?.let { priorityHeaderHypothesis(headerData, headerUnknown, it, version) }
                if (hypothesis != null && run.priorityHeaderHypotheses.size < 2) run.priorityHeaderHypotheses += hypothesis
                val originalHeaderKnown = hypothesis == null
                singleSegmentStatus(headerData, headerUnknown, version, constraints, !originalHeaderKnown)
                    .also { run.singleSegmentStatuses += it }
            } else null
            if (trackSingleSegment && constraints.trustedStructure == null) {
                // Keep the user's condition in force even when the header cannot establish it.
                considerFailure(run, format, RecoveryFailureStage.PAYLOAD_VALIDATION,
                    originalErasedCodewords = originalErasures,
                    erasedCodewords = extraction.codewords.count { it.unknownBits != 0 },
                    reason = RecoveryFailureReason.SINGLE_SEGMENT_CONDITION_UNAVAILABLE,
                    rsBitDiagnostic = bitDiagnostic)
                continue
            }
            val inputs = PayloadRecoveryInput.variants(de, inferred, constraints,
                preserveObservations, trustKnownPayloadCount)
            // Give each input its basic RS/list attempts before expanding partial-bit searches.
            val passes = if (inputs.size > 1 && run.phase in setOf(RecoveryPhase.PARTIAL_RS, RecoveryPhase.ADDITIONAL_LIST))
                inputs.map { it to true } + inputs.map { it to false }
            else inputs.map { it to false }
            for ((input, basicOnly) in passes) {
                run.cancellation.check()
                val de = input.blocks
                val constraints = input.constraints
                val trialBitEnumeration = allowBitEnumeration && !basicOnly
                val constrainedErasures = de.blocks.sumOf { block -> block.unknownMasks.count { it != 0 } }
                val rsShortfallBits = minimumRsShortfallBits(de.blocks, de.ecSymbolsPerBlock)

                val listDecodingLimit = if (run.listDecodingPhase) minOf(maxKnownDataEccMismatches ?: Int.MAX_VALUE,
                    ErrorLimitPolicy.listDecodingData(version)) else null
                val evaluator = RecoveryCandidateEvaluator(
                    version = version,
                    format = format,
                    formatRoute = formatRoute,
                    matrix = matrix,
                    diagnosticInput = run.diagnosticInput,
                    extraction = extraction,
                    evidenceExtraction = evidenceExtraction,
                    de = de,
                    constraints = constraints,
                    trackSingleSegment = trackSingleSegment,
                    singleSegmentStatus = singleSegmentStatus,
                    priorityAssignments = priorityAssignments,
                    conventionalDiagnostic = conventionalDiagnostic,
                    bitDiagnostic = bitDiagnostic,
                    originalErasures = originalErasures,
                    constrainedErasures = constrainedErasures,
                    rsShortfallBits = rsShortfallBits,
                    maxKnownDataEccMismatches = listDecodingLimit ?: maxKnownDataEccMismatches,
                    maxAlternativeDerivations = run.retentionBudget.maxAlternativeDerivations,
                    listDecodingMismatchLimit = listDecodingLimit,
                )

                if (run.listDecodingPhase) {
                    val original = QrBlockInterleaver.deinterleave(evidenceExtraction, version, format.ecLevel)
                    val nonEnumerable = de.blocks.indices.map { blockIndex ->
                        priorityRawIndices.mapNotNullTo(hashSetOf()) { raw ->
                            de.rawLocations.getOrNull(raw)?.takeIf { it.blockIndex == blockIndex }?.localIndex
                        }
                    }
                    val trialCounts = IntArray(de.blocks.size)
                    val interpolationCounts = IntArray(de.blocks.size)
                    val reusedCounts = IntArray(de.blocks.size)
                    val methodCounts = Array(de.blocks.size) { IntArray(ListDecoderMethod.entries.size) }
                    val radiusCounts = Array(de.blocks.size) { emptyMap<Int, Int>() }
                    val completedRadiusCounts = Array(de.blocks.size) { emptyMap<Int, Int>() }
                    val foundListDecoded = ListDecodingQrSearch(rs).run(de, original, matrix, extraction, priorityAssignments.keys, version, requireNotNull(listDecodingLimit),
                        trialBitEnumeration, nonEnumerable, run.partialBitSearchLimit, run.retentionBudget.workingLimitBytes,
                        run.cancellation, baseOnly = run.phase == RecoveryPhase.EARLY_LIST || basicOnly,
                        cache = run.listDecodingCache, additionalErrors = run.listDecodingAdditionalErrors,
                        onRejection = { run.candidateRejections += it },
                        onProgress = { index, search ->
                            val previous = requireNotNull(run.listDecodingSummary)
                            fun addRadii(total: Map<Int, Int>, current: Map<Int, Int>, before: Map<Int, Int>) =
                                (total.keys + current.keys).associateWith { radius ->
                                    (total[radius] ?: 0) + (current[radius] ?: 0) - (before[radius] ?: 0)
                                }
                            run.listDecodingSummary = previous.copy(
                                trials = previous.trials + search.trials - trialCounts[index],
                                interpolations = previous.interpolations + search.interpolations - interpolationCounts[index],
                                reusedTrials = previous.reusedTrials + search.reusedTrials - reusedCounts[index],
                                skipReasons = previous.skipReasons + search.skipReasons,
                                hardRadiusTrials = addRadii(previous.hardRadiusTrials, search.hardRadiusTrials, radiusCounts[index]),
                                completedHardRadiusTrials = addRadii(previous.completedHardRadiusTrials,
                                    search.completedHardRadiusTrials, completedRadiusCounts[index]),
                                methodTrials = ListDecoderMethod.entries.associateWith { method ->
                                    (previous.methodTrials[method] ?: 0) + (search.methodTrials[method] ?: 0) - methodCounts[index][method.ordinal]
                                }.filterValues { it > 0 },
                            )
                            trialCounts[index] = search.trials
                            interpolationCounts[index] = search.interpolations
                            reusedCounts[index] = search.reusedTrials
                            radiusCounts[index] = search.hardRadiusTrials
                            completedRadiusCounts[index] = search.completedHardRadiusTrials
                            ListDecoderMethod.entries.forEach { method -> methodCounts[index][method.ordinal] = search.methodTrials[method] ?: 0 }
                        }, onStops = {
                            run.listDecodingStops += it
                            run.combinationSearch = run.combinationSearch.afterPass(it)
                        },
                    ) { combination ->
                        // Rediscovering an existing exact candidate must not end additional
                        // bit trials or priority assignments before another payload is tested.
                        run.recordEvaluation(evaluator.evaluate(combination)) == RecordedRecoveryCandidate.NEWLY_PARSED
                    }
                    parsedFound = foundListDecoded || parsedFound
                    continue
                }

                val trialSpaces = if (constraints.filledBits == 0) originalSpaces else
                    de.blocks.map { bitSolver.analyze(it, de.ecSymbolsPerBlock, run.cancellation) }
                val exactOnly = maxKnownDataEccMismatches == 0 || run.phase == RecoveryPhase.BIT_CONSTRAINTS
                if (!basicOnly && run.phase != RecoveryPhase.ORIGINAL_RS && trialSpaces.any { (it?.dimension ?: 0) > 0 }) {
                    val nonEnumerable = de.blocks.indices.map { index ->
                        priorityRawIndices.mapNotNullTo(hashSetOf()) { raw ->
                            de.rawLocations.getOrNull(raw)?.takeIf { it.blockIndex == index }?.localIndex
                        }
                    }
                    val streamed = StreamingQrSearch(rs).run(de, trialSpaces, version, exactOnly,
                        trialBitEnumeration, nonEnumerable, run.partialBitSearchLimit, run.availableWorkingBytes,
                        run.cancellation, onStops = { run.combinationSearch = run.combinationSearch.afterPass(it) },
                        onSearch = { run.solverCompleteness = run.solverCompleteness.combine(it) },
                    ) { combination ->
                        run.candidateCalculations++
                        val recorded = run.recordEvaluation(evaluator.evaluate(combination))
                        recorded.parsed
                    }
                    parsedFound = streamed.parsed || parsedFound
                    if (streamed.candidates == 0) {
                        run.unresolvedCalculations = true
                        considerFailure(run, format, if (streamed.failure == null) RecoveryFailureStage.PAYLOAD_VALIDATION
                            else RecoveryFailureStage.REED_SOLOMON, originalErasedCodewords = originalErasures,
                            erasedCodewords = constrainedErasures, rsShortfallBits = rsShortfallBits,
                            constraintFilledBits = constraints.filledBits,
                            reason = streamed.failure?.let { classifyRsFailure(it, rsShortfallBits) },
                            failedBlockIndex = streamed.failedBlock, rsBlockCount = de.blocks.size,
                            failedBlockErasures = streamed.failedBlock?.let { de.blocks[it].unknownMasks.count { mask -> mask != 0 } },
                            ecSymbolsPerBlock = de.ecSymbolsPerBlock, rsBitDiagnostic = bitDiagnostic)
                    }
                    continue
                }

                val blockCandidateResults = ArrayList<List<PartialCodewordSolver.Result>>(de.blocks.size)
                var blockWorkingBytes = 0L
                var failed = false
                var failedBlockIndex: Int? = null
                var failedBlockErasures: Int? = null
                var blockFailure: RsFailureReason? = null
                for ((blockIndex, block) in de.blocks.withIndex()) {
                    run.candidateCalculations++
                    run.cancellation.check()
                    val blockErasures = block.unknownMasks.count { it != 0 }
                    val results = try {
                        val nonEnumerable = priorityRawIndices.mapNotNullTo(hashSetOf()) { raw ->
                            de.rawLocations.getOrNull(raw)?.takeIf { it.blockIndex == blockIndex }?.localIndex
                        }
                        val space = if (basicOnly) null else if (constraints.filledBits == 0) originalSpaces[blockIndex]
                            else bitSolver.analyze(block, de.ecSymbolsPerBlock, run.cancellation)
                        val requiredBytes = blockCandidateWorkingBytes(block, space?.dimension,
                            trialBitEnumeration, nonEnumerable, exactOnly,
                            de.ecSymbolsPerBlock, conventionalDiagnostic.blocks.getOrNull(blockIndex)?.margin
                                .takeIf { matrix === run.diagnosticInput && constraints.filledBits == 0 },
                            partialBitSearchLimit = run.partialBitSearchLimit)
                        if (requiredBytes > run.availableWorkingBytes - blockWorkingBytes) {
                            run.combinationSearch = run.combinationSearch.afterPass(setOf(CombinationSearchStop.WORKING_MEMORY_LIMIT))
                            throw ReedSolomonErasures.DecodeException("Exact RS space exceeds working-memory allowance",
                                RsFailureReason.BIT_SPACE_LIMIT)
                        }
                        blockWorkingBytes += requiredBytes
                        partialSolver.decodeWithBitConstraints(
                            block,
                            de.ecSymbolsPerBlock,
                            space = space?.takeIf { !basicOnly && (run.phase != RecoveryPhase.ORIGINAL_RS ||
                                (it.dimension == 0 && conventionalDiagnostic.blocks[blockIndex].status == ConventionalRsStatus.SUCCESS)) },
                            exactOnly = exactOnly,
                            allowBitEnumeration = trialBitEnumeration,
                            nonEnumerablePositions = nonEnumerable,
                            cancellation = run.cancellation,
                            maxEnumeratedBits = run.partialBitSearchLimit,
                            reportSearch = { run.solverCompleteness = run.solverCompleteness.combine(it) },
                        )
                    } catch (error: ReedSolomonErasures.DecodeException) {
                        failed = true
                        failedBlockIndex = blockIndex
                        failedBlockErasures = blockErasures
                        blockFailure = error.reason
                        break
                    }
                    blockCandidateResults += results
                }
                if (failed) {
                    // An ordinary no-result outcome terminates this block's specified policy.
                    run.unresolvedCalculations = true
                    considerFailure(
                        run,
                        format,
                        RecoveryFailureStage.REED_SOLOMON,
                        originalErasedCodewords = originalErasures,
                        erasedCodewords = constrainedErasures,
                        rsShortfallBits = rsShortfallBits,
                        constraintFilledBits = constraints.filledBits,
                        partialGuessedBits = blockCandidateResults.sumOf { it.firstOrNull()?.guessedBits ?: 0 },
                        reason = classifyRsFailure(blockFailure, rsShortfallBits),
                        failedBlockIndex = failedBlockIndex,
                        rsBlockCount = de.blocks.size,
                        failedBlockErasures = failedBlockErasures,
                        ecSymbolsPerBlock = de.ecSymbolsPerBlock,
                        rsBitDiagnostic = bitDiagnostic,
                    )
                    blockCandidateResults.clear()
                    continue
                }
                // Generate and validate a bounded search window lazily. The former product-size gate
                // discarded every candidate when the product exceeded 4096; this examines the best
                // ordered combinations in expanding windows and preserves any fully validated result.
                val combinationStops = linkedSetOf<CombinationSearchStop>()
                var parsedCandidatesForFormat = 0
                val affordableNodes = ((run.availableWorkingBytes - blockWorkingBytes) /
                    (256L + de.blocks.size * 16L)).coerceIn(0L, MAX_QR_COMBINATION_TRIALS.toLong()).toInt()
                if (affordableNodes == 0) {
                    run.combinationSearch = run.combinationSearch.afterPass(setOf(CombinationSearchStop.WORKING_MEMORY_LIMIT))
                    blockCandidateResults.clear()
                    considerFailure(run, format, RecoveryFailureStage.REED_SOLOMON,
                        reason = RecoveryFailureReason.QR_COMBINATION_SEARCH_LIMIT,
                        rsBlockCount = de.blocks.size, ecSymbolsPerBlock = de.ecSymbolsPerBlock,
                        rsBitDiagnostic = bitDiagnostic)
                    continue
                }
                val combinations = bestFirstCartesianProductSequence(blockCandidateResults, { it.orderingPenalty },
                    maxExpandedNodes = affordableNodes,
                    onLimitReached = { combinationStops += if (affordableNodes < MAX_QR_COMBINATION_TRIALS)
                        CombinationSearchStop.WORKING_MEMORY_LIMIT else CombinationSearchStop.NODE_LIMIT },
                ) { prefix ->
                    run.cancellation.check()
                    val prefixData = buildList<Int> {
                        for (i in prefix.indices) {
                            val corrected = prefix[i].corrected
                            for (j in 0 until de.blocks[i].numDataCodewords) add(corrected[j])
                        }
                    }
                    StrictQrPayloadValidator.canBePrefix(ByteArray(prefixData.size) { prefixData[it].toByte() }, version)
                        .also { valid -> if (!valid) run.candidateRejections += RecoveryCandidateRejection.QR_STRUCTURE }
                }.takeInExpandingWindows(
                    initialLimit = INITIAL_QR_COMBINATION_TRIALS,
                    maxLimit = MAX_QR_COMBINATION_TRIALS,
                    // A structurally valid but ZXing-unparsed RS coincidence is useful diagnostic
                    // evidence, not a satisfactory recovery. Keep expanding until this format yields
                    // a decoded payload or the explicit combination bound is reached.
                    shouldExpand = { parsedCandidatesForFormat == 0 },
                ) {
                    combinationStops += if (parsedCandidatesForFormat > 0) CombinationSearchStop.SUCCESS_WINDOW
                        else CombinationSearchStop.COMBINATION_LIMIT
                }
                for (combination in combinations) {
                    run.cancellation.check()
                    val recorded = run.recordEvaluation(evaluator.evaluate(combination))
                    if (recorded.parsed) {
                        parsedCandidatesForFormat++
                        parsedFound = true
                    }
                }
                run.combinationSearch = run.combinationSearch.afterPass(combinationStops)
                if (parsedCandidatesForFormat == 0) {
                    considerFailure(
                        run,
                        format,
                        RecoveryFailureStage.PAYLOAD_VALIDATION,
                        originalErasedCodewords = originalErasures,
                        erasedCodewords = constrainedErasures,
                        rsShortfallBits = rsShortfallBits,
                        constraintFilledBits = constraints.filledBits,
                        reason = if (combinationStops.isNotEmpty()) RecoveryFailureReason.QR_COMBINATION_SEARCH_LIMIT
                            else null,
                        rsBlockCount = de.blocks.size,
                        ecSymbolsPerBlock = de.ecSymbolsPerBlock,
                        rsBitDiagnostic = bitDiagnostic,
                    )
                }
            }
        }
        return parsedFound
    }

    private fun considerFailure(
        run: RecoveryRunState,
        format: FormatCandidate,
        stage: RecoveryFailureStage,
        originalErasedCodewords: Int = 0,
        erasedCodewords: Int = 0,
        rsShortfallBits: Int? = null,
        constraintFilledBits: Int = 0,
        partialGuessedBits: Int = 0,
        reason: RecoveryFailureReason? = null,
        failedBlockIndex: Int? = null,
        rsBlockCount: Int? = null,
        failedBlockErasures: Int? = null,
        ecSymbolsPerBlock: Int? = null,
        knownBitMismatches: Int? = null,
        knownBitsCompared: Int? = null,
        knownBitMismatchLimit: Int? = null,
        formatMismatchLimit: Int? = null,
        rsBitDiagnostic: RsBitDiagnostic? = null,
        manualConflicts: List<ManualCellConflict> = emptyList(),
    ) {
        val candidate = RecoveryFailure(
            ecLevel = format.ecLevel,
            dataMask = format.dataMask,
            formatMatches = format.matches,
            formatMismatches = format.mismatches,
            formatKnownBits = format.knownBits,
            stage = stage,
            reason = reason,
            failedBlockIndex = failedBlockIndex,
            rsBlockCount = rsBlockCount,
            failedBlockErasures = failedBlockErasures,
            ecSymbolsPerBlock = ecSymbolsPerBlock,
            originalErasedCodewords = originalErasedCodewords,
            erasedCodewords = erasedCodewords,
            rsShortfallBits = rsShortfallBits,
            constraintFilledBits = constraintFilledBits,
            partialGuessedBits = partialGuessedBits,
            knownBitMismatches = knownBitMismatches,
            knownBitsCompared = knownBitsCompared,
            knownBitMismatchLimit = knownBitMismatchLimit,
            formatMismatchLimit = formatMismatchLimit,
            rsBitDiagnostic = rsBitDiagnostic,
            manualConflicts = manualConflicts,
            conventionalRsDiagnostic = run.conventionalDiagnostics[format.ecLevel to format.dataMask],
        )
        run.considerFailure(candidate)
    }

    fun applyCorrection(matrix: QrMatrix, candidate: RecoveryCandidate, overwriteKnownErrors: Boolean = false) {
        val codewordCoordinates = HashSet<Coord>()
        for (cwIndex in candidate.correctedInterleaved.indices) {
            val corrected = candidate.correctedInterleaved[cwIndex]
            val coords = candidate.extraction.codewords[cwIndex].coordinates
            for (bitIndex in 0 until 8) {
                val c = coords[bitIndex]
                codewordCoordinates += c
                val unmasked = (corrected shr (7 - bitIndex)) and 1
                val masked = unmasked xor if (CodewordExtractor.maskApplies(candidate.dataMask, c.x, c.y)) 1 else 0
                val target = if (masked == 1) ModuleValue.ONE else ModuleValue.ZERO
                val cell = matrix[c.x, c.y]
                // A manual 0/1 is a hard user decision. A manual '?' is an erasure request and
                // must be resolved in the candidate/After matrix once RS determines the bit.
                if (cell.source == ModuleSource.USER && cell.value != ModuleValue.ERASED) continue
                if (cell.value == ModuleValue.ERASED || overwriteKnownErrors) {
                    if (cell.value != target || cell.value == ModuleValue.ERASED) matrix.set(c.x, c.y, target, 1f, ModuleSource.RECOVERED)
                }
            }
        }
        // Modules left after the final ECC codeword are QR remainder bits. They are not protected
        // by RS, but their unmasked value is defined as zero, so the displayed masked value is
        // still uniquely determined by the selected mask (V2 has seven such modules).
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            val coord = Coord(x, y)
            if (QrFunctionMask.isFunction(candidate.version, x, y) || coord in codewordCoordinates) continue
            val cell = matrix[x, y]
            if (cell.source == ModuleSource.USER && cell.value != ModuleValue.ERASED) continue
            val target = if (CodewordExtractor.maskApplies(candidate.dataMask, x, y)) ModuleValue.ONE else ModuleValue.ZERO
            if (cell.value == ModuleValue.ERASED || overwriteKnownErrors) {
                if (cell.value != target || cell.value == ModuleValue.ERASED) matrix.set(x, y, target, 1f, ModuleSource.STRUCTURAL)
            }
        }
    }
}

/** Suffix forcing supports only a leading Numeric, Alphanumeric, Byte or Kanji header. */
internal fun trustablePayloadHeaderAvailable(data: IntArray, unknown: IntArray, version: Int): Boolean {
    require(data.size == unknown.size)
    if (data.isEmpty() || (unknown[0] and 0xF0) != 0) return false
    if (((data[0] ushr 4) and 15) !in setOf(1, 2, 4, 8)) return false
    return completePayloadHeaderAvailable(data, unknown, version)
}

/** Reads known control prefixes until the first character-count-bearing data segment. */
internal fun completePayloadHeaderAvailable(dataBytes: IntArray, unknownMasks: IntArray, version: Int): Boolean {
    require(dataBytes.size == unknownMasks.size)
    var position = 0
    val totalBits = dataBytes.size * 8
    fun read(width: Int): Int? {
        if (width < 0 || position + width > totalBits) return null
        var value = 0
        repeat(width) {
            val bit = 7 - position % 8
            if ((unknownMasks[position / 8] and (1 shl bit)) != 0) return null
            value = (value shl 1) or ((dataBytes[position / 8] shr bit) and 1)
            position++
        }
        return value
    }
    fun skipKnown(width: Int): Boolean = read(width) != null
    fun countWidth(mode: Int): Int {
        val group = when (version) { in 1..9 -> 0; in 10..26 -> 1; else -> 2 }
        return when (mode) {
            1 -> intArrayOf(10, 12, 14)[group]
            2 -> intArrayOf(9, 11, 13)[group]
            4 -> intArrayOf(8, 16, 16)[group]
            8, 13 -> intArrayOf(8, 10, 12)[group]
            else -> 0
        }
    }

    while (position + 4 <= totalBits) {
        when (val mode = read(4) ?: return false) {
            0 -> return false
            1, 2, 4, 8 -> return read(countWidth(mode)) != null
            13 -> {
                if (read(4) != 1) return false // Hanzi GB2312 subset indicator.
                return read(countWidth(mode)) != null
            }
            3 -> if (!skipKnown(16)) return false // Structured Append
            5 -> Unit // FNC1 first position
            7 -> { // ECI
                val first = read(8) ?: return false
                val continuation = when {
                    (first and 0x80) == 0 -> 0
                    (first and 0xC0) == 0x80 -> 8
                    (first and 0xE0) == 0xC0 -> 16
                    else -> return false
                }
                if (!skipKnown(continuation)) return false
            }
            9 -> if (!skipKnown(8)) return false // FNC1 second position
            else -> return false
        }
    }
    return false
}


/** Enumerates the complete Cartesian product without materializing it in memory. */
internal fun <T> cartesianProductSequence(
    groups: List<List<T>>,
    canExtend: (List<T>) -> Boolean = { true },
): Sequence<List<T>> = sequence {
    if (groups.any { it.isEmpty() }) return@sequence
    val current = ArrayList<T>(groups.size)
    suspend fun SequenceScope<List<T>>.visit(groupIndex: Int) {
        if (groupIndex == groups.size) {
            yield(current.toList())
            return
        }
        for (value in groups[groupIndex]) {
            current += value
            if (canExtend(current)) visit(groupIndex + 1)
            current.removeAt(current.lastIndex)
        }
    }
    visit(0)
}

/** Preserves lazy traversal while reporting that a hard limit left validated values unvisited. */
internal fun <T> Sequence<T>.takeReportingTruncation(
    limit: Int,
    onTruncated: () -> Unit,
): Sequence<T> = sequence {
    require(limit >= 0)
    val source = this@takeReportingTruncation.iterator()
    var emitted = 0
    while (emitted < limit && source.hasNext()) {
        yield(source.next())
        emitted++
    }
    if (source.hasNext()) onTruncated()
}

/** Doubles a lazy search window only while the caller still has no satisfactory result. */
internal fun <T> Sequence<T>.takeInExpandingWindows(
    initialLimit: Int,
    maxLimit: Int,
    shouldExpand: () -> Boolean,
    onTruncated: () -> Unit,
): Sequence<T> = sequence {
    require(initialLimit > 0)
    require(maxLimit >= initialLimit)
    val source = this@takeInExpandingWindows.iterator()
    var emitted = 0
    var boundary = initialLimit
    while (source.hasNext()) {
        if (emitted == boundary) {
            if (!shouldExpand()) {
                onTruncated()
                return@sequence
            }
            if (boundary == maxLimit) {
                onTruncated()
                return@sequence
            }
            boundary = minOf(maxLimit, boundary * 2)
        }
        yield(source.next())
        emitted++
    }
}

/** Enumerates Cartesian products by the lowest possible total rank of each prefix. */
internal fun <T> bestFirstCartesianProductSequence(
    groups: List<List<T>>,
    rank: (T) -> Int,
    maxExpandedNodes: Int = Int.MAX_VALUE,
    onLimitReached: () -> Unit = {},
    canExtend: (List<T>) -> Boolean = { true },
): Sequence<List<T>> = sequence {
    require(maxExpandedNodes > 0)
    if (groups.any { it.isEmpty() }) return@sequence
    if (groups.isEmpty()) { yield(emptyList()); return@sequence }
    val ordered = groups.map { group -> group.withIndex().sortedWith(compareBy<IndexedValue<T>> { rank(it.value) }.thenBy { it.index }).map { it.value } }
    data class Node(val indices: IntArray, val totalRank: Long, val serial: Long)
    // A prefix tree with lazy siblings needs neither the full Cartesian frontier nor a visited
    // set. Prefer deeper prefixes at equal lower bounds so ties don't expand an entire level.
    val queue = PriorityQueue<Node>(compareBy<Node> { it.totalRank }.thenByDescending { it.indices.size }.thenBy { it.serial })
    var serial = 0L
    val initial = intArrayOf(0)
    queue += Node(initial, ordered.indices.sumOf { rank(ordered[it][0]).toLong() }, serial++)
    var expanded = 0
    while (queue.isNotEmpty()) {
        if (expanded++ == maxExpandedNodes) { onLimitReached(); return@sequence }
        val node = queue.remove()
        val depth = node.indices.lastIndex
        val oldIndex = node.indices[depth]
        // Siblings can change a rejected prefix, so enqueue the next sibling before validation.
        if (oldIndex + 1 < ordered[depth].size) {
            val sibling = node.indices.copyOf()
            sibling[depth]++
            queue += Node(sibling, node.totalRank - rank(ordered[depth][oldIndex]) + rank(ordered[depth][oldIndex + 1]), serial++)
        }
        val values = node.indices.indices.map { ordered[it][node.indices[it]] }
        if (!canExtend(values)) continue
        if (values.size == ordered.size) yield(values)
        else queue += Node(node.indices.copyOf(node.indices.size + 1), node.totalRank, serial++)
    }
}
