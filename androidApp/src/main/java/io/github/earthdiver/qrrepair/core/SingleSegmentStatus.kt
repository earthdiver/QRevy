package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Outcomes of forcing evaluation, recorded before RS and retained even if no candidate survives. */
enum class SingleSegmentStatus {
    APPLIED, APPLIED_WITH_PRIORITY_HEADER, INCOMPLETE_HEADER, UNSUPPORTED_MODE, COUNT_EXCEEDS_CAPACITY, PLAN_LIMIT;

    fun description(): String = when (this) {
        APPLIED -> localizedText("種類・文字数／バイト数を使い、単一セグメントを仮定した構造を確認・適用しました。",
            "Checked/applied the single-segment structure using the mode and character/byte count.")
        APPLIED_WITH_PRIORITY_HEADER -> localizedText("* の試行で種類・文字数／バイト数を仮定し、単一セグメントの構造を確認・適用しました。画像から読み取れた値ではありません。",
            "Checked/applied the single-segment structure with a mode/count header assigned by * trials, not observed from the image.")
        INCOMPLETE_HEADER -> localizedText("種類・文字数／バイト数に未確定のbitがあるため、単一セグメントの仮定による構造補完を適用できませんでした。",
            "Single-segment forcing could not be applied because the mode/count header contained unresolved bits.")
        UNSUPPORTED_MODE -> localizedText("先頭の種類が数字・英数字・バイト・漢字のいずれでもないため、単一セグメントの仮定による構造補完を適用できませんでした。",
            "Single-segment forcing could not be applied because the leading mode was not Numeric, Alphanumeric, Byte or Kanji.")
        COUNT_EXCEEDS_CAPACITY -> localizedText("文字数／バイト数がQRコードのデータ容量を超えるため、単一セグメントの仮定による構造補完を適用できませんでした。",
            "Single-segment forcing could not be applied because the character/byte count exceeded the QR data capacity.")
        PLAN_LIMIT -> localizedText("構造の候補を調べる上限に達したため、単一セグメントの仮定による構造補完を適用できませんでした。",
            "Single-segment forcing could not be applied because the structure-plan search limit was reached.")
    }

    /** Aggregate search evidence, rather than a statement about the selected candidate. */
    fun trialDescription(): String = when (this) {
        APPLIED -> localizedText("種類・文字数／バイト数に基づき、単一セグメントの構造補完を確認・適用した試行がありました。",
            "Some trials checked/applied the single-segment structure using the mode and character/byte count.")
        APPLIED_WITH_PRIORITY_HEADER -> localizedText("* により種類・文字数／バイト数を仮定し、単一セグメントの構造補完を確認・適用した試行がありました。仮定した値は画像から読み取れた値ではありません。",
            "Some trials checked/applied the single-segment structure with a mode/count header assigned by *. The hypothesized values were not observed from the image.")
        INCOMPLETE_HEADER -> localizedText("種類・文字数／バイト数に未確定のbitが残り、構造補完を適用できなかった試行がありました。",
            "Some trials could not apply structure forcing because the mode/count header contained unresolved bits.")
        UNSUPPORTED_MODE -> localizedText("先頭ビットを数字・英数字・バイト・漢字のいずれとしても解釈できず、構造補完を適用できなかった試行がありました。",
            "Some trials could not apply structure forcing because the leading bits were not interpreted as Numeric, Alphanumeric, Byte or Kanji.")
        COUNT_EXCEEDS_CAPACITY -> localizedText("解釈した文字数／バイト数が、その候補のデータ容量を超え、構造補完を適用できなかった試行がありました。",
            "Some trials could not apply structure forcing because the interpreted character/byte count exceeded that candidate's data capacity.")
        PLAN_LIMIT -> localizedText("構造の候補を調べる上限に達し、構造補完を適用できなかった試行がありました。",
            "Some trials could not apply structure forcing because the structure-plan search limit was reached.")
    }
}

/** Only explicit priority bits may stand in for an unresolved header during eligibility checks. */
internal fun possibleSingleSegmentHeader(data: IntArray, unknown: IntArray, priority: IntArray, version: Int): Boolean {
    require(data.size == unknown.size && data.size == priority.size)
    if (data.isEmpty()) return false
    val modeUnknown = (unknown[0] ushr 4) and 15
    if ((modeUnknown and (priority[0] ushr 4).inv()) != 0) return false
    return listOf(1, 2, 4, 8).any { mode ->
        if ((((data[0] ushr 4) xor mode) and modeUnknown.inv() and 15) != 0) false
        else {
            val trial = data.copyOf().apply { this[0] = (this[0] and 15) or (mode shl 4) }
            val remainingUnknown = IntArray(unknown.size) { unknown[it] and priority[it].inv() }
            trustablePayloadHeaderAvailable(trial, remainingUnknown, version)
        }
    }
}

