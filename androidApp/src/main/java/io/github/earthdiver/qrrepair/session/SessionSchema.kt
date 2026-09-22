package io.github.earthdiver.qrrepair.session

/** Wire capabilities are a property of the file version, never independent decoder options. */
internal class SessionSchema(val version: Int) {
    init { require(version in 9..RecoverySessionCodec.CURRENT_VERSION) }

    val hasPreprocessSettings: Boolean get() = version >= 3
    val hasGeometryLock: Boolean get() = version >= 4
    val hasPrioritySearch: Boolean get() = version >= 5
    val hasPerspectiveCorrection: Boolean get() = version >= 10
    val hasUnknownColorClusters: Boolean get() = version >= 11
    val hasLogicalGridAssignment: Boolean get() = version >= 12
    val hasFormatConstraint: Boolean get() = version >= 13
    val hasFormatMismatchLimit: Boolean get() = version >= 14
    val hasDataEccMismatchLimit: Boolean get() = version >= 15
    val hasSoftEdits: Boolean get() = version >= 16
    val hasFixedCorrections: Boolean get() = version >= 17
    val hasAutomaticThresholds: Boolean get() = version >= 18
    val hasSelectionIdentity: Boolean get() = version >= 19
    val hasErrorLimitModes: Boolean get() = version >= 20
    val hasImageAdjustment: Boolean get() = version >= 21
}
