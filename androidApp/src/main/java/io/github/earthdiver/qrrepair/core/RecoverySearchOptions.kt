package io.github.earthdiver.qrrepair.core

/** Search conditions forwarded through format and priority trials; mutable budgets belong to the run. */
internal data class RecoverySearchOptions(
    val maxFormatCandidates: Int,
    val trustKnownPayloadCount: Boolean,
    val formatConstraint: QrFormatConstraint?,
    val maxFormatMismatches: Int?,
    val maxKnownDataEccMismatches: Int?,
    val manualEdits: Map<Coord, ModuleValue>,
)
