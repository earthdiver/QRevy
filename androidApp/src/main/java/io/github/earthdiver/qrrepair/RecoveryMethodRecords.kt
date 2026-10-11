package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** Group explanations without discarding the original calculations or cell provenance. */
internal data class RecoveryMethodRecords(val records: List<RecoveryCandidate>) {
    val representative: RecoveryCandidate get() = records.first()

    val structureChecks: List<RecoveryStructureCheck>
        get() = records.map { RecoveryStructureCheck(it.constraintPlans, it.inferredMode,
            it.inferredCount, it.constraintEvidenceBits) }.distinct()

    val blockDerivations: Map<Int, List<PartialCodewordSolver.Derivation>>
        get() = records.flatMap { it.blockDerivations.keys }.distinct().associateWith { block ->
            records.flatMap { it.blockDerivations[block].orEmpty() }
                // Search ordering is not a different calculation method.
                .distinctBy { it.copy(searchPenalty = 0) }
        }.filterValues { it.size > 1 }
}

internal data class RecoveryStructureCheck(val plans: Int, val mode: Int?, val count: Int?, val evidenceBits: Int) {
    val recorded: Boolean get() = plans > 0 || mode != null || count != null || evidenceBits > 0
}

internal enum class RecoveryMethodDifference {
    INPUT, STRUCTURE, PRIORITY, BLOCKS, CELL_REPAIRS, ASSUMPTION, LIMIT, SETTINGS,
}

/** Compare calculation evidence, never formatted descriptions or their aggregate bit counts alone. */
internal fun RecoveryCandidate.methodDifferences(other: RecoveryCandidate): List<RecoveryMethodDifference> = buildList {
    if (version != other.version || ecLevel != other.ecLevel || dataMask != other.dataMask ||
        !correctedInterleaved.contentEquals(other.correctedInterleaved) || extraction != other.extraction)
        add(RecoveryMethodDifference.INPUT)
    if (constraintFilledBits != other.constraintFilledBits ||
        ((constraintFilledBits > 0 || constraintForcedByUserSetting || other.constraintFilledBits > 0 || other.constraintForcedByUserSetting) &&
            (inferredMode != other.inferredMode || inferredCount != other.inferredCount)))
        add(RecoveryMethodDifference.STRUCTURE)
    if (priorityAssignments != other.priorityAssignments) add(RecoveryMethodDifference.PRIORITY)
    if (actualBlockMethods != other.actualBlockMethods || partialGuessedBits != other.partialGuessedBits ||
        minRsMargin != other.minRsMargin || originalErasedCodewords != other.originalErasedCodewords ||
        erasedCodewords != other.erasedCodewords || correctedErrataSymbols != other.correctedErrataSymbols)
        add(RecoveryMethodDifference.BLOCKS)
    if (repairInfo != other.repairInfo) add(RecoveryMethodDifference.CELL_REPAIRS)
    if (constraintForcedByUserSetting != other.constraintForcedByUserSetting || singleSegmentStatus != other.singleSegmentStatus)
        add(RecoveryMethodDifference.ASSUMPTION)
    if (listDecodingMismatchLimit != other.listDecodingMismatchLimit) add(RecoveryMethodDifference.LIMIT)
    if (displayConditions != other.displayConditions || formatSelection != other.formatSelection || formatRoute != other.formatRoute)
        add(RecoveryMethodDifference.SETTINGS)
}

internal fun RecoveryCandidate.groupedMethodRecords(): List<RecoveryMethodRecords> {
    val groups = mutableListOf<MutableList<RecoveryCandidate>>()
    for (route in listOf(this) + alternativeDerivations) {
        val matching = groups.firstOrNull { it.first().methodDifferences(route).isEmpty() }
        if (matching != null) matching.add(route) else groups.add(mutableListOf(route))
    }
    return groups.map { RecoveryMethodRecords(it) }
}


/** Inputs to the block calculation, excluding the choice of RS solver and its target positions. */
private fun RecoveryCandidate.preparationEvidence(): List<CodewordRepairInfo> = repairInfo.map {
    it.copy(rsErrata = false, partialEnumerated = false, bitConstraintSolved = false, listDecoding = null)
}

