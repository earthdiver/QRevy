package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Explain recorded work, not correctness, uniqueness, or work still running in the background. */
fun RecoverySearchDiagnostic.terminationText(fallbackStops: Set<CombinationSearchStop> = emptySet()): String {
    val stops = combinationStops + fallbackStops
    val interruptions = incompleteReasons + CombinationSearchSummary(stops = stops).incompleteReasons
    val pending = formatOutcomes.filter { it.state == RecoveryFormatState.UNRESOLVED }
    val lines = linkedSetOf<String>()
    interruptions.forEach {
        lines += if (it == SearchIncompleteReason.INTERNAL_ERROR && coverageComplete && completedSteps == 0 && !acceptedCandidate)
            localizedText("内部エラーにより、修復処理を実行できませんでした。", "An internal error prevented repair from running.")
        else it.terminationReasonText()
    }
    val policyStops = stops.filter { it in policyBoundaries }
    policyStops.forEach { lines += it.boundaryText() }
    val reasons = pending.map { it.reason }.distinct()
    reasons.forEach { reason ->
        // A broad legacy limit record cannot identify a more specific boundary.
        if (reason == "SEARCH_LIMIT" && policyStops.isNotEmpty()) return@forEach
        val stop = policyBoundaries.firstOrNull { it.name == reason }
        if (stop != null) lines += stop.boundaryText()
        else if (reason !in explainedInterruptions(interruptions)) lines += unresolvedReasonText(reason)
    }
    if (completeness == SearchCompleteness.UNKNOWN) lines += SearchCompleteness.UNKNOWN.description() + "\n" + localizedText(
        "探索の実行記録が不足しているため、どの範囲まで確認したかを判断できません。",
        "The execution record is insufficient to determine how much of the search was checked.")
    if (unresolvedCalculations && pending.isEmpty()) lines += localizedText(
        "候補の有無を判定できなかった計算があります。対象の形式・計算と理由は記録されていません。",
        "Some calculations could not determine whether a candidate exists; their format, calculation, and reason were not recorded.")
    if (completeness == SearchCompleteness.UNSEARCHED && interruptions.isEmpty()) lines += localizedText(
        "確認を終えていない探索がありますが、対象の処理と終了理由は記録されていません。",
        "Some searches were not fully checked; the affected work and stop reason were not recorded.")
    if (lines.isEmpty()) lines += SearchCompleteness.COMPLETE.description()
    if (RecoveryCandidateRejection.UTF8_BYTE_SEGMENT in candidateRejections) lines += localizedText(
        "UTF-8 として成立しないバイト区間を含む組合せを除外しました。",
        "Pruned combinations containing Byte segments incompatible with UTF-8.")
    if (knownBitSearchAttempted) lines += localizedText("既知bit誤り探索: $knownBitTrials 試行（設定範囲内の探索）",
        "Known-bit error search: $knownBitTrials trials (within the configured scope)")
    return lines.joinToString("\n")
}

/** Group formats with the same recorded reason, without attributing aggregate evidence to one trial. */
fun RecoverySearchDiagnostic.formatTerminationDetails(): String = formatOutcomes
    .filter { it.state == RecoveryFormatState.UNRESOLVED }
    .groupBy { it.reason }
    .map { (reason, outcomes) ->
        val formats = outcomes.distinctBy { it.ecLevel to it.dataMask }.joinToString(", ") {
            "${it.ecLevel}/${it.dataMask}"
        }
        localizedText("対象のECC/マスク: $formats", "Affected ECC/mask: $formats") + "\n" +
            (policyBoundaries.firstOrNull { it.name == reason }?.boundaryText() ?: unresolvedReasonText(reason))
    }.joinToString("\n\n")

private val policyBoundaries = setOf(CombinationSearchStop.SUCCESS_WINDOW,
    CombinationSearchStop.COMBINATION_LIMIT, CombinationSearchStop.NODE_LIMIT)

private fun CombinationSearchStop.boundaryText(): String = when (this) {
    CombinationSearchStop.SUCCESS_WINDOW -> localizedText(
        "候補発見後の追加探索は、仕様で定めた範囲で終了しました。その範囲より先の組合せは調べていません。",
        "Additional search after finding a candidate ended at the boundary defined by the search policy. Combinations beyond that boundary were not checked.")
    CombinationSearchStop.COMBINATION_LIMIT -> localizedText(
        "候補の組合せの確認は、仕様で定めた件数までで終了しました。上限より先の組合せは調べていません。",
        "Candidate combination checks ended at the count defined by the search policy. Combinations beyond that limit were not checked.")
    CombinationSearchStop.NODE_LIMIT -> localizedText(
        "候補を絞り込む計算は、仕様で定めた計算量までで終了しました。残りの分岐は調べていません。",
        "Candidate filtering ended at the amount of work defined by the search policy. Remaining branches were not checked.")
    else -> error("Not a policy boundary")
}

