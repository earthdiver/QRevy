package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

fun ListDecoderMethod.displayName(): String = when (this) {
    ListDecoderMethod.GURUSWAMI_SUDAN -> localizedText("Guruswami–Sudan（GS）法", "Guruswami–Sudan (GS)")
    ListDecoderMethod.KOETTER_VARDY -> localizedText("Koetter–Vardy（KV）法", "Koetter–Vardy (KV)")
    ListDecoderMethod.WU -> localizedText("Wu法", "Wu")
}

fun ListDecodingDerivation.calculationDescription(): String = when (method) {
    ListDecoderMethod.KOETTER_VARDY -> localizedText(
        "一致スコア: $score／必要スコア: $scoreThreshold、多重度の基準: $multiplicity、Y次数: $listDegree",
        "agreement score: $score / required score: $scoreThreshold, base multiplicity: $multiplicity, Y degree: $listDegree")
    ListDecoderMethod.WU -> localizedText(
        "訂正半径: ${radius}byte、残存消失: ${remainingErasures}byte",
        "radius: $radius bytes, erasures: $remainingErasures")
    ListDecoderMethod.GURUSWAMI_SUDAN -> localizedText(
        "訂正半径: ${radius}byte、残存消失: ${remainingErasures}byte、多重度: $multiplicity、Y次数: $listDegree",
        "radius: $radius bytes, erasures: $remainingErasures, multiplicity: $multiplicity, Y degree: $listDegree")
}
