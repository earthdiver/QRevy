package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText
import kotlin.math.ln
import kotlin.math.max

/** Confidence is deliberately an explainable score, not a calibrated probability. */
data class CellConfidence(
    val score: Float,
    val labelJa: String,
    val detailJa: String,
    val labelEn: String = labelJa,
    val detailEn: String = detailJa,
)

data class CellRecommendation(
    val coord: Coord,
    val score: Double,
    val zeroCandidates: Int,
    val oneCandidates: Int,
    val totalCandidates: Int,
    val worstCaseRemaining: Int,
    val informationGainBits: Double,
    val reasonJa: String,
    val reasonEn: String = reasonJa,
    val exactSpaceDimension: Int? = null,
) {
    fun explanationJa(): String = buildString {
        append(localizedText("次の推奨セル: (${coord.x},${coord.y})", "Next recommended cell: (${coord.x},${coord.y})"))
        exactSpaceDimension?.let { d ->
            append(localizedText("\nRS自由度: ${d}bit → ${d - 1}bit（0/1のどちらでも）", "\nRS free bits: $d → ${d - 1} (for either 0 or 1)"))
        }
        if (totalCandidates > 1 && zeroCandidates > 0 && oneCandidates > 0) {
            append(localizedText("\n候補分岐: 0→${zeroCandidates}件・1→${oneCandidates}件", "\nCandidate split: 0→$zeroCandidates; 1→$oneCandidates"))
            append(localizedText("\n最悪ケースでも ${totalCandidates}→${worstCaseRemaining}件、情報利得=${"%.2f".format(informationGainBits)} bit", "\nWorst case: $totalCandidates→$worstCaseRemaining, information gain=${"%.2f".format(informationGainBits)} bit"))
        }
        append(localizedText("\n理由: $reasonJa", "\nReason: $reasonEn"))
    }
}

object RepairGuidance {
    /** This is conditional RS-space guidance, not a count of validated QR payload candidates. */
    fun recommendFromBitSpace(observed: QrMatrix, diagnostic: RsBitDiagnostic?): CellRecommendation? {
        val dimension = diagnostic?.dimension?.takeIf { it > 0 } ?: return null
        val coord = diagnostic.independentCells.firstOrNull {
            observed[it.x, it.y].value == ModuleValue.ERASED
        } ?: return null
        return CellRecommendation(coord, 100.0, 0, 0, 0, 0, 1.0,
            "この試行の形式と既知データ・ECCが正しいと仮定したRS整合解を二分します。誤りを許す解全体や、検証済みQR候補の分割ではありません。",
            "Halves the RS solution space assuming this attempt's format and known data/ECC are correct. This is not a split of all error-allowing solutions or validated QR candidates.",
            exactSpaceDimension = dimension)
    }

    /**
     * Build a confidence heat map by combining image confidence, provenance, and candidate consensus.
     * The score is 0..1 but is not presented as a statistical probability.
     */
    fun confidenceMap(
        source: QrMatrix,
        after: QrMatrix,
        candidateMatrices: List<QrMatrix>,
        traces: Map<Coord, ModuleRepairTrace>,
        totalCandidateCount: Int = candidateMatrices.size,
    ): Map<Coord, CellConfidence> {
        require(source.dimension == after.dimension)
        val out = HashMap<Coord, CellConfidence>(after.dimension * after.dimension)
        val comparison = candidateMatrices
        for (y in 0 until after.dimension) for (x in 0 until after.dimension) {
            // Format bits express a selected ECC/mask, not independent confidence in that choice.
            if (RepairTraceBuilder.isFormat(x, y, after.dimension)) continue
            val coord = Coord(x, y)
            val src = source[x, y]
            val cell = after[x, y]
            val trace = traces[coord]

            var base = when (cell.source) {
                ModuleSource.USER -> 1.0f
                ModuleSource.STRUCTURAL -> 0.99f
                ModuleSource.CONSTRAINT -> 0.91f
                ModuleSource.RECOVERED -> 0.82f
                ModuleSource.IMAGE -> src.confidence.coerceIn(0f, 1f)
            }
            var detail = when (cell.source) {
                ModuleSource.USER -> "ユーザー指定"
                ModuleSource.STRUCTURAL -> "QR固定構造から確定"
                ModuleSource.CONSTRAINT -> "ペイロード構造制約から推定"
                ModuleSource.RECOVERED -> "Reed–Solomon/復元候補から推定"
                ModuleSource.IMAGE -> "画像サンプリングの相対信頼度=${"%.2f".format(src.confidence)}（確率ではありません）"
            }
            var detailEn = when (cell.source) {
                ModuleSource.USER -> "User-defined"
                ModuleSource.STRUCTURAL -> "Determined from fixed QR structure"
                ModuleSource.CONSTRAINT -> "Inferred from payload structure constraints"
                ModuleSource.RECOVERED -> "Inferred from Reed–Solomon/recovery candidates"
                ModuleSource.IMAGE -> "Relative image-sampling confidence=${"%.2f".format(src.confidence)} (not a probability)"
            }

            if (comparison.size >= 2 && cell.value != ModuleValue.ERASED) {
                var same = 0
                var known = 0
                for (m in comparison) {
                    val v = m[x, y].value
                    if (v == ModuleValue.ERASED) continue
                    known++
                    if (v == cell.value) same++
                }
                if (known >= 2) {
                    val consensus = same.toFloat() / known
                    if (cell.source != ModuleSource.STRUCTURAL && cell.source != ModuleSource.USER) {
                        base = (base * 0.45f + consensus * 0.55f).coerceIn(0f, 1f)
                    }
                    detail += "\n候補一致: $same / 比較対象${known}候補（検証済み全${totalCandidateCount}候補）"
                    detailEn += "\nCandidate agreement: $same / $known compared ($totalCandidateCount validated candidates in total)"
                }
            }

            if (trace?.partialEnumerated == true) {
                base = minOf(base, 0.72f)
                detail += "\nビット単位列挙を使用"
                detailEn += "\nBit-level enumeration used"
            }
            if (cell.value == ModuleValue.ERASED) base = 0f
            val label = when {
                base >= 0.90f -> "高"
                base >= 0.65f -> "中"
                else -> "低"
            }
            val labelEn = when {
                base >= 0.90f -> "High"
                base >= 0.65f -> "Medium"
                else -> "Low"
            }
            out[coord] = CellConfidence(base, label, detail, labelEn, detailEn)
        }
        return out
    }

