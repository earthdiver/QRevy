package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

fun PartialCodewordSolver.Derivation.description(): String {
    val route = if (bitConstraintSolved) localizedText("RS部分ビット制約", "RS partial-bit constraints")
        else localizedText("通常RS", "conventional RS")
    val margin = if (bitConstraintSolved) localizedText("算定対象外", "not applicable") else rsMargin.toString()
    return localizedText(
        "$route / 訂正対象 ${errataPositions.size}コード語 / 探索 $guessedBits ビット / RS余裕 $margin",
        "$route / ${errataPositions.size} target codewords / $guessedBits searched bits / RS margin $margin",
    )
}
