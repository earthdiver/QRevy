package io.github.earthdiver.qrrepair.core

import kotlin.time.TimeSource

/** One repair's cumulative GS time. Ordinary recovery between GS passes does not consume it. */
class GsSearchBudget(
    val limitMillis: Long = 300_000,
    private val onProgress: ((elapsedMillis: Long, limitMillis: Long) -> Unit)? = null,
    private val clockMillis: () -> Long = monotonicClock(),
) {
    init { require(limitMillis >= 0) }
    private var accumulated = 0L
    private var started: Long? = null
    val elapsedMillis: Long get() = accumulated + (started?.let { (clockMillis() - it).coerceAtLeast(0) } ?: 0)
    val exhausted: Boolean get() = elapsedMillis >= limitMillis

    private var lastReported = -250L
    fun check() {
        val elapsed = elapsedMillis
        if (started != null && elapsed - lastReported >= 250) {
            lastReported = elapsed
            onProgress?.invoke(elapsed, limitMillis)
        }
        if (elapsed >= limitMillis) throw GsTimeLimitException()
    }

    fun <T> measure(action: () -> T): T {
        check()
        if (started != null) return action()
        started = clockMillis()
        try {
            onProgress?.invoke(elapsedMillis, limitMillis)
            return action()
        } finally {
            accumulated = elapsedMillis
            started = null
        }
    }

    companion object {
        private fun monotonicClock(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}

/** These resource stops must never be reported as an invalid RS or QR hypothesis. */
class GsTimeLimitException : RuntimeException("GS time allowance exhausted")
class GsWorkingMemoryException : RuntimeException("GS working-memory allowance exhausted")

/** Parameters and trial evidence for one list-decoded block, independent of original observations. */
data class GsDerivation(
    val radius: Int,
    val multiplicity: Int,
    val listDegree: Int,
    val remainingErasures: Int,
    val symbolErrors: Int,
    val assignment: Int,
)
