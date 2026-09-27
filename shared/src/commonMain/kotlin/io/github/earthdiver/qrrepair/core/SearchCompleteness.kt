package io.github.earthdiver.qrrepair.core

/** Execution status of the specified search policy; not proof of all mathematically possible solutions. */
enum class SearchCompleteness {
    COMPLETE, UNSEARCHED, UNKNOWN;

    fun combine(other: SearchCompleteness): SearchCompleteness = when {
        this == UNSEARCHED || other == UNSEARCHED -> UNSEARCHED
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> COMPLETE
    }
}
