package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

fun PartialCodewordSolver.Derivation.description(): String {
    val route = listDecoding?.method?.displayName() ?: if (bitConstraintSolved) localizedText("部分bit制約", "RS partial-bit constraints")
        else localizedText("通常RS", "conventional RS")
    val margin = if (bitConstraintSolved || listDecoding != null) localizedText("算定対象外", "not applicable") else rsMargin.toString()
    val choices = if (bitConstraintSolved) localizedText("この計算段階の自由度 $guessedBits", "degrees of freedom at this stage: $guessedBits")
        else localizedText("復号前に仮置きした不明bit $guessedBits", "unknown bits assigned before decoding: $guessedBits")
    return localizedText(
        "$route / 補完・訂正対象 ${errataPositions.size}コード語 / $choices / この計算の通常RS余裕 $margin",
        "$route / ${errataPositions.size} target codewords / $choices / conventional RS margin for this calculation: $margin",
    ) + (listDecoding?.let { " / " + it.calculationDescription() } ?: "")
}
