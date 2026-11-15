package io.github.earthdiver.qrrepair.core

/** Preserve detailed-search order and joint-fit replacements while delaying independent curves. */
internal fun detailedGeometrySequence(
    preview: GeometryHypothesis?,
    ordinary: List<GeometryHypothesis>,
    curvedSeeds: List<GeometryHypothesis>,
    needsJointFit: (GeometryHypothesis) -> Boolean,
    curves: (GridGeometry) -> Sequence<GridGeometry>,
    score: (GridGeometry) -> Double,
    refine: (GridGeometry) -> GridGeometry?,
    check: () -> Unit,
): Sequence<GeometryHypothesis> = sequence {
    val seenOriginal = mutableSetOf<GridGeometry>()
    val seenFinal = mutableSetOf<GridGeometry>()
    val replacements = mutableMapOf<GridGeometry, GridGeometry>()
    fun accept(hypothesis: GeometryHypothesis): GeometryHypothesis? {
        if (!seenOriginal.add(hypothesis.geometry)) return null
        val replacement = replacements[hypothesis.geometry]?.takeIf { hypothesis.geometry != preview?.geometry }
        val selected = if (replacement == null) hypothesis else
            hypothesis.copy(geometry = replacement, quickScore = score(replacement))
        return selected.takeIf { seenFinal.add(it.geometry) }
    }
    // The displayed grid can never be replaced by a later joint fit.
    preview?.let { check(); accept(it)?.let { selected -> yield(selected) } }
    val fitted = mutableMapOf<GridGeometry, List<GridGeometry>>()
    // Joint fitting selects the best of a seed's full candidate list. Resolve all such
    // replacements before emitting affected ordinary/curved slots, including duplicate slots.
    for (base in curvedSeeds.filter(needsJointFit)) {
        check()
        val candidates = curves(base.geometry).toList()
        fitted[base.geometry] = candidates
        val initial = candidates.minByOrNull(score) ?: base.geometry
        if (initial != preview?.geometry) refine(initial)?.let { replacements[initial] = it }
    }
    for (hypothesis in ordinary) {
        check()
        accept(hypothesis)?.let { yield(it) }
    }
    for (base in curvedSeeds) {
        check()
        val candidates = fitted[base.geometry]?.asSequence() ?: curves(base.geometry)
        for (geometry in candidates) {
            check()
            accept(base.copy(geometry = geometry, quickScore = score(geometry)))?.let { yield(it) }
        }
    }
}