internal fun singleSegmentStatus(data: IntArray, unknown: IntArray, version: Int,
    result: PayloadConstraintSolver.Result, priorityHeader: Boolean): SingleSegmentStatus = when {
    result.forcedByTrustedHeader -> if (priorityHeader) SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER else SingleSegmentStatus.APPLIED
    data.isEmpty() || (unknown[0] and 0xF0) != 0 -> SingleSegmentStatus.INCOMPLETE_HEADER
    ((data[0] ushr 4) and 15) !in setOf(1, 2, 4, 8) -> SingleSegmentStatus.UNSUPPORTED_MODE
    !trustablePayloadHeaderAvailable(data, unknown, version) -> SingleSegmentStatus.INCOMPLETE_HEADER
    !result.exhaustive -> SingleSegmentStatus.PLAN_LIMIT
    else -> SingleSegmentStatus.COUNT_EXCEEDS_CAPACITY
}

/** Header values assigned by priority trials, excluding body/ECC-only guesses. */
internal fun priorityHeaderHypothesis(data: IntArray, unknown: IntArray, originalUnknown: IntArray, version: Int): String? {
    if (data.isEmpty()) return null
    val mode = if ((unknown[0] and 0xF0) == 0) (data[0] ushr 4) and 15 else null
    val group = when (version) { in 1..9 -> 0; in 10..26 -> 1; else -> 2 }
    val countWidth = when (mode) {
        1 -> intArrayOf(10, 12, 14)[group]
        2 -> intArrayOf(9, 11, 13)[group]
        4 -> intArrayOf(8, 16, 16)[group]
        8 -> intArrayOf(8, 10, 12)[group]
        else -> 0
    }
    val width = minOf(4 + countWidth, data.size * 8)
    if ((0 until width).none { bit ->
        val mask = 1 shl (7 - bit % 8)
        (originalUnknown[bit / 8] and mask) != 0 && (unknown[bit / 8] and mask) == 0
    }) return null
    return buildString {
        for (bit in 0 until width) {
            val mask = 1 shl (7 - bit % 8)
            append(if ((unknown[bit / 8] and mask) != 0) '?' else if ((data[bit / 8] and mask) != 0) '1' else '0')
        }
    }
}

fun RecoverySearchDiagnostic?.singleSegmentProcessingText(): String = buildString {
    append(localizedText("単一セグメントの仮定はONです。以下は、探索中の各試行（ECC・マスク候補や * による仮定）の判定を集約したものです。元のQRコードの種類を確定する診断ではありません。",
        "The single-segment assumption was ON. The following summarizes trial outcomes across ECC/mask candidates and * hypotheses. It does not establish the original QR code's mode."))
    val diagnostic = this@singleSegmentProcessingText
    val statuses = diagnostic?.singleSegmentStatuses.orEmpty()
    val applied = statuses.any { it == SingleSegmentStatus.APPLIED || it == SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER }
    val unavailable = statuses.any { it != SingleSegmentStatus.APPLIED && it != SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER }
    if (applied && unavailable) {
        append('\n').append(localizedText("構造補完を適用できた試行と、適用できなかった試行がありました。",
            "Structure forcing was applicable in some trials and unavailable in others."))
    }
    val description = when {
        diagnostic != null && diagnostic.priorityHeaderHypotheses.size > 1 -> {
            if (SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER in statuses) localizedText(
                "* の試行で種類・文字数／バイト数を仮定し、適用可能な試行で単一セグメントの構造を確認・適用しました。仮定した値は画像から読み取れた値ではありません。",
                "Hypothesized the mode and character/byte count in * trials and checked/applied the single-segment structure in applicable trials. The hypothesized values were not observed from the image.")
            else localizedText(
                "* の試行で種類・文字数／バイト数を仮定しましたが、調べた範囲では単一セグメントの構造補完を適用できる組合せは見つかりませんでした。",
                "Hypothesized the mode and character/byte count in * trials, but no combination allowing single-segment forcing was found in the checked range.")
        }
        SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER in statuses -> SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER.trialDescription()
        SingleSegmentStatus.INCOMPLETE_HEADER in statuses && !applied -> SingleSegmentStatus.INCOMPLETE_HEADER.trialDescription()
        statuses.isNotEmpty() -> statuses.sortedBy { it.ordinal }.joinToString("\n") { it.trialDescription() }
        diagnostic?.coverageComplete == true -> localizedText(
            "構造補完の適用判定には到達していません。", "Structure-forcing evaluation was not reached.")
        else -> localizedText("構造補完の適用状況の記録がありません。", "No structure-forcing evaluation was recorded.")
    }
    append('\n').append(if (statuses.isEmpty()) description else
        description.lineSequence().joinToString("\n") { "• $it" })
}
