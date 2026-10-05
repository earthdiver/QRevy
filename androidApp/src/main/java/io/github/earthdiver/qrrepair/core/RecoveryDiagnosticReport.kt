package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import io.github.earthdiver.qrrepair.localizedText

/** Stage boundaries describe when information was obtained, not a guessed recovery timeline. */
enum class DiagnosticStage {
    SETTINGS, INPUT, PROCESSING, VERIFICATION;

    fun title(): String = when (this) {
        SETTINGS -> localizedText("1. 今回の設定", "1. Settings for this run")
        INPUT -> localizedText("2. 修復前の状態", "2. Input before repair")
        PROCESSING -> localizedText("3. 今回の処理", "3. Processing in this run")
        VERIFICATION -> localizedText("4. 確認結果", "4. Verification results")
    }

    fun detailsTitle(): String = when (this) {
        SETTINGS -> localizedText("設定の補足", "Settings details")
        INPUT -> localizedText("RSブロックと部分bit制約の説明", "RS blocks and partial-bit constraints")
        PROCESSING -> localizedText("補完・訂正の計算", "Filling and correction calculations")
        VERIFICATION -> localizedText("データ形式・HEX・文字表示", "Data format, HEX and text representation")
    }
}

data class DiagnosticSection(val stage: DiagnosticStage, val summary: String, val details: String = "")

data class RecoveryDiagnosticReport(val scope: String, val sections: List<DiagnosticSection>) {
    init { require(sections.map { it.stage } == DiagnosticStage.entries) }
    fun fullText(): String = (listOf(scope) + sections.map {
        listOf(it.stage.title(), it.summary, it.details).filter(String::isNotBlank).joinToString("\n")
    }).joinToString("\n\n")
}

/** Read the saved execution settings, never the settings currently being edited. */
fun RecoveryDisplayConditions?.runSettingsText(): String = this?.let { settings -> buildString {
    append(localizedText("実行時点の指定です。", "These are the settings at the time of the run."))
    append(localizedText("\nECC: ", "\nECC: ")).append(settings.format?.ecLevel?.let {
        localizedText("指定 ($it)", "Specified ($it)")
    } ?: localizedText("自動", "Automatic"))
    append(localizedText("\nマスク: ", "\nMask: ")).append(settings.format?.dataMask?.let {
        localizedText("指定 ($it)", "Specified ($it)")
    } ?: localizedText("自動", "Automatic"))
    settings.lockManualBits?.let { locked ->
        append(localizedText("\n固定セルの0/1と矛盾する復元候補を許容する（仮指定セルと同じ扱い）: ", "\nAllow recovery candidates that conflict with fixed 0/1 values (as for tentative cells): ")).append(if (locked) "OFF" else "ON")
    }
    append(localizedText("\n単一セグメントの仮定: ", "\nSingle-segment assumption: "))
        .append(if (settings.trustSingleSegment) "ON" else "OFF")
    append('\n').append(settings.limitText(true)).append('\n').append(settings.limitText(false))
    if (settings.dataEccLimit == 0) {
        append(localizedText(
            "\n入力のデータ・ECCの0/1と完全に一致する候補だけを採用します。「?」の補完はできます。",
            "\nOnly candidates matching every entered data/ECC 0/1 value can be accepted. Unknown (?) bits may be filled."))
        if (settings.lockManualBits == false) append(localizedText(
            "\n固定セルとの矛盾を許容していても、データ・ECCの不一致上限は0bitです。この領域の入力済み0/1と異なる候補は採用できません。",
            "\nAllowing conflicts with fixed cells does not override the zero data/ECC mismatch limit; candidates differing from entered 0/1 values in this region cannot be accepted."))
    }
    if (settings.lockManualBits == false && settings.format != null) append(localizedText(
        "\n固定セルとの矛盾を許容していても、形式情報の固定セルとECC・マスクの指定は両立している必要があります。",
        "\nEven when conflicts with fixed cells are allowed, fixed format cells must remain compatible with the ECC/mask selection."))
} } ?: localizedText("実行時点の設定は記録されていません。", "The settings for this run were not recorded.")

