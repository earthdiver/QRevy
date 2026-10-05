package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Per-invocation records; elapsed time can be summed across geometries without resetting the budget. */
data class GsSearchSummary(
    val trials: Int = 0,
    val interpolations: Int = 0,
    val elapsedMillis: Long = 0,
    val mismatchLimits: Set<Int> = emptySet(),
    val skipReasons: Set<GsTrialSkip> = emptySet(),
) {
    fun description(): String = localizedText(
        "GS法による追加探索（探索全体）: ${trials}試行、${elapsedMillis / 1000}秒",
        "GS fallback (whole search): $trials trials, ${elapsedMillis / 1000}s",
    )

    fun mismatchLimitDescription(): String = if (mismatchLimits.isEmpty()) "" else localizedText(
        "GS追加探索の既知ビットの不一致上限: ${mismatchLimits.sorted().joinToString()}ビット",
        "Known-bit mismatch limit for GS fallback: ${mismatchLimits.sorted().joinToString()} bits",
    )

    fun calculationDetails(): String = localizedText(
        "GS法による追加探索（探索全体）\n補間: ${interpolations}回",
        "GS fallback (whole search)\nInterpolations: $interpolations",
    ) + if (skipReasons.isEmpty()) "" else localizedText("\n一部試行の補間を省略: ", "\nInterpolation skipped for some trials: ") +
            skipReasons.sortedBy { it.ordinal }.joinToString("; ") { reason -> when (reason) {
                GsTrialSkip.TOO_MANY_ERASURES -> localizedText("割当て後も消失が多すぎる", "too many erasures after assignment")
                GsTrialSkip.NO_ADDITIONAL_RADIUS -> localizedText("通常RSを超える訂正半径を取れない", "no radius beyond conventional RS")
                GsTrialSkip.MISMATCH_CAP -> localizedText("追加候補は必ず既知ビットの不一致上限を超える", "every additional candidate would exceed the known-bit limit")
                GsTrialSkip.UNIQUE_NEARBY_WORD -> localizedText("対象の半径内に別の符号語がないと証明できた", "no other codeword can lie within the selected radius")
            } }

    companion object {
        fun combine(parts: List<GsSearchSummary>): GsSearchSummary? = if (parts.isEmpty()) null else GsSearchSummary(
            trials = parts.sumOf { it.trials }, interpolations = parts.sumOf { it.interpolations },
            elapsedMillis = parts.sumOf { it.elapsedMillis }, mismatchLimits = parts.flatMapTo(linkedSetOf()) { it.mismatchLimits },
            skipReasons = parts.flatMapTo(linkedSetOf()) { it.skipReasons },
        )
    }
}