internal fun RecoveryCandidate.sameStructurePreparation(other: RecoveryCandidate): Boolean {
    if (version != other.version || ecLevel != other.ecLevel || dataMask != other.dataMask || extraction != other.extraction ||
        constraintFilledBits != other.constraintFilledBits || constraintForcedByUserSetting != other.constraintForcedByUserSetting ||
        singleSegmentStatus != other.singleSegmentStatus) return false
    if (constraintFilledBits > 0 || constraintForcedByUserSetting) {
        if (inferredMode != other.inferredMode || inferredCount != other.inferredCount) return false
        // Older records may have counts without the assigned values. Counts alone cannot establish equality.
        if (constraintFilledBits > 0 && (repairInfo.sumOf { it.constraintValuesByBit.size } != constraintFilledBits ||
                other.repairInfo.sumOf { it.constraintValuesByBit.size } != other.constraintFilledBits)) return false
    }
    return preparationEvidence() == other.preparationEvidence()
}

internal fun RecoveryCandidate.samePriorityPreparation(other: RecoveryCandidate): Boolean =
    priorityAssignments == other.priorityAssignments && (priorityAssignments.isEmpty() ||
        (version == other.version && ecLevel == other.ecLevel && dataMask == other.dataMask && extraction == other.extraction))

private fun RecoveryCandidate.hasCompleteRepairInfo(): Boolean =
    extraction.codewords.isNotEmpty() && repairInfo.size == extraction.codewords.size &&
        correctedInterleaved.size == repairInfo.size && repairInfo.withIndex().all { it.index == it.value.rawIndex }

/** Reconstruct only fully recorded routes; missing provenance must not become an equality claim. */
internal fun RecoveryCandidate.selectedBlockDerivations(): Map<Int, PartialCodewordSolver.Derivation> {
    // Deferred provenance is generated once per word, rather than rescanned for every block.
    val byBlock = if (hasCompleteRepairInfo()) repairInfo.groupBy { it.blockIndex } else emptyMap()
    return actualBlockMethods.mapNotNull { method ->
        val block = method.blockIndex
        val recorded = blockDerivations[block]?.firstOrNull()?.takeIf {
            it.bitConstraintSolved == method.bitConstraintSolved && it.guessedBits == method.guessedBits &&
                it.listDecoding == method.listDecoding && (method.rsMargin == null || it.rsMargin == method.rsMargin)
        }
        if (recorded != null) return@mapNotNull block to recorded.copy(searchPenalty = 0)
        val info = byBlock[block] ?: return@mapNotNull null
        if (info.any { it.bitConstraintSolved != method.bitConstraintSolved || it.listDecoding != method.listDecoding })
            return@mapNotNull null
        val guessed = info.filter { it.partialEnumerated }.map { it.localIndex }
        block to PartialCodewordSolver.Derivation(info.filter { it.rsErrata }.map { it.localIndex },
            method.guessedBits, guessed.size, guessed, method.rsMargin ?: 0, 0, method.bitConstraintSolved, method.listDecoding)
    }.toMap()
}

internal fun RecoveryCandidate.sameBlockCalculationInput(other: RecoveryCandidate): Boolean =
    hasCompleteRepairInfo() && other.hasCompleteRepairInfo() && sameStructurePreparation(other) &&
        methodDifferences(other).all { it == RecoveryMethodDifference.BLOCKS || it == RecoveryMethodDifference.CELL_REPAIRS }

/** A recorded calculation without fills must establish the same QR under the same other conditions. */
internal fun RecoveryCandidate.sameQrWithoutStructureFilling(other: RecoveryCandidate): Boolean {
    if (constraintFilledBits <= 0 || other.constraintFilledBits != 0 || displayConditions?.lockManualBits == null ||
        displayConditions != other.displayConditions || formatSelection != other.formatSelection || formatRoute != other.formatRoute ||
        version != other.version || ecLevel != other.ecLevel || dataMask != other.dataMask ||
        priorityAssignments != other.priorityAssignments || listDecodingMismatchLimit != other.listDecodingMismatchLimit ||
        constraintForcedByUserSetting != other.constraintForcedByUserSetting || singleSegmentStatus != other.singleSegmentStatus)
        return false
    if (constraintForcedByUserSetting && (inferredMode != other.inferredMode || inferredCount != other.inferredCount)) return false
    if (extraction != other.extraction || !correctedInterleaved.contentEquals(other.correctedInterleaved) ||
        !hasCompleteRepairInfo() || !other.hasCompleteRepairInfo()) return false
    return extraction.codewords.all { it.coordinates.size == 8 } &&
        repairInfo.sumOf { it.constraintValuesByBit.size } == constraintFilledBits &&
        other.repairInfo.all { it.constraintValuesByBit.isEmpty() && it.constraintKindsByBit.isEmpty() }
}

/** Stable labels for the displayed QR methods, including records beyond Z. */
internal fun recoveryMethodLabel(index: Int): String {
    require(index >= 0)
    var value = index + 1
    return buildString {
        while (value > 0) {
            value--
            append('A' + value % 26)
            value /= 26
        }
    }.reversed()
}