fun initialDiagnosticSection(
    version: Int?, ecLevel: ErrorCorrectionLevel, mask: Int, geometryDescription: String,
    diagnostic: RsBitDiagnostic?, unknownBits: Int?, unknownCodewords: Int?,
    conventional: ConventionalRsDiagnostic? = null, candidateRaw: IntArray? = null,
    candidateUsesBitConstraints: Boolean = false,
    conditions: RecoveryDisplayConditions? = null,
): DiagnosticSection {
    val qr = version?.let(Version::getVersionForNumber)
    val blocks = qr?.getECBlocksForLevel(ecLevel)
    val summary = buildString {
        append(localizedText("参考診断の対象: ", "Reference diagnostic for: "))
        version?.let { append("V$it / ") }
        append("ECC $ecLevel / ").append(localizedText("マスク$mask", "mask $mask"))
        if (geometryDescription.isNotBlank()) append('\n').append(geometryDescription)
        append(localizedText(
            "\n入力は、画像の読み取りと手動編集を反映した、修復開始時の0/1/?です。",
            "\nInput means the 0/1/? values at repair start, including image sampling and manual edits."))
        if (qr != null && blocks != null) {
            val ecc = blocks.totalECCodewords
            append(localizedText(
                "\nRSシンボル: 全${qr.totalCodewords}個（データ${qr.totalCodewords - ecc} + ECC${ecc}）/ ${blocks.numBlocks}ブロック",
                "\nRS symbols: ${qr.totalCodewords} total (${qr.totalCodewords - ecc} data + $ecc ECC) / ${blocks.numBlocks} blocks"))
            append(localizedText("\n1シンボル = 1コード語 = 8bit", "\n1 symbol = 1 codeword = 8 bits"))
        }
        (diagnostic?.unknownBits ?: unknownBits)?.let {
            append(localizedText("\nデータ・ECC領域の不明なbit: $it", "\nUnknown bits in data/ECC: $it"))
        }
        unknownCodewords?.let {
            append(localizedText("\n不明なbitを含むコード語（全ブロック合計）: $it",
                "\nCodewords containing unknown bits (all blocks): $it"))
        }
        append(localizedText("\n\n参考診断：修復開始時の入力に対する通常RS\n", "\n\nReference diagnostic: conventional RS on the initial input\n"))
        if (conventional == null || conventional.blocks.isEmpty()) append(localizedText(
            "通常RSの参考診断の結果は記録されていません。", "The conventional RS reference diagnostic was not recorded."))
        else {
            append(localizedText(
                "構造補完やbit探索を加える前の入力を評価した結果です。修復候補を求める計算とは別に評価しています。\n",
                "This evaluates the input before structure filling or bit search, separately from calculating recovery candidates.\n"))
            val total = conventional.blocks.size
            val succeeded = conventional.blocks.count { it.status == ConventionalRsStatus.SUCCESS }
            val skipped = conventional.blocks.count { it.status == ConventionalRsStatus.ERASURE_CAPACITY_EXCEEDED }
            val failed = conventional.blocks.count { it.status == ConventionalRsStatus.CORRECTION_FAILED }
            append(when {
                succeeded == total -> localizedText(
                    "全${total}ブロックで、RSの条件を満たす訂正結果が得られました。",
                    "Correction results satisfying the RS conditions were obtained for all $total blocks.")
                skipped == total -> localizedText(
                    "全${total}ブロックで、不明bitを含むコード語の数がECCの数を超えるため、通常RSの訂正計算を省略しました。",
                    "Conventional RS correction was skipped for all $total blocks because codewords containing unknown bits outnumber ECC symbols.")
                else -> localizedText(
                    "訂正結果あり：${succeeded}ブロック／不明なコード語が多いため計算省略：${skipped}ブロック／計算したが訂正結果なし：${failed}ブロック",
                    "Correction result: $succeeded blocks / skipped because too many codewords contain unknown bits: $skipped blocks / correction attempted without a result: $failed blocks")
            })
            if (conventional.succeeded) conventional.minMargin?.let {
                append(localizedText("\nこの計算の訂正余裕（ブロック最小）: $it", "\nCorrection margin for this calculation (minimum across blocks): $it"))
            }
            if (conventional.succeeded && conventional.manualConflicts.isNotEmpty()) append(localizedText(
                "\nこの参考診断の結果は、固定セル${conventional.manualConflicts.size}個の0/1と一致しないため、そのまま採用できません。",
                "\nThis reference result conflicts with ${conventional.manualConflicts.size} fixed 0/1 values and cannot be accepted as-is."))
            val mismatches = conventional.knownBitMismatches
            val limit = conventional.knownBitMismatchLimit
            if (conventional.succeeded && mismatches != null && limit != null && mismatches > limit) append(localizedText(
                "\nこの参考診断の結果は、入力のデータ・ECCと${mismatches}bit異なります。不一致上限の${limit}bitを超えるため、そのまま採用できません。",
                "\nThis reference result differs from entered data/ECC by $mismatches bits, exceeding the $limit-bit mismatch limit, and cannot be accepted as-is."))
            if (candidateRaw != null) append(conventional.comparisonText(candidateRaw))
        }
        diagnostic?.takeIf { conventional?.succeeded != true || candidateUsesBitConstraints }?.let {
            append(localizedText("\n\n入力済みの白黒を保つ部分bit制約\n", "\n\nPartial-bit constraints preserving the entered values\n"))
            append(it.inputAnalysisDescription())
        }
    }
    val details = buildString {
        if (conventional != null) {
            append(localizedText(
                "通常RSは、構造補完やbit探索を加えずに初期入力へ適用した別の計算です。\n訂正余裕 = ECC数 − 消失コード語数 − 2 × 誤りコード語数。失敗したブロックの誤り数と余裕は不明です。",
                "Conventional RS is a separate calculation on the initial input, without structure filling or bit search.\nMargin = ECC symbols − erased codewords − 2 × erroneous codewords. Error counts and margins are unknown for failed blocks."))
            conventional.blocks.forEach { block ->
                val status = when (block.status) {
                    ConventionalRsStatus.SUCCESS -> localizedText("訂正結果が得られました", "correction result obtained")
                    ConventionalRsStatus.ERASURE_CAPACITY_EXCEEDED -> localizedText(
                        "不明bitを含むコード語${block.erasures}個がECCの${block.eccSymbols}個を超えるため、訂正計算を省略しました",
                        "correction skipped: ${block.erasures} codewords contain unknown bits, exceeding ${block.eccSymbols} ECC symbols")
                    ConventionalRsStatus.CORRECTION_FAILED -> localizedText("訂正計算を行いましたが、訂正結果は得られませんでした", "correction attempted without a result")
                }
                append(localizedText(
                    "\nブロック${block.blockIndex + 1}: データ${block.dataSymbols} + ECC${block.eccSymbols} = ${block.totalSymbols}シンボル / 消失${block.erasures} / 誤り${block.errors ?: "—"} / 余裕${block.margin ?: "—"}",
                    "\nBlock ${block.blockIndex + 1}: ${block.dataSymbols} data + ${block.eccSymbols} ECC = ${block.totalSymbols} symbols / erasures ${block.erasures} / errors ${block.errors ?: "—"} / margin ${block.margin ?: "—"}"))
                append(" / ").append(status)
            }
            conventional.qrStructureValid?.let {
                append(if (it) localizedText("\n通常RSの結果はQRのデータ構造を満たしています。", "\nThe conventional RS result satisfies the QR data structure.")
                    else localizedText("\n通常RSの結果はQRのデータ構造を満たしていません。", "\nThe conventional RS result does not satisfy the QR data structure."))
            }
            append("\n\n")
        }
        diagnostic?.let { analysis ->
            append(localizedText("入力済みの白黒を保つ部分bit制約", "Partial-bit constraints preserving the entered values"))
            append('\n').append(analysis.inputAnalysisDescription())
            if (blocks != null) append(localizedText(
                "\nRSの式: ${blocks.totalECCodewords * 8}本（全ブロック合計）。式の本数と独立な条件の数（rank）は別です。",
                "\nRS equations: ${blocks.totalECCodewords * 8} across all blocks. Equation count and independent constraint count (rank) are different."))
            if (analysis.dimension == null && conditions?.dataEccLimit == 0) append(localizedText(
                "\n上記の型番・ECC・マスクでは、入力の0/1に対して、不一致上限（0bit）を満たすRS候補はありません。自由度0とは異なります。",
                "\nFor the version/ECC/mask above, no RS candidate meets the zero-bit mismatch limit against entered 0/1 values. This is not zero free bits."))
            else if (analysis.dimension == null) append(localizedText(
                "\n入力済みの白黒をすべて維持する条件では両立しません。自由度0とは異なります。通常RSでは入力済みの誤りを訂正できる場合があります。",
                "\nThe constraints are inconsistent when all entered values are preserved; this is not zero free bits. Conventional RS may still correct errors in entered values."))
            else {
                append(localizedText(
                    "\n独立な条件は、ほかの条件の組合せでは得られない条件です。\nこのRSの組合せを1通りにするための追加の独立条件: ${analysis.dimension}個。任意のセルをその数だけ確認すればよい、という意味ではありません。\nこの形式と入力済みの白黒を正しいものとした、構造補完・QRの内容確認前の計算です。",
                    "\nAn independent constraint cannot be obtained by combining the other constraints.\nAdditional independent constraints needed for one RS combination: ${analysis.dimension}. This is not a count of arbitrary cells to check.\nThis calculation assumes the format and entered values are correct, before structure filling and QR content validation."))
                analysis.commonBitsDescription()?.let { append('\n').append(it) }
            }
        }
    }.trim()
    return DiagnosticSection(DiagnosticStage.INPUT, summary, details)
}

