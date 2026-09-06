package io.github.earthdiver.qrrepair.core

/**
 * Small stable entry point intended for platform smoke tests while the full recovery pipeline is
 * incrementally moved into commonMain. Keep this API dependency-free and Swift-friendly.
 */
class QRRepairCoreApi {
    fun version(): String = "0.7.3"

    fun isValidQrDimension(dimension: Int): Boolean =
        dimension in 21..177 && (dimension - 17) % 4 == 0

    /** Deterministic self-test useful when first linking the XCFramework from Swift. */
    fun reedSolomonSelfTest(): Boolean {
        val rs = ReedSolomonErasures()
        val data = IntArray(12) { (it * 29 + 7) and 0xFF }
        val encoded = rs.encode(data, 10)
        val damaged = encoded.copyOf()
        val erasures = intArrayOf(2, 8, 15)
        damaged[2] = damaged[2] xor 0x31
        damaged[8] = damaged[8] xor 0x52
        damaged[15] = damaged[15] xor 0x73
        damaged[19] = damaged[19] xor 0x44
        return try {
            rs.decode(damaged, 10, erasures).corrected.contentEquals(encoded)
        } catch (_: ReedSolomonErasures.DecodeException) {
            false
        }
    }
}
