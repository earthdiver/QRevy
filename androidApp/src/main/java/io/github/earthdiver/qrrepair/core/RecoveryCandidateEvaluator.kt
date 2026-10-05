package io.github.earthdiver.qrrepair.core

import com.google.zxing.FormatException
import com.google.zxing.qrcode.decoder.ZxingPayloadBridge

/** The engine owns search bookkeeping; one evaluation only describes the examined combination. */
internal sealed interface RecoveryCandidateEvaluation {
    data class Accepted(val candidate: RecoveryCandidate, val decoderFailed: Boolean) : RecoveryCandidateEvaluation
    data class Rejected(val reason: RecoveryCandidateRejection, val failure: RecoveryFailure? = null) : RecoveryCandidateEvaluation
    data object UnresolvedPriorityAssignment : RecoveryCandidateEvaluation
}

/** Validates and describes combinations for one format without choosing search order or retention. */
internal class RecoveryCandidateEvaluator(
    private val version: Int,
    private val format: FormatCandidate,
    private val formatRoute: FormatSelectionReason,
    private val matrix: QrMatrix,
    private val diagnosticInput: QrMatrix,
    private val extraction: CodewordExtraction,
    private val evidenceExtraction: CodewordExtraction,
    private val de: DeinterleavedQr,
    private val constraints: PayloadConstraintSolver.Result,
    private val trackSingleSegment: Boolean,
    private val singleSegmentStatus: SingleSegmentStatus?,
    private val priorityAssignments: Map<Coord, ModuleValue>,
    private val conventionalDiagnostic: ConventionalRsDiagnostic,
    private val bitDiagnostic: RsBitDiagnostic,
    private val originalErasures: Int,
    private val constrainedErasures: Int,
    private val rsShortfallBits: Int,
    private val maxKnownDataEccMismatches: Int?,
    private val maxAlternativeDerivations: Int,
    private val listDecodingMismatchLimit: Int? = null,
) {
    private val originalUnknownByRaw = IntArray(evidenceExtraction.codewords.size) { raw ->
        evidenceExtraction.codewords[raw].unknownBits and 0xFF
    }

    private val constraintKinds = HashMap<Pair<Int, Int>, PayloadConstraintSolver.ConstraintKind>()
    private val constraintValues = HashMap<Pair<Int, Int>, Int>()
    private val postConstraintUnknownByRaw = IntArray(de.rawLocations.size) { raw ->
        val loc = requireNotNull(de.rawLocations[raw])
        de.blocks[loc.blockIndex].unknownMasks[loc.localIndex] and 0xFF
    }
    init {
        val rawByBlockLocal = HashMap<Long, Int>(de.rawLocations.size)
        fun key(blockIndex: Int, localIndex: Int): Long =
            (blockIndex.toLong() shl 32) or (localIndex.toLong() and 0xFFFFFFFFL)
        for (raw in de.rawLocations.indices) {
            val loc = de.rawLocations[raw] ?: continue
            rawByBlockLocal[key(loc.blockIndex, loc.localIndex)] = raw
        }
        for (filled in constraints.filled) {
            val raw = rawByBlockLocal[key(filled.blockIndex, filled.localByteIndex)] ?: continue
            constraintKinds[raw to filled.bitFromMsb] = filled.kind
            constraintValues[raw to filled.bitFromMsb] = filled.value
        }
    }

    fun evaluate(combination: List<PartialCodewordSolver.Result>): RecoveryCandidateEvaluation {
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
        if (trackSingleSegment && constraints.trustedStructure?.matches(dataBytes) != true) {
            return RecoveryCandidateEvaluation.Rejected(RecoveryCandidateRejection.SINGLE_SEGMENT_STRUCTURE)
        }
        if (!StrictQrPayloadValidator.isValid(dataBytes, version)) {
            return RecoveryCandidateEvaluation.Rejected(RecoveryCandidateRejection.QR_STRUCTURE)
        }
        val errata = combination.sumOf { it.errataPositions.size }
        val partialBits = combination.sumOf { it.guessedBits }
        val minMargin = if (combination.any { it.listDecoding != null }) 0 else combination.minOfOrNull { it.rsMargin } ?: 0

        // Retain only the selected route's explanation, not the solver's corrected block
        // arrays and every equivalent block derivation through a closure.
        val selectedRoutes = combination.map { it.derivation() }
        val repairInfo = RecoveryCandidateEvidence.repairInfo(
            de.rawLocations, selectedRoutes, originalUnknownByRaw,
            postConstraintUnknownByRaw, constraintKinds, constraintValues,
        )

        val correctedRaw = QrBlockInterleaver.reinterleave(de, correctedBlocks)
        val dataPinConflicts = RecoveryCandidateEvidence.userPinConflicts(matrix, extraction, correctedRaw, format.dataMask)
        if (dataPinConflicts.isNotEmpty()) {
            // Temporary priority assignments reject this trial but are not manual input.
            val inputPinConflicts = dataPinConflicts.filter {
                val input = diagnosticInput[it.coord.x, it.coord.y]
                input.source == ModuleSource.USER && input.value != ModuleValue.ERASED
            }
            if (inputPinConflicts.isEmpty()) {
                return RecoveryCandidateEvaluation.UnresolvedPriorityAssignment
            }
            return RecoveryCandidateEvaluation.Rejected(RecoveryCandidateRejection.FIXED_CELLS,
                failure(RecoveryFailureReason.MANUAL_PIN_CONFLICT, manualConflicts = inputPinConflicts))
        }
        val (knownBitsCompared, knownMismatches) = RecoveryCandidateEvidence.countKnownBitMismatches(diagnosticInput, evidenceExtraction, correctedRaw, format.dataMask)
        if (maxKnownDataEccMismatches != null && knownMismatches > maxKnownDataEccMismatches) {
            return RecoveryCandidateEvaluation.Rejected(if (listDecodingMismatchLimit != null) RecoveryCandidateRejection.LIST_DECODING_MISMATCH_LIMIT else RecoveryCandidateRejection.MISMATCH_LIMIT,
                failure(RecoveryFailureReason.KNOWN_DATA_ECC_MISMATCH_LIMIT,
                    partialGuessedBits = partialBits, knownBitMismatches = knownMismatches,
                    knownBitsCompared = knownBitsCompared, knownBitMismatchLimit = maxKnownDataEccMismatches))
        }
        var decoderFailed = false
        val decoded = try {
            ZxingPayloadBridge.decodeData(dataBytes, version, format.ecLevel, null)
        } catch (_: FormatException) {
            null
        } catch (_: RuntimeException) {
            decoderFailed = true
            null
        }
        val text = decoded?.text
        return RecoveryCandidateEvaluation.Accepted(RecoveryCandidate(
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
            erasedCodewords = constrainedErasures,
            correctedErrataSymbols = errata,
            knownBitMismatchesAfterCorrection = knownMismatches,
            knownBitsCompared = knownBitsCompared,
            correctedInterleaved = correctedRaw,
            extraction = evidenceExtraction,
            constraintFilledBits = constraints.filledBits,
            constraintPlans = constraints.viablePlans,
            constraintEvidenceBits = constraints.evidenceBits,
            constraintForcedByUserSetting = constraints.forcedByTrustedHeader,
            singleSegmentStatus = singleSegmentStatus,
            inferredMode = constraints.inferredMode,
            inferredCount = constraints.inferredCount,
            partialGuessedBits = partialBits,
            minRsMargin = minMargin,
            conventionalRsDiagnostic = conventionalDiagnostic,
            actualBlockMethods = combination.mapIndexed { blockIndex, result ->
                RecoveryBlockMethod(blockIndex, result.bitConstraintSolved,
                    result.rsMargin.takeUnless { result.bitConstraintSolved || result.listDecoding != null }, result.guessedBits, result.listDecoding)
            },
            blockDerivations = combination.withIndex().filter { it.value.alternativeDerivations.isNotEmpty() }
                .associate { (block, result) -> block to (listOf(selectedRoutes[block]) +
                    result.alternativeDerivations.take(maxAlternativeDerivations)) },
            derivationsOmitted = combination.any { it.alternativeDerivations.size > maxAlternativeDerivations },
            priorityAssignments = priorityAssignments,
            repairInfo = repairInfo,
            rsBitDiagnostic = bitDiagnostic,
            listDecodingMismatchLimit = listDecodingMismatchLimit,
        ), decoderFailed)
    }

    private fun failure(
        reason: RecoveryFailureReason,
        manualConflicts: List<ManualCellConflict> = emptyList(),
        partialGuessedBits: Int = 0,
        knownBitMismatches: Int? = null,
        knownBitsCompared: Int? = null,
        knownBitMismatchLimit: Int? = null,
    ) = RecoveryFailure(
        ecLevel = format.ecLevel,
        dataMask = format.dataMask,
        formatMatches = format.matches,
        formatMismatches = format.mismatches,
        formatKnownBits = format.knownBits,
        stage = RecoveryFailureStage.PAYLOAD_VALIDATION,
        reason = reason,
        originalErasedCodewords = originalErasures,
        erasedCodewords = constrainedErasures,
        rsShortfallBits = rsShortfallBits,
        constraintFilledBits = constraints.filledBits,
        partialGuessedBits = partialGuessedBits,
        rsBlockCount = de.blocks.size,
        ecSymbolsPerBlock = de.ecSymbolsPerBlock,
        knownBitMismatches = knownBitMismatches,
        knownBitsCompared = knownBitsCompared,
        knownBitMismatchLimit = knownBitMismatchLimit,
        rsBitDiagnostic = bitDiagnostic,
        manualConflicts = manualConflicts,
        conventionalRsDiagnostic = conventionalDiagnostic,
    )
}
