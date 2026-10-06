package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Per-invocation records; elapsed time can be summed across geometries without resetting the budget. */
data class ListDecodingSummary(
    val trials: Int = 0,
    val interpolations: Int = 0,
    val elapsedMillis: Long = 0,
    val mismatchLimits: Set<Int> = emptySet(),
    val skipReasons: Set<ListDecodingTrialSkip> = emptySet(),
    val methodTrials: Map<ListDecoderMethod, Int> = emptyMap(),
) {
    fun description(): String = localizedText(
        "リスト復号の試行回数（探索全体）: ${trials}回、所要時間: ${if (elapsedMillis < 1000) "1秒未満" else "${elapsedMillis / 1000}秒"}",
        "List-decoding trials (whole search): $trials, elapsed time: ${if (elapsedMillis < 1000) "less than 1s" else "${elapsedMillis / 1000}s"}",
    )

    fun mismatchLimitDescription(): String = if (mismatchLimits.isEmpty()) "" else localizedText(
        "リスト復号の既知ビットの不一致上限: ${mismatchLimits.sorted().joinToString()}ビット",
        "Known-bit mismatch limit for list decoding: ${mismatchLimits.sorted().joinToString()} bits",
    )

    fun calculationDetails(): String = localizedText(
        "リスト復号の計算（探索全体）",
        "List-decoding calculations (whole search)",
    ) + mismatchLimitDescription().takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() + methodTrials.entries.sortedBy { it.key.ordinal }.joinToString("") { (method, count) ->
        localizedText("\n${method.displayName()}によるブロック復号: ${count}試行", "\nBlock decoding with ${method.displayName()}: $count trials")
    } + if (skipReasons.isEmpty()) "" else localizedText("\n一部試行の復号計算を省略: ", "\nDecoding skipped for some trials: ") +
            skipReasons.sortedBy { it.ordinal }.joinToString("; ") { reason -> when (reason) {
                ListDecodingTrialSkip.TOO_MANY_ERASURES -> localizedText("割当て後も消失が多すぎる", "too many erasures after assignment")
                ListDecodingTrialSkip.NO_ADDITIONAL_RADIUS -> localizedText("通常RSを超える訂正半径を取れない", "no radius beyond conventional RS")
                ListDecodingTrialSkip.MISMATCH_CAP -> localizedText("追加候補は必ず既知ビットの不一致上限を超える", "every additional candidate would exceed the known-bit limit")
                ListDecodingTrialSkip.UNIQUE_NEARBY_WORD -> localizedText("対象の半径内に別の符号語がないと証明できた", "no other codeword can lie within the selected radius")
            } }

    companion object {
        fun combine(parts: List<ListDecodingSummary>): ListDecodingSummary? = if (parts.isEmpty()) null else ListDecodingSummary(
            trials = parts.sumOf { it.trials }, interpolations = parts.sumOf { it.interpolations },
            elapsedMillis = parts.sumOf { it.elapsedMillis }, mismatchLimits = parts.flatMapTo(linkedSetOf()) { it.mismatchLimits },
            skipReasons = parts.flatMapTo(linkedSetOf()) { it.skipReasons },
            methodTrials = ListDecoderMethod.entries.associateWith { method -> parts.sumOf { it.methodTrials[method] ?: 0 } }
                .filterValues { it > 0 },
        )
    }
}
