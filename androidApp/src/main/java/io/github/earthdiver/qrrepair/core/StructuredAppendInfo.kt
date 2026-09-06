package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Metadata for one repaired symbol; no assembly or whole-message parity validation is implied. */
data class StructuredAppendInfo(val index: Int, val total: Int, val parity: Int) {
    init {
        require(total in 2..16 && index in 0 until total)
        require(parity in 0..255)
    }

    fun description(): String = localizedText(
        "連結QRの一部: ${index + 1}/$total、パリティ=$parity。修復はこのQRのみです。全体の連結・パリティ照合は未実施です。",
        "Structured Append part ${index + 1}/$total, parity=$parity. Only this symbol is repaired; message assembly and parity verification have not been performed.",
    )
}
