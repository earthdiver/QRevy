package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Proven input restrictions, checked across every permitted format before making a run-wide claim. */
enum class RecoveryInputBlocker { FORMAT_SELECTION, FORMAT_LIMIT, STRUCTURAL_FIXED_CELLS, ZERO_MISMATCH }

enum class RecoveryCandidateRejection { FIXED_CELLS, MISMATCH_LIMIT, QR_STRUCTURE, SINGLE_SEGMENT_STRUCTURE, LIST_DECODING_MISMATCH_LIMIT }

enum class SearchIncompleteReason { USER_INTERRUPTED, WORKING_MEMORY_LIMIT, LIST_DECODING_TIME_LIMIT, INTERNAL_ERROR }

/** Evidence from the whole search, independent of the representative candidate or failure. */
data class RecoverySearchDiagnostic(
    val inputBlocker: RecoveryInputBlocker? = null,
    val candidateRejections: Set<RecoveryCandidateRejection> = emptySet(),
    val acceptedCandidate: Boolean = false,
    val decodedCandidate: Boolean = false,
    val singleSegmentRetryAvailable: Boolean = false,
    val unresolvedCalculations: Boolean = false,
    val unsearched: Boolean = false,
    val incompleteReasons: Set<SearchIncompleteReason> = emptySet(),
    /** Successfully completed processing steps, independent of the number of candidates. */
    val completedSteps: Int = 0,
    /** Whether diagnostic records cover every attempted input, not proof of exhaustive solving. */
    val coverageComplete: Boolean = true,
    val solverCompleteness: SearchCompleteness = SearchCompleteness.UNKNOWN,
    val candidateCalculations: Int = 0,
    /** Validated results omitted from the retained, ranked list; not an unexplored search branch. */
    val candidatesOmitted: Boolean = false,
    /** Equivalent explanations omitted while the representative's complete provenance is retained. */
    val derivationsOmitted: Boolean = false,
    val singleSegmentStatuses: Set<SingleSegmentStatus> = emptySet(),
    /** At most two distinct mode/count bit patterns, to distinguish multiple hypotheses. */
    val priorityHeaderHypotheses: Set<String> = emptySet(),
    val listDecoding: ListDecodingSummary? = null,
    val listDecodingExecution: ListDecodingExecution? = null,
) {
    val completeness: SearchCompleteness get() = when {
        unsearched || incompleteReasons.isNotEmpty() -> SearchCompleteness.UNSEARCHED
        !coverageComplete -> SearchCompleteness.UNKNOWN
        inputBlocker != null -> SearchCompleteness.COMPLETE
        else -> solverCompleteness
    }

    fun withUnsearched() = copy(unsearched = true)

    fun completionReasonsText(): String = buildList {
        incompleteReasons.forEach { reason -> add(when (reason) {
            SearchIncompleteReason.USER_INTERRUPTED -> localizedText("利用者が探索を中断しました。", "Search was interrupted by the user.")
            SearchIncompleteReason.WORKING_MEMORY_LIMIT -> localizedText(
                "計算用メモリの上限により、一部または全部の探索を実行できませんでした。",
                "The working-memory limit prevented some or all of the search from running.")
            SearchIncompleteReason.LIST_DECODING_TIME_LIMIT -> localizedText(
                "リスト復号による追加探索は時間切れで終了しました。未探索の組合せが残っています。",
                "List-decoding fallback ended at the time limit. Some combinations remain unchecked.")
            SearchIncompleteReason.INTERNAL_ERROR -> if (!coverageComplete) localizedText(
                "内部エラーにより、探索の完了状況を確認できません。",
                "An internal error prevented determining search completion.") else if (completedSteps > 0) localizedText(
                "内部エラーにより、一部の探索を実行できませんでした。",
                "An internal error prevented part of the search from running.") else localizedText(
                "内部エラーにより、修復処理を実行できませんでした。",
                "An internal error prevented repair from running.")
        }) }
        if (completeness == SearchCompleteness.UNKNOWN) add(localizedText(
            "探索の完了状況を確認するための情報が不足しています。",
            "There is insufficient execution information to determine whether the search completed."))
        if (completeness == SearchCompleteness.UNSEARCHED && incompleteReasons.isEmpty()) add(localizedText(
            "一部または全部の探索が未完了ですが、終了理由を確認できません。",
            "Some or all of the search is unfinished, but its stop reason is unavailable."))
    }.joinToString("\n")

    /** Null summaries must not disappear when combining geometries or sampling profiles. */
    companion object {
        fun combine(parts: List<RecoverySearchDiagnostic?>): RecoverySearchDiagnostic? {
            if (parts.isEmpty() || parts.all { it == null }) return null
            val known = parts.filterNotNull()
            return RecoverySearchDiagnostic(
                inputBlocker = known.first().inputBlocker?.takeIf { blocker ->
                    parts.all { it?.inputBlocker == blocker && it.coverageComplete }
                },
                listDecoding = ListDecodingSummary.combine(known.mapNotNull { it.listDecoding }),
                listDecodingExecution = ListDecodingExecution.combine(known.mapNotNull { it.listDecodingExecution }),
                candidateRejections = known.flatMapTo(linkedSetOf()) { it.candidateRejections },
                singleSegmentStatuses = known.flatMapTo(linkedSetOf()) { it.singleSegmentStatuses },
                priorityHeaderHypotheses = known.asSequence().flatMap { it.priorityHeaderHypotheses }.distinct().take(2).toSet(),
                acceptedCandidate = known.any { it.acceptedCandidate },
                decodedCandidate = known.any { it.decodedCandidate },
                singleSegmentRetryAvailable = known.any { it.singleSegmentRetryAvailable },
                unresolvedCalculations = known.any { it.unresolvedCalculations },
                unsearched = known.any { it.unsearched || it.incompleteReasons.isNotEmpty() },
                incompleteReasons = known.flatMapTo(linkedSetOf()) { it.incompleteReasons },
                completedSteps = known.sumOf { it.completedSteps },
                coverageComplete = parts.all { it?.coverageComplete == true },
                solverCompleteness = parts.fold(SearchCompleteness.COMPLETE) { result, part ->
                    result.combine(part?.completeness ?: SearchCompleteness.UNKNOWN)
                },
                candidateCalculations = known.sumOf { it.candidateCalculations },
                candidatesOmitted = known.any { it.candidatesOmitted },
                derivationsOmitted = known.any { it.derivationsOmitted },
            )
        }
    }

    fun failureExplanation(settings: RecoveryDisplayConditions?): String {
        if (!coverageComplete || acceptedCandidate) return ""
        when (inputBlocker) {
            RecoveryInputBlocker.FORMAT_SELECTION -> return localizedText(
                "形式情報の固定セルの0/1と、指定したECC・マスクが両立しません。",
                "Fixed 0/1 values in the format information conflict with the specified ECC/mask.")
            RecoveryInputBlocker.FORMAT_LIMIT -> return localizedText(
                "設定で許可されているECC・マスクの組合せは、すべて入力の形式情報との不一致が指定上限を超えています。",
                "Every permitted ECC/mask combination exceeds the mismatch limit for the entered format information.")
            RecoveryInputBlocker.STRUCTURAL_FIXED_CELLS -> return localizedText(
                "位置合わせパターンなど、データ・ECC以外の固定セルが、型番・形式の条件に合うQR構造と矛盾しています。",
                "Fixed cells outside data/ECC, such as alignment patterns, conflict with the QR structure required by the version and format conditions.")
            RecoveryInputBlocker.ZERO_MISMATCH -> if (settings?.dataEccLimit == 0) return localizedText(
                "入力の0/1に対して、不一致上限（0bit）を満たすRS候補はありません。",
                "No RS candidate meets the zero-bit mismatch limit against the entered 0/1 values.")
            null -> Unit
        }
        if (completeness != SearchCompleteness.COMPLETE || unresolvedCalculations) return ""
        return when (candidateRejections) {
            setOf(RecoveryCandidateRejection.FIXED_CELLS) -> localizedText(
                "得られた候補はすべて、固定セルの0/1と一致しないため、採用できませんでした。",
                "All candidates obtained conflicted with fixed 0/1 values and could not be accepted.")
            setOf(RecoveryCandidateRejection.MISMATCH_LIMIT) -> settings?.dataEccLimit?.let { limit -> localizedText(
                "得られた候補はすべて、入力のデータ・ECCとの不一致が指定上限の${limit}bitを超えるため、採用できませんでした。",
                "All candidates obtained exceeded the $limit-bit mismatch limit against the entered data/ECC and could not be accepted.") }.orEmpty()
            setOf(RecoveryCandidateRejection.FIXED_CELLS, RecoveryCandidateRejection.MISMATCH_LIMIT) -> localizedText(
                "得られた候補には、不一致上限を超えるものや、固定セルの0/1と一致しないものがあり、すべての指定条件を満たす修復候補は見つかりませんでした。",
                "Candidates obtained exceeded the mismatch limit or conflicted with fixed 0/1 values; no recovery candidate met all specified conditions.")
            setOf(RecoveryCandidateRejection.SINGLE_SEGMENT_STRUCTURE) -> localizedText(
                "得られた候補はすべて、強制した種類・文字数または終端後の構造と一致しないため、採用できませんでした。",
                "All candidates obtained conflicted with the forced mode/count or following structure and could not be accepted.")
            setOf(RecoveryCandidateRejection.LIST_DECODING_MISMATCH_LIMIT) -> localizedText(
                "リスト復号で得られた候補は、元の既知ビットとの不一致が上限を超えたため、採用できませんでした。",
                "List-decoded candidates exceeded the mismatch cap against the original known bits.")
            setOf(RecoveryCandidateRejection.QR_STRUCTURE) -> localizedText(
                "RSの条件を満たす候補は得られましたが、QRのデータ構造を満たす修復候補は見つかりませんでした。",
                "RS-consistent candidates were obtained, but no recovery candidate satisfied the QR data structure.")
            else -> ""
        }
    }
}