    /**
     * Select the cell that maximizes expected candidate separation. When candidates no longer
     * disagree, fall back to unresolved/low-confidence cells so the user still gets a useful target.
     */
    fun recommendNextCell(
        observed: QrMatrix,
        candidateMatrices: List<QrMatrix>,
        confidence: Map<Coord, CellConfidence>,
        totalCandidateCount: Int = candidateMatrices.size,
    ): CellRecommendation? {
        val matrices = candidateMatrices
        if (matrices.isEmpty()) return null
        val version = (observed.dimension - 17) / 4
        val dataAndEccCoordinates = runCatching {
            CodewordExtractor.extract(observed, version, 0).codewords
                .flatMapTo(hashSetOf()) { codeword -> codeword.coordinates }
        }.getOrElse { return null }
        var best: CellRecommendation? = null
        for (y in 0 until observed.dimension) for (x in 0 until observed.dimension) {
            val coord = Coord(x, y)
            if (coord !in dataAndEccCoordinates) continue
            var zeros = 0
            var ones = 0
            for (m in matrices) {
                when (m[x, y].value) {
                    ModuleValue.ZERO -> zeros++
                    ModuleValue.ONE -> ones++
                    ModuleValue.ERASED -> Unit
                }
            }
            val total = zeros + ones
            val observedCell = observed[x, y]
            val conf = confidence[coord]?.score ?: observedCell.confidence
            val split = zeros > 0 && ones > 0 && total >= 2
            val informationGain = if (split) binaryEntropy(zeros, ones) else 0.0
            val worst = if (total > 0) max(zeros, ones) else 0
            val unresolvedBonus = if (observedCell.value == ModuleValue.ERASED) 45.0 else 0.0
            val lowConfidenceBonus = (1.0 - conf.coerceIn(0f, 1f)) * 28.0
            val splitScore = informationGain * 100.0
            val score = splitScore + unresolvedBonus + lowConfidenceBonus
            if (!split && observedCell.value != ModuleValue.ERASED && conf >= 0.45f) continue
            val reason = when {
                split && observedCell.value == ModuleValue.ERASED -> "復元候補が0/1に分岐しており、かつ元画像ではerasureです。ここを判定すると候補を最も減らせます。"
                split -> "復元候補がこのセルで0/1に分岐しています。候補集合の情報利得が大きいセルです。"
                observedCell.value == ModuleValue.ERASED -> "候補間の分岐は小さいものの、未確定のerasureなので手動判定の価値があります。"
                else -> "画像サンプリングの相対信頼度が低く、誤認識の可能性を確認する価値があります。"
            }
            val reasonEn = when {
                split && observedCell.value == ModuleValue.ERASED -> "Recovery candidates split between 0 and 1, and the source is an erasure. Resolving it reduces the candidate set most."
                split -> "Recovery candidates split between 0 and 1 at this cell, giving high information gain."
                observedCell.value == ModuleValue.ERASED -> "Candidate separation is small, but this unresolved erasure is still worth checking manually."
                else -> "Image confidence is low, so this cell is worth checking for a recognition error."
            }
            val scopeJa = if (matrices.size < totalCandidateCount) " 比較対象${matrices.size}件（検証済み全${totalCandidateCount}件）の範囲での案内です。" else ""
            val scopeEn = if (matrices.size < totalCandidateCount) " Guidance covers ${matrices.size} compared candidates out of $totalCandidateCount validated candidates." else ""
            val rec = CellRecommendation(coord, score, zeros, ones, total, worst, informationGain, reason + scopeJa, reasonEn + scopeEn)
            if (best == null || rec.score > best.score) best = rec
        }
        return best
    }

    private fun binaryEntropy(zero: Int, one: Int): Double {
        val n = zero + one
        if (n <= 0 || zero == 0 || one == 0) return 0.0
        val p0 = zero.toDouble() / n
        val p1 = one.toDouble() / n
        return -(p0 * ln(p0) + p1 * ln(p1)) / ln(2.0)
    }
}
