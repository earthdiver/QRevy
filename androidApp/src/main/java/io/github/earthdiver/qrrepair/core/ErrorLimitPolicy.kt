package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version

/** Operational presets, not correctness guarantees or estimated error probabilities. */
object ErrorLimitPolicy {
    const val STRICT_FORMAT = 6
    const val RELAXED_FORMAT = 9

    fun strictData(version: Int): Int =
        (Version.getVersionForNumber(version).totalCodewords * 7 + 99) / 100

    fun relaxedData(version: Int): Int =
        (Version.getVersionForNumber(version).totalCodewords * 8 + 9) / 10

    /** Additional list-decoding candidates may change at most 5% of all data/ECC bits. */
    fun listDecodingData(version: Int): Int =
        (Version.getVersionForNumber(version).totalCodewords * 8 + 19) / 20

    fun format(mode: ErrorLimitMode, manual: Int?): Int? = when (mode) {
        ErrorLimitMode.AUTOMATIC, ErrorLimitMode.STRICT -> STRICT_FORMAT
        ErrorLimitMode.RELAXED -> RELAXED_FORMAT
        ErrorLimitMode.MANUAL -> manual
        ErrorLimitMode.EXACT -> 0
        ErrorLimitMode.UNLIMITED -> null
    }

    fun data(version: Int, mode: ErrorLimitMode, manual: Int?): Int? = when (mode) {
        ErrorLimitMode.AUTOMATIC -> if (version <= 4) strictData(version) else relaxedData(version)
        ErrorLimitMode.STRICT -> strictData(version)
        ErrorLimitMode.RELAXED -> relaxedData(version)
        ErrorLimitMode.MANUAL -> manual
        ErrorLimitMode.EXACT -> 0
        ErrorLimitMode.UNLIMITED -> null
    }
}
