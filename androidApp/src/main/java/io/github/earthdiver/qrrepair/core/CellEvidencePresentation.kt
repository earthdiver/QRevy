package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** The result image and its cell evidence must use the same optional unmasking. */
internal fun displayedModuleValue(value: ModuleValue, version: Int, x: Int, y: Int, maskToRemove: Int?): ModuleValue {
    if (maskToRemove == null || QrFunctionMask.isFunction(version, x, y) ||
        !CodewordExtractor.maskApplies(maskToRemove, x, y)) return value
    return when (value) {
        ModuleValue.ZERO -> ModuleValue.ONE
        ModuleValue.ONE -> ModuleValue.ZERO
        ModuleValue.ERASED -> ModuleValue.ERASED
    }
}

/** bitIndex follows extraction order: zero is the leftmost (most significant) bit. */
fun codewordCellText(index: Int, bitIndex: Int, value: Int, unknown: Int, corrected: Int?,
    block: Int?, local: Int?, targeted: Boolean): String = buildString {
    require(bitIndex in 0..7)
    append(localizedText("コード語${index + 1}", "Codeword ${index + 1}"))
    block?.let { append(localizedText("／RSブロック${it + 1}", " / RS block ${it + 1}")) }
    local?.let { append(localizedText("／ブロック内${it + 1}番目", " / position ${it + 1} in block")) }
    fun bits(v: Int, mask: Int) = (0..7).joinToString("") { i ->
        val b = 1 shl (7 - i)
        val c = if (mask and b != 0) "?" else if (v and b != 0) "1" else "0"
        if (i == bitIndex) "[$c]" else c
    }
    append(localizedText("\nマスク解除後：", "\nAfter removing the QR mask: "))
    append(bits(value, unknown)).append(" → ").append(bits(corrected ?: 0, if (corrected == null) 255 else 0))
    append(localizedText("\n選択中：左から${bitIndex + 1}番目のbit（[ ]で表示）", "\nSelected: bit ${bitIndex + 1} from the left (in [ ])"))
    if (targeted) append(localizedText("\nこのコード語はRSの訂正対象です。セルごとの変更は上の前後値で確認できます。",
        "\nThis codeword was targeted by RS. See the cell's before/after values above for its individual change."))
}
