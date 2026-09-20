package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Proven input restrictions, checked across every permitted format before making a run-wide claim. */
enum class RecoveryInputBlocker { FORMAT_SELECTION, FORMAT_LIMIT, STRUCTURAL_FIXED_CELLS, ZERO_MISMATCH }

enum class RecoveryCandidateRejection { FIXED_CELLS, MISMATCH_LIMIT, QR_STRUCTURE }

/** Evidence from the whole search, independent of the representative candidate or failure. */
data class RecoverySearchDiagnostic(
    val inputBlocker: RecoveryInputBlocker? = null,
    val candidateRejections: Set<RecoveryCandidateRejection> = emptySet(),
    val acceptedCandidate: Boolean = false,
    val unresolvedCalculations: Boolean = false,
    val unsearched: Boolean = false,
    val coverageComplete: Boolean = true,
    val candidateCalculations: Int = 0,
    /** Validated results omitted from the retained, ranked list; not an unexplored search branch. */
    val candidatesOmitted: Boolean = false,
    /** Equivalent explanations omitted while the representative's complete provenance is retained. */
    val derivationsOmitted: Boolean = false,
) {
    fun withUnsearched() = copy(unsearched = true)

    /** Null summaries must not disappear when combining geometries or sampling profiles. */
    companion object {
        fun combine(parts: List<RecoverySearchDiagnostic?>): RecoverySearchDiagnostic? {
            if (parts.isEmpty() || parts.all { it == null }) return null
            val known = parts.filterNotNull()
            return RecoverySearchDiagnostic(
                inputBlocker = known.first().inputBlocker?.takeIf { blocker ->
                    parts.all { it?.inputBlocker == blocker && it.coverageComplete }
                },
                candidateRejections = known.flatMapTo(linkedSetOf()) { it.candidateRejections },
                acceptedCandidate = known.any { it.acceptedCandidate },
                unresolvedCalculations = known.any { it.unresolvedCalculations },
                unsearched = known.any { it.unsearched },
                coverageComplete = parts.all { it?.coverageComplete == true },
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
        if (unsearched || unresolvedCalculations) return ""
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
            setOf(RecoveryCandidateRejection.QR_STRUCTURE) -> localizedText(
                "RSの条件を満たす候補は得られましたが、QRのデータ構造を満たす修復候補は見つかりませんでした。",
                "RS-consistent candidates were obtained, but no recovery candidate satisfied the QR data structure.")
            else -> ""
        }
    }
}

fun searchResultText(candidateCount: Int, unsearched: Boolean): String {
    require(candidateCount >= 0)
    return when {
        candidateCount > 0 && unsearched -> localizedText(
            "修復候補が${candidateCount}件見つかりました。未探索の組合せが残っているため、ほかにも候補が存在する可能性があります。",
            "$candidateCount recovery candidates were found. Unchecked combinations remain, so additional candidates may exist.")
        candidateCount > 0 -> localizedText(
            "指定条件を満たす修復候補が${candidateCount}件見つかりました。",
            "$candidateCount recovery candidates meeting the specified conditions were found.")
        unsearched -> localizedText(
            "探索範囲の上限に達しました。調べた範囲では修復候補は見つかりませんでしたが、未探索の組合せが残っています。",
            "The search limit was reached. No recovery candidate was found in the checked range; unchecked combinations remain.")
        else -> localizedText("指定条件を満たす修復候補は見つかりませんでした。",
            "No recovery candidate meeting the specified conditions was found.")
    }
}

fun RecoveryDisplayConditions?.candidateCalculationPolicyText(): String =
    if (this?.dataEccLimit == 0) localizedText(
        "データ・ECCの不一致上限が0bitのため、候補の計算には、通常RSではなく部分bit制約を使う設定です。",
        "The data/ECC mismatch limit is zero, so candidate calculation is configured to use partial-bit constraints instead of conventional RS.")
    else ""
