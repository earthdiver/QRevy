package io.github.earthdiver.qrrepair.core

import com.google.zxing.FormatException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.ZxingPayloadBridge
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
}

internal fun classifyRsFailure(message: String?, erasureShortfallBits: Int): RecoveryFailureReason = when {
    message?.startsWith("No exact known-bit") == true -> RecoveryFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT
    message?.startsWith("Exact RS space exceeds") == true -> RecoveryFailureReason.RS_BIT_SPACE_LIMIT
    message?.contains("bounded partial-bit solver") == true ||
        message?.contains("equally ranked RS-consistent") == true ->
        RecoveryFailureReason.BOUNDED_BIT_SEARCH_LIMIT
    message?.contains("RS-consistent assignment") == true || message?.contains("Chien search") == true ->
        RecoveryFailureReason.NO_RS_CONSISTENT_ASSIGNMENT
    message?.contains("unknown errors") == true || message?.contains("capacity exceeded") == true ->
        RecoveryFailureReason.ERROR_ERASURE_CAPACITY_EXCEEDED
    erasureShortfallBits > 0 || message?.startsWith("Too many erasure") == true ->
        RecoveryFailureReason.ERASURE_CAPACITY_EXCEEDED
    else -> RecoveryFailureReason.RS_CORRECTION_FAILED
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
    }

    private val partialSolver = PartialCodewordSolver(rs)
    private val bitSolver = RsBitConstraintSolver(rs)
    /** Mutable search evidence belongs to exactly one invocation, including its fallback trials. */
    private class RecoveryRunState(val diagnosticInput: QrMatrix, val retentionBudget: RecoveryRetentionBudget,
        val cancellation: RecoveryCancellation) {
        var ignoredFormatCoords: Set<Coord> = emptySet()
        val bitDiagnostics = mutableMapOf<Pair<ErrorCorrectionLevel, Int>, RsBitDiagnostic>()
        val conventionalDiagnostics = mutableMapOf<Pair<ErrorCorrectionLevel, Int>, ConventionalRsDiagnostic>()
        var failure: RecoveryFailure? = null
        var formatAttempts: Int = 0
        var combinationSearch = CombinationSearchSummary()
        var expectedFormats: Set<Pair<ErrorCorrectionLevel, Int>> = emptySet()
        val inputBlockers = mutableMapOf<Pair<ErrorCorrectionLevel, Int>, RecoveryInputBlocker?>()
        var earlyBlocker: RecoveryInputBlocker? = null
        val candidateRejections = linkedSetOf<RecoveryCandidateRejection>()
        var acceptedCandidate = false
        var unresolvedCalculations = false
        var boundedSearch = false
        var candidateCalculations = 0

        fun searchDiagnostic(): RecoverySearchDiagnostic {
            val allInputsChecked = expectedFormats.isNotEmpty() && expectedFormats.all(inputBlockers::containsKey)
            val blockers = expectedFormats.map { inputBlockers[it] }.toSet()
            return RecoverySearchDiagnostic(
                inputBlocker = earlyBlocker ?: blockers.singleOrNull()?.takeIf { allInputsChecked },
                candidateRejections = candidateRejections.toSet(),
                acceptedCandidate = acceptedCandidate,
                unresolvedCalculations = unresolvedCalculations,
                unsearched = boundedSearch || combinationSearch.stops.isNotEmpty() || retentionBudget.exhausted ||
                    (expectedFormats.isNotEmpty() && !allInputsChecked),
                candidateCalculations = candidateCalculations,
            )
        }
    }

    /** Diagnostics published when a recovery finishes; never used as working search state. */
    var lastFailure: RecoveryFailure? = null
        private set
    var lastSearchDiagnostic: RecoverySearchDiagnostic? = null
        private set
    var lastFormatAttempts: Int = 0
        private set

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
        val effectiveLimit = maxFormatMismatches.takeUnless { formatConstraint?.candidateCount == 1 }
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
        for (format in formats) {
            val extraction = runCatching { CodewordExtractor.extract(matrix, version, format.dataMask) }.getOrNull() ?: continue
            val de = runCatching { QrBlockInterleaver.deinterleave(extraction, version, format.ecLevel) }.getOrNull() ?: continue
            val dataBytes = de.blocks.flatMap { block ->
                (0 until block.numDataCodewords).map { block.codewords[it] }
            }.toIntArray()
            val unknownMasks = de.blocks.flatMap { block ->
                (0 until block.numDataCodewords).map { block.unknownMasks[it] }
            }.toIntArray()
            if (if (trustOnly) trustablePayloadHeaderAvailable(dataBytes, unknownMasks, version)
                else completePayloadHeaderAvailable(dataBytes, unknownMasks, version)) return true
        }
        return false
    }

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
        onPriorityProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<RecoveryCandidate> {
        require(maxFormatMismatches == null || maxFormatMismatches in 0..30)
        require(maxKnownDataEccMismatches == null || maxKnownDataEccMismatches in 0..MAX_QR_DATA_ECC_BITS)
        val run = RecoveryRunState(matrix, retentionBudget, cancellation)
        val conditions = RecoveryDisplayConditions(trustKnownPayloadCount, displayLockManualBits,
            formatConstraint, maxFormatMismatches, maxKnownDataEccMismatches)
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
        return try {
            cancellation.check()
            recoverRun(run, matrix, maxFormatCandidates, trustKnownPayloadCount, formatConstraint,
                maxFormatMismatches, maxKnownDataEccMismatches, manualEdits, onPriorityProgress)
                .map(::annotate)
        } finally {
            lastSearchDiagnostic = publishedDiagnostic
            lastFailure = run.failure?.copy(combinationSearch = run.combinationSearch, displayConditions = conditions,
                searchDiagnostic = lastSearchDiagnostic)
            lastFormatAttempts = run.formatAttempts
        }
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
        val effectiveLimit = maxFormatMismatches.takeUnless { formatConstraint?.candidateCount == 1 }
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
        require(priority.size <= 4) { "Priority search is limited to four modules" }
        val forcedFormats = formatConstraint?.let { constraint ->
            FormatSolver.ranked(matrix, run.ignoredFormatCoords)
                .filter { constraint.permits(it.ecLevel, it.dataMask) }
                .filter { effectiveLimit == null || it.mismatches <= effectiveLimit }
                .take(maxFormatCandidates)
        }
        if (priority.isEmpty()) return finish(run, recoverInternal(run, matrix, maxFormatCandidates, forcedFormats, allowBitEnumeration = true, trustKnownPayloadCount, maxFormatMismatches = effectiveLimit, maxKnownDataEccMismatches = maxKnownDataEccMismatches))
        val version = (matrix.dimension - 17) / 4
        val codewordCoords = CodewordExtractor.extract(matrix, version, 0).codewords
            .flatMapTo(hashSetOf()) { partial -> partial.coordinates.map { it.x to it.y } }
        require(priority.all { it in codewordCoords }) { "Priority search is only valid for data/ECC modules" }

        // First preserve ordinary QR behavior: every priority bit is still an erasure.
        val ordinary = recoverInternal(run, matrix, maxFormatCandidates, forcedFormats, allowBitEnumeration = false, trustKnownPayloadCount, maxFormatMismatches = effectiveLimit, maxKnownDataEccMismatches = maxKnownDataEccMismatches)
        if (ordinary.any { it.payloadParsed }) return finish(run, ordinary)

        // Then try only the explicitly authorized bits. Ordinary '?' modules remain erasures.
        val priorityResults = mutableListOf<RecoveryCandidate>()
        val totalAssignments = 1 shl priority.size
        for (assignment in 0 until totalAssignments) {
            run.cancellation.check()
            if (run.retentionBudget.exhausted) break
            onPriorityProgress?.invoke(assignment, totalAssignments)
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
            priorityResults += recoverInternal(run, guessed, maxFormatCandidates, guessedForcedFormats, allowBitEnumeration = true, trustKnownPayloadCount, maxFormatMismatches = effectiveLimit, maxKnownDataEccMismatches = maxKnownDataEccMismatches)
                .filter { it.payloadParsed }
                .map { it.copy(priorityAssignments = assignments) }
        }
        onPriorityProgress?.invoke(totalAssignments, totalAssignments)
        val distinct = priorityResults.sortedWith(recoveryCandidateComparator)
        if (distinct.isNotEmpty() || run.retentionBudget.exhausted) return finish(run, distinct.ifEmpty { ordinary })

        // No priority assignment produced a validated payload; retain the existing bounded '?'
        // search as the final fallback.
        return finish(run, recoverInternal(run, matrix, maxFormatCandidates, forcedFormats, allowBitEnumeration = true, trustKnownPayloadCount, maxFormatMismatches = effectiveLimit, maxKnownDataEccMismatches = maxKnownDataEccMismatches))
    }

    /** A format-consistent near miss is more useful than an unparsed RS coincidence. */
    private fun finish(run: RecoveryRunState, candidates: List<RecoveryCandidate>): List<RecoveryCandidate> {
        if (candidates.any { it.payloadParsed }) return mergeRecoveryDerivations(candidates)
        val bestCandidate = candidates.firstOrNull() ?: return candidates
        val failure = run.failure ?: return candidates
        val failureHasStrongerFormatEvidence =
            failure.formatMismatches < bestCandidate.formatMismatches ||
                (failure.formatMismatches == bestCandidate.formatMismatches && failure.formatMatches > bestCandidate.formatMatches)
        return if (failureHasStrongerFormatEvidence) emptyList() else candidates
    }

    private fun recoverInternal(
        run: RecoveryRunState,
        matrix: QrMatrix,
        maxFormatCandidates: Int,
        forcedFormats: List<FormatCandidate>?,
        allowBitEnumeration: Boolean,
        trustKnownPayloadCount: Boolean,
        allowPayloadConstraints: Boolean = true,
        maxFormatMismatches: Int? = null,
        maxKnownDataEccMismatches: Int? = null,
        formatRoute: FormatSelectionReason = FormatSelectionReason.SEARCH,
    ): List<RecoveryCandidate> {
        run.cancellation.check()
        val version = (matrix.dimension - 17) / 4
        val rankedFormats = FormatSolver.ranked(matrix, run.ignoredFormatCoords)
            .filter { maxFormatMismatches == null || it.mismatches <= maxFormatMismatches }
            .take(maxFormatCandidates)
        var formats = forcedFormats ?: rankedFormats
        val out = mutableListOf<RecoveryCandidate>()

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
                val fastResult = recoverInternal(run, matrix, 1, forcedFormats = listOf(trusted), allowBitEnumeration = allowBitEnumeration, trustKnownPayloadCount, allowPayloadConstraints, maxFormatMismatches, maxKnownDataEccMismatches, FormatSelectionReason.OBSERVED)
                // A BCH-trusted format is a hard constraint for this geometry. RS or payload
                // failure must not cause unrelated ECC-level/mask combinations to be tried.
                return fastResult
            } else {
                val provisional = FormatSolver.matchingInformationCandidate(matrix)?.let { provisional ->
                    rankedFormats.firstOrNull { it.maskedCodeword == provisional.maskedCodeword }
                }?.takeIf { nonRsManualConflicts(matrix, version, it.ecLevel, it.dataMask).isEmpty() }
                if (provisional != null) {
                    val fastResult = recoverInternal(run, matrix, 1, forcedFormats = listOf(provisional), allowBitEnumeration = allowBitEnumeration, trustKnownPayloadCount, allowPayloadConstraints, maxFormatMismatches, maxKnownDataEccMismatches, FormatSelectionReason.OBSERVED)
                    // Matching information bits are weaker than BCH agreement. They only provide
                    // ordering; an invalid payload must always fall back to the other 31 formats.
                    if (fastResult.any { it.payloadParsed }) return fastResult
                    out += fastResult
                    formats = rankedFormats.filterNot { it.maskedCodeword == provisional.maskedCodeword }
                }
            }
        }

        for (format in formats) {
            run.cancellation.check()
            if (run.retentionBudget.exhausted) break
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
                considerFailure(run, format, RecoveryFailureStage.EXTRACTION)
                continue
            }
            val de = try {
                QrBlockInterleaver.deinterleave(extraction, version, format.ecLevel)
            } catch (_: Exception) {
                run.unresolvedCalculations = true
                considerFailure(run, format, RecoveryFailureStage.DEINTERLEAVE)
                continue
            }
            // Preserve the pre-trial observation for metrics and Before/provenance views.
            // The trial extraction and blocks still drive RS and enforce temporary guesses.
            val evidenceExtraction = if (matrix === run.diagnosticInput) extraction else
                CodewordExtractor.extract(run.diagnosticInput, version, format.dataMask)
            val conventionalDiagnostic = run.conventionalDiagnostics.getOrPut(format.ecLevel to format.dataMask) {
                val inputBlocks = QrBlockInterleaver.deinterleave(evidenceExtraction, version, format.ecLevel)
                val diagnosis = ConventionalRsDiagnostic.analyze(inputBlocks, rs, run.cancellation)
                val raw = diagnosis.correctedInterleaved
                diagnosis.copy(
                    knownBitMismatches = raw?.let { countKnownBitMismatches(run.diagnosticInput, evidenceExtraction, it, format.dataMask).second },
                    knownBitMismatchLimit = maxKnownDataEccMismatches,
                    manualConflicts = raw?.let { userPinConflicts(run.diagnosticInput, evidenceExtraction, it, format.dataMask) }.orEmpty(),
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
            val rawByBlockLocal = HashMap<Long, Int>(de.rawLocations.size)
            fun key(blockIndex: Int, localIndex: Int): Long = (blockIndex.toLong() shl 32) or (localIndex.toLong() and 0xFFFFFFFFL)
            for (raw in de.rawLocations.indices) {
                val loc = de.rawLocations[raw] ?: continue
                rawByBlockLocal[key(loc.blockIndex, loc.localIndex)] = raw
            }
            val originalUnknownByRaw = IntArray(evidenceExtraction.codewords.size) { raw ->
                evidenceExtraction.codewords[raw].unknownBits and 0xFF
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
            val constraints = if (allowPayloadConstraints) {
                PayloadConstraintSolver.apply(de, version, trustKnownCount = trustKnownPayloadCount, cancellation = run.cancellation)
            } else {
                PayloadConstraintSolver.Result(0, 0, null, null)
            }
            val constraintKinds = HashMap<Pair<Int, Int>, PayloadConstraintSolver.ConstraintKind>()
            val constraintValues = HashMap<Pair<Int, Int>, Int>()
            for (filled in constraints.filled) {
                val raw = rawByBlockLocal[key(filled.blockIndex, filled.localByteIndex)] ?: continue
                constraintKinds[raw to filled.bitFromMsb] = filled.kind
                constraintValues[raw to filled.bitFromMsb] = filled.value
            }
            val postConstraintUnknownByRaw = IntArray(de.rawLocations.size) { raw ->
                val loc = requireNotNull(de.rawLocations[raw])
                de.blocks[loc.blockIndex].unknownMasks[loc.localIndex] and 0xFF
            }
            val constrainedErasures = de.blocks.sumOf { block -> block.unknownMasks.count { it != 0 } }
            val rsShortfallBits = minimumRsShortfallBits(de.blocks, de.ecSymbolsPerBlock)

            val blockCandidateResults = ArrayList<List<PartialCodewordSolver.Result>>(de.blocks.size)
            var erasures = 0
            var failed = false
            var failedBlockIndex: Int? = null
            var failedBlockErasures: Int? = null
            var failureMessage: String? = null
            for ((blockIndex, block) in de.blocks.withIndex()) {
                run.candidateCalculations++
                run.cancellation.check()
                val blockErasures = block.unknownMasks.count { it != 0 }
                erasures += blockErasures
                val results = try {
                    val nonEnumerable = priorityRawIndices.mapNotNullTo(hashSetOf()) { raw ->
                        de.rawLocations.getOrNull(raw)?.takeIf { it.blockIndex == blockIndex }?.localIndex
                    }
                    val space = if (constraints.filledBits == 0) originalSpaces[blockIndex]
                        else bitSolver.analyze(block, de.ecSymbolsPerBlock, run.cancellation)
                    partialSolver.decodeWithBitConstraints(
                        block,
                        de.ecSymbolsPerBlock,
                        space = space,
                        exactOnly = maxKnownDataEccMismatches == 0,
                        allowBitEnumeration = allowBitEnumeration,
                        nonEnumerablePositions = nonEnumerable,
                        cancellation = run.cancellation,
                    )
                } catch (error: ReedSolomonErasures.DecodeException) {
                    failed = true
                    failedBlockIndex = blockIndex
                    failedBlockErasures = blockErasures
                    failureMessage = error.message
                    break
                }
                blockCandidateResults += results
            }
            if (failed) {
                run.unresolvedCalculations = true
                if (classifyRsFailure(failureMessage, rsShortfallBits) in setOf(
                        RecoveryFailureReason.RS_BIT_SPACE_LIMIT, RecoveryFailureReason.BOUNDED_BIT_SEARCH_LIMIT)) {
                    run.boundedSearch = true
                }
                considerFailure(
                    run,
                    format,
                    RecoveryFailureStage.REED_SOLOMON,
                    originalErasedCodewords = originalErasures,
                    erasedCodewords = constrainedErasures,
                    rsShortfallBits = rsShortfallBits,
                    constraintFilledBits = constraints.filledBits,
                    partialGuessedBits = blockCandidateResults.sumOf { it.firstOrNull()?.guessedBits ?: 0 },
                    reason = classifyRsFailure(failureMessage, rsShortfallBits),
                    failedBlockIndex = failedBlockIndex,
                    rsBlockCount = de.blocks.size,
                    failedBlockErasures = failedBlockErasures,
                    ecSymbolsPerBlock = de.ecSymbolsPerBlock,
                    rsBitDiagnostic = bitDiagnostic,
                )
                // Payload structure is an optional accelerator, not a reason to discard an
                // otherwise recoverable QR. Retry this exact format once from freshly
                // deinterleaved, unconstrained blocks before moving to another format.
                if (allowPayloadConstraints && constraints.filledBits > 0) {
                    out += recoverInternal(
                        run,
                        matrix = matrix,
                        maxFormatCandidates = 1,
                        forcedFormats = listOf(format),
                        allowBitEnumeration = allowBitEnumeration,
                        trustKnownPayloadCount = false,
                        allowPayloadConstraints = false,
                        maxFormatMismatches = maxFormatMismatches,
                        maxKnownDataEccMismatches = maxKnownDataEccMismatches,
                        formatRoute = formatRoute,
                    )
                }
                continue
            }
            // Generate and validate a bounded search window lazily. The former product-size gate
            // discarded every candidate when the product exceeded 4096; this examines the best
            // ordered combinations in expanding windows and preserves any fully validated result.
            val combinationStops = linkedSetOf<CombinationSearchStop>()
            var parsedCandidatesForFormat = 0
            val combinations = bestFirstCartesianProductSequence(blockCandidateResults, { it.orderingPenalty },
                maxExpandedNodes = MAX_QR_COMBINATION_TRIALS,
                onLimitReached = { combinationStops += CombinationSearchStop.NODE_LIMIT },
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
                val correctedBlocks = combination.map { it.corrected }
                val data = buildList<Int> {
                    for (i in de.blocks.indices) {
                        val block = de.blocks[i]
                        val corrected = correctedBlocks[i]
                        for (j in 0 until block.numDataCodewords) add(corrected[j])
                    }
                }.toIntArray()
                val dataBytes = ByteArray(data.size) { data[it].toByte() }
                // Most Cartesian combinations are RS-valid blockwise but not a valid QR data
                // stream. Reject them before allocating provenance and reinterleaved arrays.
                if (!StrictQrPayloadValidator.isValid(dataBytes, version)) {
                    run.candidateRejections += RecoveryCandidateRejection.QR_STRUCTURE
                    continue
                }
                val errata = combination.sumOf { it.errataPositions.size }
                val partialBits = combination.sumOf { it.guessedBits }
                val minMargin = combination.minOfOrNull { it.rsMargin } ?: 0

                val repairInfo = DeferredRepairInfo(de.rawLocations.size) { raw ->
                    val loc = requireNotNull(de.rawLocations[raw])
                    val perBit = buildMap {
                        for (bit in 0 until 8) constraintKinds[raw to bit]?.let { put(bit, it) }
                    }
                    CodewordRepairInfo(
                        rawIndex = raw,
                        blockIndex = loc.blockIndex,
                        localIndex = loc.localIndex,
                        originalUnknownMask = originalUnknownByRaw[raw],
                        postConstraintUnknownMask = postConstraintUnknownByRaw[raw],
                        rsErrata = loc.localIndex in combination[loc.blockIndex].errataPositions,
                        partialEnumerated = loc.localIndex in combination[loc.blockIndex].guessedPositions,
                        constraintKindsByBit = perBit,
                        bitConstraintSolved = combination[loc.blockIndex].bitConstraintSolved,
                        constraintValuesByBit = buildMap {
                            for (bit in 0 until 8) constraintValues[raw to bit]?.let { put(bit, it) }
                        },
                    )
                }

                val correctedRaw = QrBlockInterleaver.reinterleave(de, correctedBlocks)
                val dataPinConflicts = userPinConflicts(matrix, extraction, correctedRaw, format.dataMask)
                if (dataPinConflicts.isNotEmpty()) {
                    // Temporary priority assignments reject this trial but are not manual input.
                    val inputPinConflicts = dataPinConflicts.filter {
                        val input = run.diagnosticInput[it.coord.x, it.coord.y]
                        input.source == ModuleSource.USER && input.value != ModuleValue.ERASED
                    }
                    if (inputPinConflicts.isEmpty()) {
                        run.unresolvedCalculations = true
                        continue
                    }
                    run.candidateRejections += RecoveryCandidateRejection.FIXED_CELLS
                    considerFailure(run, format, RecoveryFailureStage.PAYLOAD_VALIDATION,
                        originalErasedCodewords = originalErasures,
                        erasedCodewords = constrainedErasures,
                        rsShortfallBits = rsShortfallBits,
                        constraintFilledBits = constraints.filledBits,
                        reason = RecoveryFailureReason.MANUAL_PIN_CONFLICT,
                        rsBlockCount = de.blocks.size,
                        ecSymbolsPerBlock = de.ecSymbolsPerBlock,
                        rsBitDiagnostic = bitDiagnostic,
                        manualConflicts = inputPinConflicts)
                    continue
                }
                val (knownBitsCompared, knownMismatches) = countKnownBitMismatches(run.diagnosticInput, evidenceExtraction, correctedRaw, format.dataMask)
                if (maxKnownDataEccMismatches != null && knownMismatches > maxKnownDataEccMismatches) {
                    run.candidateRejections += RecoveryCandidateRejection.MISMATCH_LIMIT
                    considerFailure(
                        run,
                        format,
                        RecoveryFailureStage.PAYLOAD_VALIDATION,
                        originalErasedCodewords = originalErasures,
                        erasedCodewords = constrainedErasures,
                        rsShortfallBits = rsShortfallBits,
                        constraintFilledBits = constraints.filledBits,
                        partialGuessedBits = partialBits,
                        reason = RecoveryFailureReason.KNOWN_DATA_ECC_MISMATCH_LIMIT,
                        rsBlockCount = de.blocks.size,
                        ecSymbolsPerBlock = de.ecSymbolsPerBlock,
                        knownBitMismatches = knownMismatches,
                        knownBitsCompared = knownBitsCompared,
                        knownBitMismatchLimit = maxKnownDataEccMismatches,
                        rsBitDiagnostic = bitDiagnostic,
                    )
                    continue
                }
                val decoded = try {
                    ZxingPayloadBridge.decodeData(dataBytes, version, format.ecLevel, null)
                } catch (_: FormatException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                val text = decoded?.text
                run.acceptedCandidate = true
                if (!run.retentionBudget.tryRetain(correctedRaw.size, dataBytes.size, combination.size)) {
                    combinationStops += CombinationSearchStop.RETENTION_LIMIT
                    break
                }
                if (text != null) parsedCandidatesForFormat++

                out += RecoveryCandidate(
                    version = version,
                    ecLevel = format.ecLevel,
                    dataMask = format.dataMask,
                    text = text,
                    fnc1ApplicationIndicator = if (text != null) ZxingPayloadBridge.fnc1ApplicationIndicator(dataBytes) else null,
                    structuredAppend = decoded?.takeIf { it.hasStructuredAppend() }?.let {
                        StructuredAppendInfo(it.structuredAppendSequenceNumber ushr 4,
                            (it.structuredAppendSequenceNumber and 15) + 1, it.structuredAppendParity)
                    },
                    dataBytes = dataBytes,
                    formatRoute = formatRoute,
                    formatMatches = format.matches,
                    formatMismatches = format.mismatches,
                    originalErasedCodewords = originalErasures,
                    erasedCodewords = erasures,
                    correctedErrataSymbols = errata,
                    knownBitMismatchesAfterCorrection = knownMismatches,
                    knownBitsCompared = knownBitsCompared,
                    correctedInterleaved = correctedRaw,
                    extraction = evidenceExtraction,
                    constraintFilledBits = constraints.filledBits,
                    constraintPlans = constraints.viablePlans,
                    constraintEvidenceBits = constraints.evidenceBits,
                    constraintForcedByUserSetting = constraints.forcedByTrustedHeader,
                    inferredMode = constraints.inferredMode,
                    inferredCount = constraints.inferredCount,
                    partialGuessedBits = partialBits,
                    minRsMargin = minMargin,
                    conventionalRsDiagnostic = conventionalDiagnostic,
                    actualBlockMethods = combination.mapIndexed { blockIndex, result ->
                        RecoveryBlockMethod(blockIndex, result.bitConstraintSolved,
                            result.rsMargin.takeUnless { result.bitConstraintSolved }, result.guessedBits)
                    },
                    blockDerivations = combination.withIndex().filter { it.value.alternativeDerivations.isNotEmpty() }
                        .associate { (block, result) -> block to (listOf(result.derivation()) + result.alternativeDerivations) },
                    repairInfo = repairInfo,
                    rsBitDiagnostic = bitDiagnostic,
                )
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
            // The solver currently models a single data segment. Even a fully parsed constrained
            // result cannot prove that an erased second segment did not exist, so every mutation
            // made by this optional accelerator keeps a fresh unconstrained route in the result set.
            if (!run.retentionBudget.exhausted && shouldRetryWithoutPayloadConstraints(allowPayloadConstraints, constraints.filledBits)) {
                out += recoverInternal(
                    run,
                    matrix = matrix,
                    maxFormatCandidates = 1,
                    forcedFormats = listOf(format),
                    allowBitEnumeration = allowBitEnumeration,
                    trustKnownPayloadCount = false,
                    allowPayloadConstraints = false,
                    maxFormatMismatches = maxFormatMismatches,
                    maxKnownDataEccMismatches = maxKnownDataEccMismatches,
                    formatRoute = formatRoute,
                )
            }
        }
        return out.sortedWith(recoveryCandidateComparator)
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
        val current = run.failure
        if (current == null || failureComparator.compare(candidate, current) <= 0) run.failure = candidate
    }

    private val failureComparator = compareBy<RecoveryFailure> { it.formatMismatches }
        .thenByDescending { it.formatMatches }
        .thenBy { it.rsShortfallBits ?: Int.MAX_VALUE }
        .thenByDescending { it.stage.ordinal }
        // Do not replace an actionable rejection with the generic end-of-format diagnostic.
        .thenBy { if (it.reason == null) 1 else 0 }
        .thenBy { it.knownBitMismatches ?: Int.MAX_VALUE }
        .thenBy { it.erasedCodewords }
        .thenBy { it.partialGuessedBits }

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

    private fun userPinConflicts(matrix: QrMatrix, extraction: CodewordExtraction, correctedRaw: IntArray, mask: Int): List<ManualCellConflict> {
        val conflicts = mutableListOf<ManualCellConflict>()
        for (i in correctedRaw.indices) {
            val corrected = correctedRaw[i]
            for (b in 0 until 8) {
                val c = extraction.codewords[i].coordinates[b]
                val cell = matrix[c.x, c.y]
                if (cell.source != ModuleSource.USER) continue
                val observed = cell.value.bitOrNull() ?: continue
                val unmasked = (corrected shr (7 - b)) and 1
                val expectedObserved = unmasked xor if (CodewordExtractor.maskApplies(mask, c.x, c.y)) 1 else 0
                if (observed != expectedObserved) conflicts += ManualCellConflict(c, cell.value,
                    if (expectedObserved == 1) ModuleValue.ONE else ModuleValue.ZERO)
            }
        }
        return conflicts
    }

    private fun countKnownBitMismatches(matrix: QrMatrix, extraction: CodewordExtraction, correctedRaw: IntArray, mask: Int): Pair<Int, Int> {
        var known = 0
        var mismatches = 0
        for (i in correctedRaw.indices) {
            val corrected = correctedRaw[i]
            for (b in 0 until 8) {
                val c = extraction.codewords[i].coordinates[b]
                val observed = matrix[c.x, c.y].value.bitOrNull() ?: continue
                known++
                val unmasked = (corrected shr (7 - b)) and 1
                val expectedObserved = unmasked xor if (CodewordExtractor.maskApplies(mask, c.x, c.y)) 1 else 0
                if (observed != expectedObserved) mismatches++
            }
        }
        return known to mismatches
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

internal fun shouldRetryWithoutPayloadConstraints(allowPayloadConstraints: Boolean, filledBits: Int): Boolean =
    allowPayloadConstraints && filledBits > 0

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
