package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.max

/** Count image-supported logical finders, not manual role assignments or unrelated detections. */
internal fun verifiedFinderCount(image: LumaImage, profile: SamplingProfile, geometry: GridGeometry,
    stopped: () -> Boolean = { false }): Int {
    var count = 0
    for (role in FinderRole.entries) {
        if (stopped()) return count
        val center = geometry.finderModulePoint(role)
        var matches = 0
        for (dy in -3..3) for (dx in -3..3) {
            val x = center.x + dx
            val y = center.y + dy
            val point = geometry.moduleToImage(x, y)
            val luminance = image.sampleMean(point.x, point.y, geometry.localModulePitch(x, y) * .12)
                ?.let(profile::normalize) ?: continue
            val radius = max(abs(dx), abs(dy))
            val black = radius == 3 || radius <= 1
            if (if (black) luminance <= profile.settings.blackMax else luminance >= profile.settings.whiteMin) matches++
        }
        // Require at least 45 of the 49 cells to support the full finder footprint.
        if (matches >= 45) count++
    }
    return count
}

internal fun needsUncorrectedGridVerification(image: LumaImage, profile: SamplingProfile,
    geometry: GridGeometry, stopped: () -> Boolean = { false }): Boolean {
    if (stopped() || verifiedFinderCount(image, profile, geometry, stopped) == 3 || stopped()) return false
    val alignment = AlignmentDetector(image, profile).findBest(geometry, stopped = stopped)
    if (stopped()) return false
    if (alignment == null) return true
    // A relative-contrast match alone can mistake the edge of a missing area for a pattern.
    // Require observed black/white support; unknown pixels cannot establish a landmark.
    val pitch = geometry.localModulePitch(alignment.moduleX.toDouble(), alignment.moduleY.toDouble())
    var matches = 0
    for (dy in -2..2) for (dx in -2..2) {
        val luminance = image.sampleMean(alignment.imageX + dx * pitch, alignment.imageY + dy * pitch, pitch * .12)
            ?.let(profile::normalize) ?: continue
        val radius = max(abs(dx), abs(dy))
        if (if (radius == 2 || radius == 0) luminance <= profile.settings.blackMax
            else luminance >= profile.settings.whiteMin) matches++
    }
    return matches < 21 && !stopped()
}
