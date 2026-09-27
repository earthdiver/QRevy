package io.github.earthdiver.qrrepair.core

/** Shared per-block limit for partial-bit trials and exact RS-space enumeration. */
object PartialBitSearchLimit {
    const val DEFAULT = 12
    val supported = 12..14
}