fun SearchCompleteness.description(): String = when (this) {
    SearchCompleteness.COMPLETE -> localizedText("指定条件・探索仕様の範囲で探索を完了しました。", "Search completed within the specified conditions and search policy.")
    SearchCompleteness.UNSEARCHED -> localizedText("未探索の組合せが残っています。", "Some combinations remain unchecked.")
    SearchCompleteness.UNKNOWN -> localizedText("探索の完了状況を確認できません。", "Search completion could not be determined.")
}

fun searchResultText(candidateCount: Int, completeness: SearchCompleteness, candidatesOmitted: Boolean = false,
    reasons: String = RecoverySearchDiagnostic(unsearched = completeness == SearchCompleteness.UNSEARCHED,
        solverCompleteness = completeness).completionReasonsText(),
): String {
    require(candidateCount >= 0)
    val result = when {
        candidatesOmitted -> localizedText(
            "優先度の高い修復候補を${candidateCount}件保持しています。ほかの候補の詳細は保持上限により省略しています。",
            "Retained $candidateCount highest-ranked recovery candidates. Other candidate details were omitted at the retention limit.")
        candidateCount > 0 -> localizedText("指定条件を満たす修復候補が${candidateCount}件見つかりました。",
            "$candidateCount recovery candidates meeting the specified conditions were found.")
        else -> localizedText("調べた範囲では修復候補は見つかりませんでした。", "No recovery candidate was found in the checked range.")
    }
    return localizedText("指定の条件下で探索を行いました。", "Searched under the specified conditions.") +
        "\n" + result + "\n" + completeness.description() + reasons.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
}

fun RecoveryDisplayConditions?.candidateCalculationPolicyText(): String =
    if (this?.dataEccLimit == 0) localizedText(
        "データ・ECCの不一致上限が0bitのため、候補の計算には、通常RSではなく部分bit制約を使う設定です。",
        "The data/ECC mismatch limit is zero, so candidate calculation is configured to use partial-bit constraints instead of conventional RS.")
    else ""
