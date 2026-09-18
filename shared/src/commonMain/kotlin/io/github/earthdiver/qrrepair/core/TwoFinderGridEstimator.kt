package io.github.earthdiver.qrrepair.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round

/** Similarity grids from observed Finders; missing Finder roles are never assigned. */
internal object TwoFinderGridEstimator {
    fun estimate(image: LumaImage): InitialGridEstimate? {
        val finders = FinderDetector(image).detect(6).filter {
            it.evidence == FinderEvidence.COMPLETE_CROSS && it.score >= 1.0 && it.moduleSize >= 2.0
        }
        if (finders.size < 2) return null
        data class Choice(val fit: FittedGeometry, val error: Double, val alignmentValid: Boolean,
            val timing: TimingEvidence, val separatorsValid: Boolean)
        val choices = mutableListOf<Choice>()
        for (fit in GeometryFitter.twoFinderHypotheses(finders)) {
            val g = fit.geometry
            // V1 has only five timing cells per axis and no alignment: insufficient independent evidence.
            if (g.version < 2) continue
            val observed = fit.finderIndices.map { finders[it] }
            fun axisError(angle: Double): Double {
                val d = angle - g.angleRad
                return abs(d - round(d / (PI / 2)) * (PI / 2))
            }
            if (observed.any { abs(it.moduleSize / g.modulePitch - 1.0) > .08 || axisError(it.angleRad) > .10 }) continue
            if (g.moduleCenterQuad().asList().any { !image.inBounds(it.x, it.y) }) continue
            fun mismatch(x: Double, y: Double, black: Boolean): Int {
                val p = g.moduleToImage(x, y)
                val value = image.sampleMean(p.x, p.y, 0.0) ?: return 1
                return if ((value <= image.threshold) == black) 0 else 1
            }
            var finderErrors = 0
            var verified = true
            for (role in fit.roleToFinderIndex.keys) {
                val center = g.finderModulePoint(role)
                for ((dx, dy) in offsets) {
                    var errors = 0
                    for (y in -3..3) for (x in -3..3) {
                        val black = abs(x) == 3 || abs(y) == 3 || (abs(x) <= 1 && abs(y) <= 1)
                        errors += mismatch(center.x + x + dx, center.y + y + dy, black)
                    }
                    if (errors > 2) verified = false
                    finderErrors += errors
                }
            }
            if (!verified) continue
            // The lower-right alignment pattern is present in Model 2 versions 2..40.
            // It provides independent image evidence for the inferred extent and logical orientation.
            val center = (g.dimension - 7).toDouble()
            var alignmentErrors = 0
            for (y in -2..2) for (x in -2..2) {
                alignmentErrors += mismatch(center + x, center + y,
                    abs(x) == 2 || abs(y) == 2 || (x == 0 && y == 0))
            }
            choices += Choice(fit, alignmentErrors / 25.0 + finderErrors / 490.0,
                alignmentErrors <= 1, timingEvidence(image, fit), separatorsAgree(image, fit))
        }
        // Compare all Finder-verified hypotheses, including ones failing the timing/coverage gate.
        // Otherwise rejecting the runner-up could make an ambiguous role appear certain.
        val timingOrder = choices.sortedBy { it.timing.error }
        val timed = timingOrder.firstOrNull()
        if (timed != null && timed.timing.sufficient && timed.separatorsValid &&
            timed.fit.roleToFinderIndex.keys == setOf(FinderRole.TOP_RIGHT, FinderRole.BOTTOM_LEFT) &&
            timingOrder.drop(1).all { it.timing.error - timed.timing.error >= .12 }) {
            val g = timed.fit.geometry
            val roles = timed.fit.roleToFinderIndex.mapValues { (_, index) ->
                val f = finders[index]
                PointD(f.centerX, f.centerY)
            }
            return InitialGridEstimate(g.version, g.moduleCenterQuad(), InitialGridSource.TWO_FINDER, roles)
        }
        // Retain the alignment-supported provisional grid when timing cannot settle the roles.
        val ordered = choices.filter { it.alignmentValid }.sortedBy { it.error }
        val best = ordered.firstOrNull() ?: return null
        // Do not choose arbitrarily between symbols, versions or logical orientations.
        if (ordered.drop(1).any { it.error - best.error < .05 }) return null
        return InitialGridEstimate(best.fit.geometry.version, best.fit.geometry.moduleCenterQuad(), InitialGridSource.TWO_FINDER)
    }

