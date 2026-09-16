package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Selection settings captured for the completed recovery, not the current UI settings. */
data class FormatSelection(val eccSpecified: Boolean, val maskSpecified: Boolean)

enum class FormatSelectionReason { USER, OBSERVED, SEARCH }

/** Determine the origin of this bit, including parity bits fixed by a partial selection. */
fun RecoveryCandidate.formatReasonAt(x: Int, y: Int, userSpecified: Boolean = false): FormatSelectionReason? {
    if (userSpecified) return FormatSelectionReason.USER
    val selection = formatSelection ?: return null
    if (selection.eccSpecified || selection.maskSpecified) {
        val constraint = QrFormatConstraint(ecLevel.takeIf { selection.eccSpecified }, dataMask.takeIf { selection.maskSpecified })
        if (Coord(x, y) in FormatEditPolicy.fixedValues(version, constraint)) return FormatSelectionReason.USER
    }
    return formatRoute
}

fun formatCellDescription(x: Int, y: Int, before: ModuleValue, after: ModuleValue,
    candidate: RecoveryCandidate?, userSpecified: Boolean, includeBasic: Boolean = true): String = buildString {
    fun value(v: ModuleValue) = when (v) {
        ModuleValue.ERASED -> localizedText("不明", "unknown")
        ModuleValue.ZERO -> localizedText("白", "white")
        ModuleValue.ONE -> localizedText("黒", "black")
    }
    if (includeBasic) append(localizedText("セル ($x,$y)：${value(before)} → ${value(after)}\n領域：形式情報",
        "Cell ($x,$y): ${value(before)} → ${value(after)}\nRegion: format information"))
    if (!includeBasic) append(localizedText("領域：形式情報", "Region: format information"))
    if (candidate != null) append(localizedText(
        "\n選択中の候補の形式：ECCレベル ${candidate.ecLevel} / マスク ${candidate.dataMask}",
        "\nSelected candidate format: ECC ${candidate.ecLevel} / mask ${candidate.dataMask}"))
    candidate?.let { c ->
        val reason = c.formatReasonAt(x, y, userSpecified)
        append(localizedText("\nこのビットの形式選択の根拠：", "\nFormat selection basis for this bit: "))
        append(when (reason) {
            FormatSelectionReason.USER -> if (userSpecified) localizedText("ユーザー指定 — このセルの手動指定値を使用しました。", "User-specified — used the manually specified cell value.")
                else {
                    val specified = when {
                        c.formatSelection?.eccSpecified == true && c.formatSelection.maskSpecified -> "ECC・マスク"
                        c.formatSelection?.eccSpecified == true -> "ECC"
                        else -> localizedText("マスク", "mask")
                    }
                    localizedText("ユーザー指定 — 指定された${specified}を使用しました。",
                        "User-specified — used the specified ${specified.replace("・", "/")}.")
                }
            FormatSelectionReason.OBSERVED -> localizedText("読み取った形式情報から訂正・判定 — 読み取り済みの形式情報に基づいて選びました。",
                "Decoded/corrected observed format information — selected based on the observed format information.")
            FormatSelectionReason.SEARCH -> localizedText("複数の形式を探索 — このECC・マスクで復元候補が得られました。",
                "Format search — this ECC/mask produced a recovery candidate.")
            null -> localizedText("記録なし", "not recorded")
        })
        if (reason == FormatSelectionReason.OBSERVED || reason == FormatSelectionReason.SEARCH) {
            val compared = c.formatMatches + c.formatMismatches
            append(if (compared == 0) localizedText("\n既知の形式情報：0bit（一致を確認できません）",
                "\nKnown format information: 0 bits (agreement cannot be checked)")
            else localizedText("\n既知の形式情報${compared}bitとの比較：一致${c.formatMatches}bit／不一致${c.formatMismatches}bit",
                "\nComparison with $compared known format bits: ${c.formatMatches} match / ${c.formatMismatches} mismatch"))
        }
    }
    append(localizedText("\n理由：", "\nReason: "))
    when {
        userSpecified -> append(localizedText("このセルはユーザー指定の値です。", "This cell has a user-specified value."))
        candidate == null -> append(localizedText("採用したECCレベル・マスクは未確定です。", "The selected ECC level and mask are not available."))
        after == ModuleValue.ERASED -> append(localizedText("このセルの値は未確定です。", "This cell remains unknown."))
        before == after -> append(localizedText("読み取り済みの値は、選択中の候補のECCレベル・マスクに対応する形式情報と一致しています。",
            "The observed value agrees with the format information for the selected candidate's ECC level and mask."))
        else -> append(localizedText(
            "選択中の候補のECCレベル・マスクに対応する形式情報では、このセルが${value(after)}になるため${if (before == ModuleValue.ERASED) "補完" else "変更"}しました。",
            "The format information for the selected candidate's ECC level and mask requires this cell to be ${value(after)}, so it was ${if (before == ModuleValue.ERASED) "filled" else "changed"}."))
    }
    append(localizedText("\n※形式の正しさを、このセル単独で確認したわけではありません。",
        "\nThis cell alone does not establish that the selected format is correct."))
}
