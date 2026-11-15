package io.github.earthdiver.qrrepair.core

import kotlin.time.TimeSource

/** Optional stage diagnostics; implementations must not retain source-image data. */
fun interface GridEstimationTrace {
    fun record(stage: String, detail: String)
}

internal inline fun <T> GridEstimationTrace?.measure(stage: String, action: () -> T): T {
    if (this == null) return action()
    val start = TimeSource.Monotonic.markNow()
    record(stage, "START")
    return try {
        action().also { record(stage, "END durationMs=${start.elapsedNow().inWholeMilliseconds}") }
    } catch (e: RecoveryCancelledException) {
        record(stage, "CANCELLED durationMs=${start.elapsedNow().inWholeMilliseconds}")
        throw e
    }
}
