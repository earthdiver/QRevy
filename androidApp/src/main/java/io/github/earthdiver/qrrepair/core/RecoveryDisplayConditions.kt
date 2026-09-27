package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Conditions of the completed run, independent of later UI setting changes. */
data class RecoveryDisplayConditions(
    val trustSingleSegment: Boolean,
    val lockManualBits: Boolean?,
    val format: QrFormatConstraint?,
    val formatLimit: Int?,
    val dataEccLimit: Int?,
)

fun inputAgreementText(known: Int, mismatches: Int, format: Boolean): String {
    val regionJa = if (format) "形式情報領域" else "データ・ECC領域"
    val regionEn = if (format) "Format information" else "Data/ECC region"
    return if (known == 0) localizedText(
        "$regionJa: 比較対象の読み取り済み0bit（一致を確認できません）",
        "$regionEn: 0 observed bits available for comparison (agreement cannot be checked)",
    ) else localizedText(
        "$regionJa: 比較対象の読み取り済み${known}bitとの不一致 ${mismatches}bit",
        "$regionEn: $mismatches mismatching bits out of $known observed bits compared",
    )
}

fun RecoveryDisplayConditions.limitText(formatRegion: Boolean): String {
    val limit = if (formatRegion) formatLimit else dataEccLimit
    val nameJa = if (formatRegion) "形式情報" else "データ・ECC"
    val nameEn = if (formatRegion) "Format" else "Data/ECC"
    return localizedText("${nameJa}の不一致上限: ${limit?.let { "${it}bit" } ?: "なし"}",
        "$nameEn mismatch limit: ${limit?.let { "$it bits" } ?: "none"}")
}

fun RecoveryCandidate.conditionText(): String = buildString {
    val conditions = displayConditions
    conditions?.let {
        append(it.limitText(true)).append('\n').append(it.limitText(false))
        append(localizedText("\n形式の選択: ECC ", "\nFormat selection: ECC "))
        append(if (it.format?.ecLevel != null) localizedText("指定", "specified") else localizedText("自動", "automatic"))
        append(localizedText(" / マスク ", " / mask "))
        append(if (it.format?.dataMask != null) localizedText("指定", "specified") else localizedText("自動", "automatic"))
        it.lockManualBits?.let { locked ->
            append(localizedText("\n固定セルの0/1と矛盾する復元候補を許容する（仮指定セルと同じ扱い）: ", "\nAllow recovery candidates that conflict with fixed 0/1 values (as for tentative cells): ")).append(if (locked) "OFF" else "ON")
            if (!locked && it.dataEccLimit == 0) append(localizedText(
                "（データ・ECC領域の不一致上限が0bitのため、この領域の入力済み0/1は変更していません）",
                " (the zero data/ECC mismatch limit prevents changes to observed 0/1 values in that region)",
            ))
        }
    }
    manualDataEccCompared?.let { known ->
        if (known == 0) append(localizedText("\nデータ・ECC領域の手動0/1指定: なし", "\nManual 0/1 values in data/ECC: none"))
        else manualDataEccMismatches?.let { count ->
            append(localizedText("\nデータ・ECC領域: 手動指定${known}bitとの不一致 ${count}bit",
                "\nData/ECC region: $count mismatches against $known manually specified bits"))
            if (count > 0) append(localizedText("。手動指定したセルをご確認ください。", ". Review the manually entered cells."))
        }
    }
    if (constraintForcedByUserSetting) {
        append(localizedText("\n単一セグメントの仮定: この候補の補完に使用しました。",
            "\nSingle-segment assumption: used to fill this candidate."))
    } else if (conditions != null) {
        append(if (conditions.trustSingleSegment) localizedText(
            "\n単一セグメントの仮定: 設定はONですが、この候補の補完には使用していません。",
            "\nSingle-segment assumption: enabled, but not used to fill this candidate.",
        ) else localizedText("\n単一セグメントの仮定: 使用していません。",
            "\nSingle-segment assumption: not used."))
    }
}.trim()
