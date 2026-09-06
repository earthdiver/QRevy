package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

enum class CombinationSearchStop { SUCCESS_WINDOW, COMBINATION_LIMIT, NODE_LIMIT, RETENTION_LIMIT }

/** Scope is only the QR block-combination passes actually reached, not every possible QR. */
data class CombinationSearchSummary(
    val passes: Int = 0,
    val stops: Set<CombinationSearchStop> = emptySet(),
) {
    fun afterPass(passStops: Set<CombinationSearchStop>) =
        CombinationSearchSummary(passes + 1, stops + passStops)

    fun withRetentionLimit() = copy(stops = stops + CombinationSearchStop.RETENTION_LIMIT)

    fun description(): String = if (stops.isEmpty()) localizedText(
        "QR組合せ探索: 実行した${passes}試行内の組合せを検証済み（QR全体の網羅性を示しません）",
        "QR combination search: combinations exhausted in $passes attempted passes (not exhaustive QR recovery)",
    ) else localizedText(
        "QR組合せ探索: 未探索の組合せがあります。候補数は探索済み範囲で検証できた数です。終了理由: ",
        "QR combination search: combinations remain unexplored. Candidate counts cover validated results found so far. Stop reasons: ",
    ) + stops.joinToString { reason -> when (reason) {
        CombinationSearchStop.SUCCESS_WINDOW -> localizedText("成功後の探索窓終了", "window ended after success")
        CombinationSearchStop.COMBINATION_LIMIT -> localizedText("組合せ数上限", "combination limit")
        CombinationSearchStop.NODE_LIMIT -> localizedText("展開ノード数上限", "expanded-node limit")
        CombinationSearchStop.RETENTION_LIMIT -> localizedText("候補保持の資源上限（未探索候補あり）", "candidate retention budget (candidates remain unexplored)")
    } }
}