/** Short method list; records describe the adopted methods, not their execution order. */
fun RecoveryCandidate.compactUsedMethodsText(): String = localizedText("使用した方法: ", "Methods used: ") +
    buildList {
        if (constraintFilledBits > 0) add(if (constraintForcedByUserSetting) localizedText("構造補完（指定した仮定）", "structure filling (specified assumption)") else localizedText("構造補完", "structure filling"))
        if (priorityGuessedBits > 0) add(localizedText("優先セルのbit探索", "priority-cell bit search"))
        if (partialGuessedBits > 0) add(localizedText("bit探索", "bit search"))
        if (usesConventionalRs()) add(localizedText("通常RS", "conventional RS"))
        if (actualBlockMethods.any { it.gs != null }) add(localizedText("消失対応GS", "GS errors+erasures"))
        if (usesBitConstraints()) add(localizedText("部分bit制約", "partial-bit constraints"))
    }.joinToString(localizedText("・", ", "))

private fun RecoveryCandidate.usesBitConstraints(): Boolean = if (actualBlockMethods.isNotEmpty())
    actualBlockMethods.any { it.bitConstraintSolved } else repairInfo.any { it.bitConstraintSolved }

private fun RecoveryCandidate.usesConventionalRs(): Boolean = if (actualBlockMethods.isNotEmpty())
    actualBlockMethods.any { !it.bitConstraintSolved && it.gs == null } else repairInfo.isEmpty() || repairInfo.any { !it.bitConstraintSolved && it.gs == null }

