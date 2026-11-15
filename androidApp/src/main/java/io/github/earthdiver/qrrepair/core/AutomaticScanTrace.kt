package io.github.earthdiver.qrrepair.core

/** Bounded, local diagnostic events. No image bytes or recovered payloads are recorded. */
class AutomaticScanTrace(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val started = clockMillis()
    private val lines = mutableListOf<String>()
    private var omitted = 0
    val elapsedMillis: Long get() = (clockMillis() - started).coerceAtLeast(0)

    fun event(stage: String, detail: String) {
        if (lines.size < 512) lines += "${elapsedMillis}ms $stage: $detail" else omitted++
    }

    fun <T> measure(stage: String, action: () -> T): T {
        val start = elapsedMillis
        event(stage, "START")
        return try { action() } finally { event(stage, "END durationMs=${elapsedMillis - start}") }
    }

    fun report(): String = lines.joinToString("\n") + if (omitted > 0) "\nEvents omitted: $omitted" else ""

    fun gridObserver() = GridEstimationTrace { stage, detail -> event("GRID_$stage", detail) }
}
