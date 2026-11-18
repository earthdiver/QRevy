package io.github.earthdiver.qrrepair.core

import kotlin.math.hypot

/** Fits every assigned finder while retaining corners that have no finder constraint. */
internal fun alignQuadToFinders(quad: ImageQuad, version: Int, finders: Map<FinderRole, PointD>): ImageQuad {
    require(version in 1..40 && finders.isNotEmpty())
    val last = (16 + 4 * version).toDouble()
    val modules = listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last))
    val roles = listOf(FinderRole.TOP_LEFT, FinderRole.TOP_RIGHT, null, FinderRole.BOTTOM_LEFT)
    val original = quad.asList()
    val anchors = modules.mapIndexed { index, point ->
        if (roles[index] !in finders) point else when (roles[index]) {
            FinderRole.TOP_LEFT -> PointD(3.0, 3.0)
            FinderRole.TOP_RIGHT -> PointD(last - 3.0, 3.0)
            else -> PointD(3.0, last - 3.0)
        }
    }
    val fitted = Homography.fromCorrespondences(anchors,
        original.mapIndexed { index, point -> finders[roles[index]] ?: point })
    val corners = modules.mapIndexed { index, point ->
        if (roles[index] in finders) fitted.map(point) else original[index]
    }
    fun cross(points: List<PointD>, index: Int): Double {
        val a = points[(index + 1) % 4] - points[index]
        val b = points[(index + 2) % 4] - points[(index + 1) % 4]
        return a.x * b.y - a.y * b.x
    }
    require(corners.all { it.x.isFinite() && it.y.isFinite() })
    // Reject folded or reversed grids before replacing any observations.
    require(corners.indices.all { cross(corners, it) * cross(original, 0) > 0.0 })
    return ImageQuad(corners[0], corners[1], corners[2], corners[3])
}

/** Aligns the corner nearest [role] until its projective finder center reaches [finderCenter]. */
internal fun alignQuadCornerToFinder(
    quad: ImageQuad,
    version: Int,
    role: FinderRole,
    finderCenter: PointD,
    iterations: Int = 8,
): ImageQuad {
    require(iterations > 0)
    val dimension = 17 + 4 * version
    val cornerIndex = when (role) {
        FinderRole.TOP_LEFT -> 0
        FinderRole.TOP_RIGHT -> 1
        FinderRole.BOTTOM_LEFT -> 3
    }
    var current = quad
    repeat(iterations) {
        val pitch = ((current.topLeft.distanceTo(current.topRight) + current.topLeft.distanceTo(current.bottomLeft)) /
            (2.0 * (dimension - 1))).coerceAtLeast(.25)
        val geometry = GridGeometry(
            version, FinderRole.TOP_LEFT, current.topLeft.x, current.topLeft.y,
            pitch, 0.0, current, GeometryModel.MANUAL_PROJECTIVE,
        ).reanchored()
        val module = geometry.finderModulePoint(role)
        val predicted = geometry.moduleToImage(module.x, module.y)
        val old = current.asList()[cornerIndex]
        current = current.withCorner(
            cornerIndex,
            PointD(old.x + finderCenter.x - predicted.x, old.y + finderCenter.y - predicted.y),
        )
    }
    return current
}

/** Assigns a clearly corner-local candidate without claiming that the QR orientation is proven. */
internal fun provisionalFinderRole(candidate: FinderCandidate, imageWidth: Int, imageHeight: Int): FinderRole? {
    if (imageWidth <= 0 || imageHeight <= 0) return null
    val x = candidate.centerX / imageWidth
    val y = candidate.centerY / imageHeight
    return when {
        x < .45 && y < .45 -> FinderRole.TOP_LEFT
        x > .55 && y < .45 -> FinderRole.TOP_RIGHT
        x < .45 && y > .55 -> FinderRole.BOTTOM_LEFT
        else -> null
    }
}

internal fun bestProvisionalFinderCandidate(
    candidates: List<FinderCandidate>,
    imageWidth: Int,
    imageHeight: Int,
): FinderCandidate? = candidates.mapNotNull { candidate ->
    val reliable = candidate.score >= .78 && when (candidate.evidence) {
        FinderEvidence.COMPLETE_CROSS, FinderEvidence.PARTIAL_CORE -> candidate.observations >= 2
        FinderEvidence.MANUAL_HINT -> false
    }
    if (!reliable) return@mapNotNull null
    val role = provisionalFinderRole(candidate, imageWidth, imageHeight) ?: return@mapNotNull null
    val x = candidate.centerX / imageWidth
    val y = candidate.centerY / imageHeight
    val targetX = if (role == FinderRole.TOP_RIGHT) 1.0 else 0.0
    val targetY = if (role == FinderRole.BOTTOM_LEFT) 1.0 else 0.0
    Triple(candidate, role, hypot(x - targetX, y - targetY))
}.minWithOrNull(compareBy<Triple<FinderCandidate, FinderRole, Double>> { it.third }
    .thenByDescending { it.first.reliabilityScore() })?.first
