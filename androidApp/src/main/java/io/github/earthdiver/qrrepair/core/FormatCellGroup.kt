package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Positions follow the 15-bit format word from most to least significant bit. */
enum class FormatCellRole(val indices: IntRange) {
    ECC(0..1), MASK(2..4), PARITY(5..14);

    fun label(): String = when (this) {
        ECC -> localizedText("ECCレベル", "ECC level")
        MASK -> localizedText("マスク番号", "Mask number")
        PARITY -> localizedText("形式情報の誤り訂正", "Format error correction")
    }

    fun buttonLabel(): String = when (this) {
        ECC -> localizedText("ECCレベルのセルを表示", "Show ECC level cells")
        MASK -> localizedText("マスク番号のセルを表示", "Show mask number cells")
        PARITY -> localizedText("形式情報の訂正ビットを表示", "Show format correction bits")
    }
}

data class FormatCellGroup(val role: FormatCellRole, val copyIndex: Int, val bitIndex: Int) {
    fun coordinates(dimension: Int): List<Coord> = FormatSolver.copies(dimension).flatMap { copy ->
        role.indices.map { i -> Coord(copy[i].first, copy[i].second) }
    }

    fun cellText(): String {
        val position = bitIndex - role.indices.first + 1
        val count = role.indices.count()
        val significance = if (position == 1) localizedText("（最上位ビット）", " (most significant bit)") else ""
        return localizedText(
            "領域：形式情報\n役割：${role.label()}の${count}ビット中、左から${position}番目$significance\n配置：コピー${copyIndex + 1}",
            "Region: format information\nRole: ${role.label()}, bit $position of $count from the left$significance\nLocation: copy ${copyIndex + 1}",
        )
    }

    companion object {
        fun at(dimension: Int, x: Int, y: Int): FormatCellGroup? {
            FormatSolver.copies(dimension).forEachIndexed { copy, coords ->
                val index = coords.indexOf(x to y)
                if (index >= 0) return FormatCellGroup(FormatCellRole.entries.first { index in it.indices }, copy, index)
            }
            return null
        }
    }
}

/** Read both observed copies independently; never fill unknown input bits from the candidate. */
fun formatGroupDetailText(group: FormatCellGroup, before: QrMatrix, after: QrMatrix): String = buildString {
    require(before.dimension == after.dimension)
    append(localizedText("関連セル：${group.role.label()}（2か所）", "Related cells: ${group.role.label()} (two copies)"))
    append(localizedText("\n各行は修復前 → 修復後。[ ]は選択ビットと他方のコピーの対応ビットです。",
        "\nEach line shows before → after. [ ] marks the selected bit and its counterpart in the other copy."))
    append(localizedText("\n白黒のビット値から形式情報の固定マスク（0x5412）を解除して解釈します。データ領域の「マスク解除」とは別です。",
        "\nCell bits are interpreted after removing the fixed format mask (0x5412). This is separate from Unmask for the data region."))
    FormatSolver.copies(before.dimension).forEachIndexed { copyIndex, coords ->
        fun bits(matrix: QrMatrix, removeMask: Boolean): List<Int?> = group.role.indices.map { index ->
            val (x, y) = coords[index]
            matrix[x, y].value.bitOrNull()?.let { bit ->
                bit xor if (removeMask) ((0x5412 shr (14 - index)) and 1) else 0
            }
        }
        fun display(bits: List<Int?>): String = bits.mapIndexed { index, bit ->
            val value = bit?.toString() ?: "?"
            if (index + group.role.indices.first == group.bitIndex) "[$value]" else value
        }.joinToString("")
        fun meaning(bits: List<Int?>): String {
            if (bits.any { it == null }) return localizedText("未確定", "unknown")
            val value = bits.fold(0) { acc, bit -> (acc shl 1) or bit!! }
            return when (group.role) {
                FormatCellRole.ECC -> listOf("M", "L", "H", "Q")[value]
                FormatCellRole.MASK -> value.toString()
                FormatCellRole.PARITY -> error("Parity has no standalone format meaning")
            }
        }
        val selected = if (copyIndex == group.copyIndex) localizedText("（選択中）", " (selected)") else ""
        append(localizedText("\n\nコピー${copyIndex + 1}$selected", "\n\nCopy ${copyIndex + 1}$selected"))
        append(localizedText("\nセルの白黒（黒=1、白=0）：", "\nCell bits (black=1, white=0): "))
        append(display(bits(before, false))).append(" → ").append(display(bits(after, false)))
        val unmaskedBefore = bits(before, true)
        val unmaskedAfter = bits(after, true)
        append(localizedText("\n形式情報の固定マスク解除後：", "\nAfter removing the fixed format mask: "))
        append(display(unmaskedBefore)).append(" → ").append(display(unmaskedAfter))
        if (group.role != FormatCellRole.PARITY) {
            append(localizedText(
                "\n${group.role.label()}：${meaning(unmaskedBefore)} → ${meaning(unmaskedAfter)}",
                "\n${group.role.label()}: ${meaning(unmaskedBefore)} → ${meaning(unmaskedAfter)}"))
        }
    }
    if (group.role == FormatCellRole.PARITY) append(localizedText(
        "\nこの10ビットはECCレベル2ビットとマスク番号3ビットから生成するBCH訂正用ビットです。RSコード語ではありません。",
        "\nThese 10 BCH correction bits are generated from the 2 ECC-level bits and 3 mask-number bits. They are not an RS codeword."))
}
