package io.github.earthdiver.qrrepair.core

/** Maximum extra erroneous symbols per block beyond conventional RS; zero disables all list decoding. */
object ListDecodingLimit {
    const val DEFAULT = 1
    val supported: IntRange = 0..3
    val enabledExtensions: IntRange = 1..3
}