    private val offsets = listOf(0.0 to 0.0, -.2 to 0.0, .2 to 0.0, 0.0 to -.2, 0.0 to .2)
    private data class TimingEvidence(val error: Double, val sufficient: Boolean)

    private fun timingEvidence(image: LumaImage, fit: FittedGeometry): TimingEvidence {
        val g = fit.geometry
        fun sample(x: Double, y: Double): Double? {
            val p = g.moduleToImage(x, y)
            return image.sampleMean(p.x, p.y, 0.0)
        }
        // Calibrate an unknown band from the two verified Finders, not the global background.
        val black = mutableListOf<Double>()
        val white = mutableListOf<Double>()
        for (role in fit.roleToFinderIndex.keys) {
            val c = g.finderModulePoint(role)
            for (y in -3..3) for (x in -3..3) {
                val v = sample(c.x + x, c.y + y) ?: continue
                if (abs(x) == 3 || abs(y) == 3 || (abs(x) <= 1 && abs(y) <= 1)) black += v else white += v
            }
        }
        if (black.isEmpty() || white.isEmpty()) return TimingEvidence(1.0, false)
        val dark = black.sorted()[black.size / 2]
        val light = white.sorted()[white.size / 2]
        if (light - dark < 64) return TimingEvidence(1.0, false)
        val darkLimit = dark + (light - dark) * .3
        val lightLimit = dark + (light - dark) * .7
        val count = g.dimension - 16
        var worstError = 0.0
        var sufficient = true
        for ((dx, dy) in offsets) for (horizontal in listOf(true, false)) {
            var known = 0; var errors = 0; var blacks = 0; var whites = 0
            for (i in 8..g.dimension - 9) {
                val v = if (horizontal) sample(i + dx, 6.0 + dy) else sample(6.0 + dx, i + dy)
                val observed = when {
                    v == null -> null
                    v <= darkLimit -> true
                    v >= lightLimit -> false
                    else -> null
                } ?: continue
                known++
                if (observed) blacks++ else whites++
                if (observed != (i % 2 == 0)) errors++
            }
            // Unknown samples supply no positive evidence and still penalize the ranking.
            worstError = maxOf(worstError, (errors + .5 * (count - known)) / count)
            if (known < 8 || known < count * .6 || blacks < 3 || whites < 3 || errors > known * .05) sufficient = false
        }
        return TimingEvidence(worstError, sufficient)
    }

    private fun separatorsAgree(image: LumaImage, fit: FittedGeometry): Boolean {
        val g = fit.geometry
        for (role in fit.roleToFinderIndex.keys) {
            val c = g.finderModulePoint(role)
            val sx = if (role == FinderRole.TOP_RIGHT) -4 else 4
            val sy = if (role == FinderRole.BOTTOM_LEFT) -4 else 4
            for ((dx, dy) in offsets) {
                fun black(x: Double, y: Double): Int {
                    val p = g.moduleToImage(x + dx, y + dy)
                    val v = image.sampleMean(p.x, p.y, 0.0) ?: return 1
                    return if (v <= image.threshold) 1 else 0
                }
                var errors = black(c.x + sx, c.y + sy)
                for (i in -3..3) errors += black(c.x + sx, c.y + i) + black(c.x + i, c.y + sy)
                if (errors > 1) return false
            }
        }
        return true
    }

}