fun RecoveryCandidate.usedMethodsText(): String = buildString {
    append(localizedText("この候補を得た方法", "Methods used to obtain this candidate"))
    // The records identify methods, not the complete chronological order of nested trials.
    if (constraintFilledBits > 0) append(if (constraintForcedByUserSetting)
        localizedText("\n指定した仮定に基づく構造補完・変更: ${constraintFilledBits}bit",
            "\nStructure-based filling/changes under the specified assumption: $constraintFilledBits bits")
        else localizedText("\nQRの構造に基づく補完・変更: ${constraintFilledBits}bit",
            "\nFilling/changes using QR structure: $constraintFilledBits bits"))
    if (priorityGuessedBits > 0) append(localizedText(
        "\n優先指定セルの白黒を仮置き: ${priorityGuessedBits}bit",
        "\nTentative values for prioritized cells: $priorityGuessedBits bits"))
    actualBlockMethods.forEach { append('\n').append(it.searchDescription(includeCalculationDetails = false)) }
    gsMismatchLimit?.let { append(localizedText("\nこの追加探索の既知ビットの不一致上限: ${it}ビット",
        "\nKnown-bit mismatch limit for this fallback: $it bits")) }
    if (usesBitConstraints()) append(localizedText(
        "\n部分bit制約で、不明なbitの組合せを計算しました。",
        "\nPartial-bit constraints calculated combinations of unknown bits."))
    if (usesConventionalRs()) append(if (correctedErrataSymbols > 0)
        localizedText("\n通常RSによる確認・補完・訂正を使いました。", "\nConventional RS was used to check, fill or correct values.")
        else localizedText("\n通常RSで条件を確認しました。", "\nConventional RS checked the conditions."))
    if (displayConditions?.trustSingleSegment == true && !constraintForcedByUserSetting) append(localizedText(
        "\n単一セグメントの仮定はONですが、この候補の補完には使用していません。",
        "\nThe single-segment assumption was ON but was not used to fill this candidate."))
    if (displayConditions?.trustSingleSegment == true) singleSegmentStatus?.let {
        append('\n').append(it.description())
        if (constraintForcedByUserSetting && constraintFilledBits == 0) append(localizedText(
            "（補完・変更は0bit）", " (0 bits filled/changed)"))
    }
}

