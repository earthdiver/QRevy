package io.github.earthdiver.qrrepair.core

/** Bit lengths for QR data modes; callers decide which modes their operation supports. */
object QrPayloadLayout {
    /** Excludes the mode indicator and the Hanzi subset indicator. Control modes have no count. */
    fun characterCountBits(mode: Int, version: Int): Int? {
        val group = when (version) { in 1..9 -> 0; in 10..26 -> 1; else -> 2 }
        return when (mode) {
            1 -> when (group) { 0 -> 10; 1 -> 12; else -> 14 }
            2 -> when (group) { 0 -> 9; 1 -> 11; else -> 13 }
            4 -> if (group == 0) 8 else 16
            8, 13 -> when (group) { 0 -> 8; 1 -> 10; else -> 12 }
            else -> null
        }
    }

    /** Encoded body length only, excluding headers, terminator, alignment and padding. */
    fun payloadBits(mode: Int, count: Int): Int? = when (mode) {
        1 -> count / 3 * 10 + when (count % 3) { 1 -> 4; 2 -> 7; else -> 0 }
        2 -> count / 2 * 11 + if ((count and 1) != 0) 6 else 0
        4 -> count * 8
        8, 13 -> count * 13
        else -> null
    }
}
