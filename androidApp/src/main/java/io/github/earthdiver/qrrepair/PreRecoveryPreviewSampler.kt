package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** Owns one preview's observation cache; every caller receives an independently editable matrix. */
internal class PreRecoveryPreviewSampler {
    private var sampledImage: LumaImage? = null
    private var sampledGeometry: GridGeometry? = null
    private var sampledProfile: SamplingProfile? = null
    private var sampledResult: Pair<QrMatrix, MatrixSampler.SamplingStats>? = null
    private var observations: Array<ModuleObservation?>? = null

    fun sample(image: LumaImage, geometry: GridGeometry, requested: SamplingProfile,
        logicalGridAssigned: Boolean): Pair<QrMatrix, MatrixSampler.SamplingStats> {
        // Effective slider values are outputs in automatic mode, not inputs to the next fit.
        val profile = if (requested.settings.automaticThresholds) requested.copy(settings = requested.settings.copy(
            blackMax = requested.provisionalThresholds.blackMax, whiteMin = requested.provisionalThresholds.whiteMin,
            automaticThresholds = logicalGridAssigned,
        )) else requested
        if (sampledImage !== image || sampledGeometry != geometry || sampledProfile != profile) {
            if (sampledImage !== image || sampledGeometry != geometry || sampledProfile?.inverted != profile.inverted) {
                observations = GridThresholdEstimator.observe(image, geometry, profile.inverted)
            }
            sampledResult = MatrixSampler(image, profile).sampleObserved(geometry, profile, requireNotNull(observations))
            sampledImage = image
            sampledGeometry = geometry
            sampledProfile = profile
        }
        val (matrix, stats) = requireNotNull(sampledResult)
        return matrix.copy() to stats
    }
}
