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
