package io.github.earthdiver.qrrepair.core

/** Machine-readable failures shared by RS decoding and bounded searches over RS blocks. */
enum class RsFailureReason {
    ERASURE_CAPACITY_EXCEEDED,
    ERROR_ERASURE_CAPACITY_EXCEEDED,
    BOUNDED_BIT_SEARCH_LIMIT,
    NO_CONSISTENT_ASSIGNMENT,
    KNOWN_BIT_CONSTRAINT_CONFLICT,
    BIT_SPACE_LIMIT,
    CORRECTION_FAILED,
}