private fun SearchIncompleteReason.terminationReasonText(): String = when (this) {
    SearchIncompleteReason.USER_INTERRUPTED -> localizedText(
        "利用者が探索を中断しました。中断時点で残っていた候補の確認は行っていません。",
        "Search was interrupted by the user. Candidate checks remaining at interruption were not performed.")
    SearchIncompleteReason.TIME_LIMIT -> localizedText(
        "試行の時間上限に達しました。その条件で残っていた候補の確認は行っていません。",
        "A trial reached its time limit. Remaining candidate checks under those conditions were not performed.")
    SearchIncompleteReason.LIST_DECODING_TIME_LIMIT -> localizedText(
        "リスト復号による追加探索が時間切れになりました。その追加探索で残っていた組合せは調べていません。",
        "List-decoding fallback reached its time limit. Remaining combinations in that fallback were not checked.")
    SearchIncompleteReason.KNOWN_BIT_SEARCH_TIME_LIMIT -> localizedText(
        "既知bit誤り探索が時間切れになりました。既知セルの白黒を反転する残りの組合せは調べていません。",
        "Known-bit error search reached its time limit. Remaining combinations of flips to known cells were not checked.")
    SearchIncompleteReason.KNOWN_BIT_SEARCH_LIMIT -> localizedText(
        "既知bit誤り探索は、試行数の上限までで終了しました。既知セルの白黒を反転する残りの組合せは調べていません。",
        "Known-bit error search ended at its trial-count limit. Remaining combinations of flips to known cells were not checked.")
    SearchIncompleteReason.WORKING_MEMORY_LIMIT -> localizedText(
        "計算用メモリの上限に達したため、候補の確認を途中で終了しました。",
        "Candidate checks ended early because the working-memory limit was reached.")
    SearchIncompleteReason.INTERNAL_ERROR -> localizedText(
        "内部エラーにより探索を完了できませんでした。候補の確認ができなかった処理があります。",
        "An internal error prevented search completion. Some candidate checks could not be performed.")
}

private fun explainedInterruptions(reasons: Set<SearchIncompleteReason>): Set<String> = buildSet {
    if (SearchIncompleteReason.USER_INTERRUPTED in reasons || SearchIncompleteReason.TIME_LIMIT in reasons)
        add("INTERRUPTED")
    addAll(reasons.map { it.name })
}

private fun unresolvedReasonText(reason: String): String = when (reason) {
    "BIT_SPACE_LIMIT", "BOUNDED_BIT_SEARCH_LIMIT" -> localizedText(
        "不明ビットの組合せが、この計算で列挙する範囲を超えました。範囲外の組合せに候補があるかは判断していません。",
        "Unknown-bit combinations exceeded this calculation's enumeration range. Candidate existence outside that range was not determined.")
    "KNOWN_BIT_CONSTRAINT_CONFLICT" -> localizedText(
        "読み取れた白黒をすべて正しいとする計算では、誤り訂正の条件を満たせませんでした。白黒の誤りを許す場合の候補の有無は、この計算では判断できません。",
        "This calculation could not satisfy error-correction constraints while preserving every observed bit. It does not determine whether candidates exist when bit errors are allowed.")
    "SINGLE_SEGMENT_HEADER_UNRESOLVED" -> localizedText(
        "データ先頭の種類・文字数を確定できず、この形式では単一セグメント仮定を適用した候補の確認ができませんでした。",
        "The data header's mode/count could not be determined, so candidates under the single-segment assumption could not be checked for this format.")
    "PRIORITY_ASSIGNMENT_UNRESOLVED" -> localizedText(
        "*で指定したセルの白黒の組合せのうち、候補の有無を判定できなかったものがあります。",
        "Candidate existence could not be determined for some black/white assignments to cells marked *.")
    "SEARCH_LIMIT" -> localizedText(
        "候補の組合せの確認は探索上限で終了しました。上限の種類は記録されていません。",
        "Candidate combination checks ended at a search limit; the type of limit was not recorded.")
    "ERASURE_CAPACITY_EXCEEDED", "TOO_MANY_ERASURES" -> localizedText(
        "この計算では、不明なコード語が誤り訂正で扱える数を超え、候補を求められませんでした。",
        "This calculation could not obtain a candidate because unknown codewords exceeded its error-correction capacity.")
    "ERROR_ERASURE_CAPACITY_EXCEEDED" -> localizedText(
        "この計算では、誤りと不明なコード語の合計が誤り訂正の容量を超えました。",
        "Errors and unknown codewords exceeded the error-correction capacity of this calculation.")
    "NO_CONSISTENT_ASSIGNMENT" -> localizedText(
        "試した不明ビットの白黒の組合せでは、誤り訂正の条件を満たす候補が得られませんでした。",
        "The tried black/white assignments to unknown bits produced no candidate satisfying the error-correction constraints.")
    "CORRECTION_FAILED", "DECODER_NO_RESULT" -> localizedText(
        "この形式の誤り訂正では候補を求められませんでした。別の計算で候補が得られるかは、この記録からは判断できません。",
        "Error correction for this format did not obtain a candidate. This record does not establish whether another calculation could do so.")
    "SEARCH_INCOMPLETE" -> localizedText(
        "この形式の組合せ探索は全範囲の確認に至りませんでした。到達した範囲の詳細は記録されていません。",
        "Combination search for this format did not check its entire range. The extent reached was not recorded.")
    "INTERNAL_ERROR" -> SearchIncompleteReason.INTERNAL_ERROR.terminationReasonText()
    "WORKING_MEMORY_LIMIT" -> SearchIncompleteReason.WORKING_MEMORY_LIMIT.terminationReasonText()
    "TIME_LIMIT" -> SearchIncompleteReason.TIME_LIMIT.terminationReasonText()
    "USER_INTERRUPTED", "INTERRUPTED" -> localizedText(
        "この形式の探索は途中で終了しました。候補の確認を終えていません。",
        "Search for this format ended early; candidate checks were not completed.")
    "NOT_ATTEMPTED" -> localizedText(
        "この形式の探索を実行した記録がありません。候補の有無は判断できません。",
        "No search execution was recorded for this format; candidate existence cannot be determined.")
    else -> localizedText(
        "この形式の計算では候補の有無を判定できませんでした。詳しい理由は記録されていません。",
        "This format's calculation did not determine whether a candidate exists. A detailed reason was not recorded.")
}
