package io.github.earthdiver.qrrepair.core

import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Independently generated V1-L streams with exact RS parity, including intentionally invalid payloads. */
internal class PayloadBits {
    private val bits = mutableListOf<Int>()
    fun put(value: Int, width: Int) { for (bit in width - 1 downTo 0) bits += (value ushr bit) and 1 }
    fun finish(byteCount: Int = 19): ByteArray {
        require(bits.size <= byteCount * 8)
        repeat(minOf(4, byteCount * 8 - bits.size)) { bits += 0 }
        while (bits.size % 8 != 0) bits += 0
        var pad = 0
        while (bits.size < byteCount * 8) put(if ((pad++ and 1) == 0) 0xEC else 0x11, 8)
        return ByteArray(byteCount) { i -> (0..7).fold(0) { v, b -> (v shl 1) or bits[i * 8 + b] }.toByte() }
    }
}

internal fun recoverPayload(data: ByteArray): List<RecoveryCandidate> {
    require(data.size == 19)
    val raw = IntArray(26) { if (it < data.size) data[it].toInt() and 255 else 0 }
    ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256).encode(raw, 7)
    val matrix = QrMatrix(21)
    StructuralRepair.apply(matrix, ErrorCorrectionLevel.L, 0)
    CodewordExtractor.extract(matrix, 1, 0).codewords.forEachIndexed { i, cw ->
        cw.coordinates.forEachIndexed { bit, c ->
            val value = ((raw[i] ushr (7 - bit)) and 1) xor
                if (CodewordExtractor.maskApplies(0, c.x, c.y)) 1 else 0
            matrix.set(c.x, c.y, if (value == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
    }
    return RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0),
        maxKnownDataEccMismatches = 0)
}
