package io.github.earthdiver.qrrepair.core

/** Reproducible search conditions, independent of mutable execution budgets and callbacks. */
data class RecoveryRequest(
    val maxFormatCandidates: Int = 32,
    val trustKnownPayloadCount: Boolean = false,
    val formatConstraint: QrFormatConstraint? = null,
    val maxFormatMismatches: Int? = null,
    val maxKnownDataEccMismatches: Int? = null,
    val manualEdits: Map<Coord, ModuleValue> = emptyMap(),
    val displayLockManualBits: Boolean? = null,
    val partialBitSearchLimit: Int = PartialBitSearchLimit.DEFAULT,
    val listDecodingAdditionalErrors: Int = ListDecodingLimit.DEFAULT,
    val knownBitErrorLimit: Int = 0,
    val assumeUtf8ByteSegments: Boolean = false,
    val executionPolicy: RecoveryExecutionPolicy = RecoveryExecutionPolicy.DETAILED,
    val allowAutomaticPrioritySearch: Boolean = false,
) {
    companion object {
        /** Resolve presets for the actual geometry and snapshot manual evidence for this request. */
        fun fromSettings(settings: PreprocessSettings, version: Int = settings.version): RecoveryRequest = RecoveryRequest(
            trustKnownPayloadCount = settings.trustKnownPayloadCount,
            assumeUtf8ByteSegments = settings.assumeUtf8ByteSegments,
            partialBitSearchLimit = settings.partialBitSearchLimit,
            listDecodingAdditionalErrors = settings.listDecodingAdditionalErrors,
            knownBitErrorLimit = settings.knownBitErrorLimit,
            formatConstraint = settings.formatConstraint,
            maxFormatMismatches = settings.effectiveFormatMismatchLimit,
            maxKnownDataEccMismatches = ErrorLimitPolicy.data(version, settings.dataEccMismatchLimitMode, settings.dataEccMismatchLimit),
            manualEdits = settings.fixedPreRecoveryEdits.toMap(),
            displayLockManualBits = settings.lockManualBits,
        )
    }
}

/** Budgets are fresh by default; callers may explicitly share them across related trials. */
class RecoveryExecutionControls(
    val retentionBudget: RecoveryRetentionBudget = RecoveryRetentionBudget(),
    val cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
    val stopRequested: () -> Boolean = { false },
    val listDecodingBudget: ListDecodingBudget = ListDecodingBudget(),
    val knownBitErrorBudget: KnownBitErrorBudget = KnownBitErrorBudget(),
    val discoveryCounter: RecoveryDiscoveryCounter = RecoveryDiscoveryCounter(),
    /** Classifies an already requested stop; never decides whether to stop. */
    val stopIsTimeLimit: () -> Boolean = { false },
    val onPriorityProgress: ((done: Int, total: Int) -> Unit)? = null,
)
