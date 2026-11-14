package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version

/** Image-only evidence outside the patterns used by joint fitting. */
internal fun imageUnknownCountForGeometryRanking(matrix: QrMatrix, version: Int): Int {
    val n = 17 + 4 * version
    val regions = QrCellRegions(version)
    return (0 until n).sumOf { y -> (0 until n).count { x ->
        val coord = Coord(x, y)
        val region = regions.structureAt(coord)
        (coord in regions.codewordCoordinates || region == RepairReasonKind.TIMING_PATTERN ||
            region == RepairReasonKind.FORMAT_INFORMATION) &&
            matrix[x, y].source == ModuleSource.IMAGE && matrix[x, y].value == ModuleValue.ERASED
    } }
}

internal fun geometryRankingUnknownCount(
    image: LumaImage,
    geometry: GridGeometry,
    profile: SamplingProfile,
    stopped: () -> Boolean,
): Int {
    val observations = GridThresholdEstimator.observe(image, geometry, profile.inverted, stopped)
    val matrix = MatrixSampler(image, profile).sampleObserved(geometry, profile, observations).first
    return imageUnknownCountForGeometryRanking(matrix, geometry.version)
}

/** Replaces one existing candidate only when fitting and independent image readings agree. */
internal fun jointlyRefinedGeometry(
    image: LumaImage,
    geometry: GridGeometry,
    profile: SamplingProfile,
    stopped: () -> Boolean,
): GridGeometry? {
    val refined = JointPatternRefiner.refine(image, geometry,
        Version.getVersionForNumber(geometry.version).alignmentPatternCenters, profile.inverted, stopped)?.geometry
        ?: return null
    if (stopped()) return null
    val before = geometryRankingUnknownCount(image, geometry, profile, stopped)
    val after = geometryRankingUnknownCount(image, refined, profile, stopped)
    return refined.takeIf { !stopped() && after <= before }
}
