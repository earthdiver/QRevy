package io.github.earthdiver.qrrepair.core

import kotlin.time.TimeSource

/** One repair's cumulative list-decoding time. Ordinary recovery between passes does not consume it. */
class ListDecodingBudget(
    val limitMillis: Long = 600_000,
    private val onProgress: ((elapsedMillis: Long, limitMillis: Long) -> Unit)? = null,
    private val onIdle: (() -> Unit)? = null,
    private val clockMillis: () -> Long = monotonicClock(),
) {
    init { require(limitMillis >= 0) }
    private var accumulated = 0L
    private var started: Long? = null
    private var sliceEnd: Long? = null
    val elapsedMillis: Long get() = accumulated + (started?.let { (clockMillis() - it).coerceAtLeast(0) } ?: 0)
    val exhausted: Boolean get() = elapsedMillis >= limitMillis

    private var lastReported = -250L
    fun check() {
        val elapsed = elapsedMillis
        if (started != null && elapsed - lastReported >= 250) {
            lastReported = elapsed
            onProgress?.invoke(elapsed, limitMillis)
        }
        if (elapsed >= limitMillis) throw ListDecodingTimeLimitException()
        if (sliceEnd?.let { elapsed >= it } == true) throw ListDecodingSliceLimitException()
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
            onIdle?.invoke()
        }
    }

    /** A short first pass shares the cumulative clock but cannot consume later solver work. */
    fun <T> measureSlice(allowanceMillis: Long, action: () -> T): T {
        require(allowanceMillis >= 0)
        val previous = sliceEnd
        sliceEnd = minOf(previous ?: Long.MAX_VALUE, elapsedMillis + allowanceMillis)
        return try { measure { check(); action() } } finally { sliceEnd = previous }
    }

    companion object {
        private fun monotonicClock(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}

/** These resource stops must never be reported as an invalid RS or QR hypothesis. */
class ListDecodingTimeLimitException : RuntimeException("List-decoding time allowance exhausted")
class ListDecodingSliceLimitException : RuntimeException("List-decoding first pass deferred")
class ListDecodingWorkingMemoryException : RuntimeException("List-decoding working-memory allowance exhausted")

/** Parameters and trial evidence for one list-decoded block, independent of original observations. */
data class ListDecodingDerivation(
    val radius: Int,
    val multiplicity: Int,
    val listDegree: Int,
    val remainingErasures: Int,
    val symbolErrors: Int,
    val assignment: Int,
    val method: ListDecoderMethod = ListDecoderMethod.GURUSWAMI_SUDAN,
    val score: Int? = null,
    val scoreThreshold: Int? = null,
)

enum class ListDecoderMethod { GURUSWAMI_SUDAN, KOETTER_VARDY, WU }
