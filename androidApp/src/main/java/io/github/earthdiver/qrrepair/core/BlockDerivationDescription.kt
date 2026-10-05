package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

fun PartialCodewordSolver.Derivation.description(): String {
    val route = if (gs != null) localizedText("消失対応GS", "GS errors+erasures") else if (bitConstraintSolved) localizedText("部分bit制約", "RS partial-bit constraints")
        else localizedText("通常RS", "conventional RS")
    val margin = if (bitConstraintSolved || gs != null) localizedText("算定対象外", "not applicable") else rsMargin.toString()
    val choices = if (bitConstraintSolved) localizedText("この計算段階の自由度 $guessedBits", "degrees of freedom at this stage: $guessedBits")
        else localizedText("復号前に仮置きした不明bit $guessedBits", "unknown bits assigned before decoding: $guessedBits")
    return localizedText(
        "$route / 補完・訂正対象 ${errataPositions.size}コード語 / $choices / この計算の通常RS余裕 $margin",
        "$route / ${errataPositions.size} target codewords / $choices / conventional RS margin for this calculation: $margin",
    ) + (gs?.let { localizedText(
        " / GS半径 ${it.radius}byte / 残存消失 ${it.remainingErasures}byte / 多重度 ${it.multiplicity} / Y次数 ${it.listDegree}",
        " / GS radius ${it.radius} bytes / erasures ${it.remainingErasures} / multiplicity ${it.multiplicity} / Y degree ${it.listDegree}") } ?: "")
}
