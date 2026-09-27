package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

enum class CombinationSearchStop { USER_INTERRUPTED, SUCCESS_WINDOW, COMBINATION_LIMIT, NODE_LIMIT, WORKING_MEMORY_LIMIT }

/** Scope is only the QR block-combination passes actually reached, not every possible QR. */
data class CombinationSearchSummary(
    val passes: Int = 0,
    val stops: Set<CombinationSearchStop> = emptySet(),
) {
    val incompleteReasons: Set<SearchIncompleteReason> get() = stops.mapNotNullTo(linkedSetOf()) {
        when (it) {
            CombinationSearchStop.USER_INTERRUPTED -> SearchIncompleteReason.USER_INTERRUPTED
            CombinationSearchStop.WORKING_MEMORY_LIMIT -> SearchIncompleteReason.WORKING_MEMORY_LIMIT
            else -> null
        }
    }
    val unsearched: Boolean get() = incompleteReasons.isNotEmpty()

    fun afterPass(passStops: Set<CombinationSearchStop>) =
        CombinationSearchSummary(passes + 1, stops + passStops)

    // An empty stop set does not prove exhaustion of upstream format/geometry choices.
    fun description(candidateCount: Int): String = buildString {
        require(candidateCount >= 0)
        append(localizedText(
            "指定条件のもとで探索した結果、${candidateCount}件の候補が見つかりました。",
            "Search under the specified conditions found $candidateCount candidates.",
        ))
        if (stops.isNotEmpty()) {
            append('\n')
            append(stopReasonsText())
            if (unsearched) append(localizedText("\nまだ調べていない組合せがあります。", "\nSome combinations remain unchecked."))
        }
    }

    fun stopReasonsText(): String = stops.map { reason ->
        when (reason) {
            CombinationSearchStop.USER_INTERRUPTED -> localizedText("利用者が探索を中断しました。", "Search was interrupted by the user.")
            CombinationSearchStop.SUCCESS_WINDOW -> localizedText("候補が見つかった後、追加の探索を一定範囲で終了しました。", "Additional search ended after a bounded range following candidate discovery.")
            CombinationSearchStop.COMBINATION_LIMIT, CombinationSearchStop.NODE_LIMIT -> localizedText("仕様で定めた探索上限まで実行しました。", "Search reached the limit defined by the search policy.")
            CombinationSearchStop.WORKING_MEMORY_LIMIT -> localizedText("計算用メモリの上限に達したため、途中で探索を終了しました。", "Search stopped early at the working-memory limit.")
        }
    }.distinct().joinToString("\n")
}
