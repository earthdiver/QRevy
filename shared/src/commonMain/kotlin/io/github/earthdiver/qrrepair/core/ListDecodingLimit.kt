package io.github.earthdiver.qrrepair.core

/** Maximum extra erroneous symbols per block beyond conventional errors+erasures RS. */
object ListDecodingLimit {
    const val DEFAULT = 1
    val supported: IntRange = 1..3
}
