package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** Owns one preview's observation cache; every caller receives an independently editable matrix. */
internal class PreRecoveryPreviewSampler {
    data class ObservationInput(val image: LumaImage, val geometry: GridGeometry, val inverted: Boolean)

    private var sampledImage: LumaImage? = null
    private var sampledGeometry: GridGeometry? = null
    private var sampledProfile: SamplingProfile? = null
    private var sampledResult: Pair<QrMatrix, MatrixSampler.SamplingStats>? = null
    private var observations: Array<ModuleObservation?>? = null
    private var observedInput: ObservationInput? = null

    fun hasObservations(input: ObservationInput): Boolean = observedInput == input

    /** Accept a worker's immutable observations before classifying on the UI thread. */
    fun acceptObservations(input: ObservationInput, result: Array<ModuleObservation?>) {
        require(result.size == input.geometry.dimension * input.geometry.dimension)
        observedInput = input
        observations = result
        sampledResult = null
    }

    fun sample(image: LumaImage, geometry: GridGeometry, requested: SamplingProfile,
        logicalGridAssigned: Boolean, stopRequested: () -> Boolean = { false }): Pair<QrMatrix, MatrixSampler.SamplingStats> {
        if (stopRequested()) throw RecoveryCancelledException()
        // Effective slider values are outputs in automatic mode, not inputs to the next fit.
        val profile = if (requested.settings.automaticThresholds) requested.copy(settings = requested.settings.copy(
            blackMax = requested.provisionalThresholds.blackMax, whiteMin = requested.provisionalThresholds.whiteMin,
            automaticThresholds = logicalGridAssigned,
        )) else requested
        if (sampledResult == null || sampledImage !== image || sampledGeometry != geometry || sampledProfile != profile) {
            val input = ObservationInput(image, geometry, profile.inverted)
            if (!hasObservations(input)) {
                acceptObservations(input, GridThresholdEstimator.observe(image, geometry, profile.inverted, stopRequested))
            }
            if (stopRequested()) throw RecoveryCancelledException()
            sampledResult = MatrixSampler(image, profile).sampleObserved(geometry, profile, requireNotNull(observations))
            sampledImage = image
            sampledGeometry = geometry
            sampledProfile = profile
        }
        val (matrix, stats) = requireNotNull(sampledResult)
        return matrix.copy() to stats
    }
}