fun RecoveryCandidate.inputComparisonText(): String = buildString {
    append(inputAgreementText(formatMatches + formatMismatches, formatMismatches, true))
    append('\n').append(inputAgreementText(knownBitsCompared, knownBitMismatchesAfterCorrection, false))
    manualDataEccCompared?.let { known ->
        if (known > 0) manualDataEccMismatches?.let { mismatches ->
            append(localizedText("\nデータ・ECC領域: 手動指定${known}bitとの不一致 ${mismatches}bit",
                "\nData/ECC region: $mismatches mismatches against $known manually specified bits"))
        }
    }
    if (displayConditions?.lockManualBits == false && displayConditions.dataEccLimit == 0) append(localizedText(
        "\n不一致上限が0bitのため、この領域の入力済み0/1は変更していません。",
        "\nThe zero mismatch limit prevents changes to entered 0/1 values in that region."))
}

/** Compare complete data/ECC codewords, not displayed text or structural cells. */
fun ConventionalRsDiagnostic.comparisonText(raw: IntArray): String = when (matches(raw)) {
    true -> localizedText("\n通常RSだけでも、選択中の候補と同じデータ・ECCコード語が得られました。",
        "\nConventional RS alone produced the same data/ECC codewords as the selected candidate.")
    false -> localizedText("\n通常RSだけの結果は、選択中の候補とは別の結果です（データ・ECCコード語が異なります）。",
        "\nThe conventional RS result differs from the selected candidate in its data/ECC codewords.")
    null -> localizedText("\n選択中の候補との比較結果はありません。", "\nNo comparison result is available for the selected candidate.")
}

fun RecoveryBlockMethod.searchDescription(includeCalculationDetails: Boolean = true): String = localizedText("ブロック${blockIndex + 1}: ", "Block ${blockIndex + 1}: ") +
    if (gs != null && !includeCalculationDetails) localizedText(
        "Guruswami–Sudan（GS）法によるリスト復号", "Guruswami–Sudan (GS) list decoding")
    else if (gs != null) localizedText(
        "消失対応GS／訂正半径: ${gs.radius}byte、残存消失: ${gs.remainingErasures}byte、多重度: ${gs.multiplicity}、Y次数: ${gs.listDegree}／仮置きした不明bit: $guessedBits",
        "GS errors+erasures / radius: ${gs.radius} bytes, erasures: ${gs.remainingErasures}, multiplicity: ${gs.multiplicity}, Y degree: ${gs.listDegree} / assigned unknown bits: $guessedBits")
    else if (bitConstraintSolved) localizedText(
        "部分bit制約／この計算段階の自由度: $guessedBits",
        "Partial-bit constraints / degrees of freedom at this calculation stage: $guessedBits")
    else localizedText("通常RS／計算前に仮置きした不明bit: $guessedBits",
        "Conventional RS / unknown bits assigned before calculation: $guessedBits")
