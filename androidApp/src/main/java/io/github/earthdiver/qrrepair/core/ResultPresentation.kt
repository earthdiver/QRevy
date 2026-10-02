package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Result overview. Technical encoding/HEX information remains available in diagnostics. */
fun QrContentPreview.resultContentText(fullText: String?, onRecoveredText: ((Int, Int) -> Unit)? = null): String = buildString {
    fun appendRecovered(value: String) {
        val start = length
        append(value)
        if (value.isNotEmpty()) onRecoveredText?.invoke(start, length)
    }
    val rawText = recoveredText ?: fullText
    append(localizedText("読み取れた内容", "Read content")).append(" — ").append(title)
    if (!qrStructureValid) append(localizedText("\nQRのデータ構造を確認できませんでした。", "\nThe QR data structure could not be verified."))
    val value = if (kind == QrContentKind.TEXT) rawText ?: summary else summary
    if (if (kind == QrContentKind.TEXT) value.isNotEmpty() else value.isNotBlank()) {
        append('\n')
        if (rawText != null && value == rawText) appendRecovered(value) else append(value)
    }
    fields.filter { kind != QrContentKind.TEXT }.forEach { field ->
        append('\n').append(field.label).append(": ").append(field.value)
    }
    rawText?.takeIf { kind != QrContentKind.TEXT && it != summary }?.let { raw ->
        append(localizedText("\n\n復元した文字列（原文）\n", "\n\nRecovered text (original)\n"))
        appendRecovered(raw)
    }
    if (textRepresentation && !contentFormatValid) append(localizedText(
        "\n必要な情報が不足しているか、形式に不備があります。", "\nRequired information is missing or malformed."))
    if (textRepresentation && byteSegments.isNotEmpty()) append(localizedText(
        "\nバイトデータを文字に変換した表示です。文字コードによって表示が変わります。",
        "\nText converted from bytes; the display depends on the character encoding."))
    structuredAppend?.let { append('\n').append(it.description()) }
    fnc1ApplicationIndicator?.let { append(localizedText("\nFNC1アプリケーション識別子: $it", "\nFNC1 application indicator: $it")) }
}

fun RecoveryCandidate.resultExplanationText(): String = buildString {
    append(compactUsedMethodsText())
    if ((manualDataEccMismatches ?: 0) > 0) append(localizedText(
        "\n手動指定と異なる値: ${manualDataEccMismatches}bit（データ・ECC領域）",
        "\nValues differing from manual entries: $manualDataEccMismatches bits (data/ECC region)"))
    if (combinationSearch.unsearched) append(localizedText(
        "\n探索は途中で終了しました。未確認の組合せがあります。",
        "\nSearch ended early. Some combinations remain unchecked."))
}

/** Candidate numbers are one-based display labels, independent of list positions. */
fun candidatePairComparisonText(
    first: RecoveryCandidate,
    second: RecoveryCandidate,
    firstNumber: Int,
    secondNumber: Int,
): String {
    require(firstNumber > 0 && secondNumber > 0)
    val a = QrContentPreviewParser.parse(first)!!
    val b = QrContentPreviewParser.parse(second)!!
    var hasExcerpt = false
    return buildString {
        append(localizedText("候補${firstNumber}と候補${secondNumber}の比較", "Compare candidates $firstNumber and $secondNumber"))
        fun difference(label: String, left: String?, right: String?) {
            if (left == right) return
            val diff = firstDifferenceExcerpt(left, right)
            hasExcerpt = hasExcerpt || diff.truncated
            append("\n\n").append(label).append(localizedText(" — 最初の違い", " — first difference"))
            append(localizedText("（${diff.position}文字目）", " (character ${diff.position})"))
            append(localizedText("\n候補${firstNumber}: ", "\nCandidate $firstNumber: ")).append(diff.left)
            append(localizedText("\n候補${secondNumber}: ", "\nCandidate $secondNumber: ")).append(diff.right)

        }
        val sameText = first.text == second.text
        val sameData = first.dataBytes.contentEquals(second.dataBytes)
        when {
            a.kind == QrContentKind.URL && b.kind == QrContentKind.URL -> {
                // Preserve the original text and tolerate future changes to URL classification.
                val left = first.text?.let { runCatching { java.net.URI(it) }.getOrNull() }
                val right = second.text?.let { runCatching { java.net.URI(it) }.getOrNull() }
                if (left == null || right == null) {
                    difference(localizedText("元の内容", "Original content"), first.text, second.text)
                } else {
                    difference(localizedText("スキーム", "Scheme"), left.scheme, right.scheme)
                    difference(localizedText("接続先", "Authority"), left.rawAuthority, right.rawAuthority)
                    difference(localizedText("パス", "Path"), left.rawPath, right.rawPath)
                    difference(localizedText("クエリ", "Query"), left.rawQuery, right.rawQuery)
                    difference(localizedText("フラグメント", "Fragment"), left.rawFragment, right.rawFragment)
                }
            }
            a.kind == QrContentKind.TEXT && b.kind == QrContentKind.TEXT && !sameText -> {
                val left = first.text!!
                val right = second.text!!
                difference(localizedText("内容", "Content"), left, right)
            }
            !sameText -> difference(localizedText("内容", "Content"), first.text, second.text)
        }
        if (sameText && first.text != null) append(localizedText("\n表示する内容は同じです。", "\nThe displayed content is the same."))
        append(if (sameData) localizedText("\nデータコード語列は同じです。", "\nThe data codeword sequences are identical.")
            else localizedText("\nデータコード語列は異なります。", "\nThe data codeword sequences differ."))
        if (hasExcerpt) append(localizedText("\n長い項目は抜粋です。各候補の内容表示で全文を確認できます。",
            "\nLong fields are excerpted. View each candidate’s content for the full text."))
    }
}

internal data class DifferenceExcerpt(val left: String, val right: String, val position: Int, val truncated: Boolean)

/** Compare original code points before escaping whitespace; never split a surrogate pair. */
internal fun firstDifferenceExcerpt(left: String?, right: String?): DifferenceExcerpt {
    val a = left.orEmpty().codePoints().toArray()
    val b = right.orEmpty().codePoints().toArray()
    var first = 0
    while (first < minOf(a.size, b.size) && a[first] == b[first]) first++
    var truncated = false
    fun excerpt(original: String?, points: IntArray): String {
        if (original == null) return localizedText("（項目なし）", "(field absent)")
        if (points.isEmpty()) return localizedText("（空）", "(empty)")
        val start = maxOf(0, first - 16)
        val end = minOf(points.size, first + 48)
        if (start > 0 || end < points.size) truncated = true
        return buildString {
            if (start > 0) append("…")
            for (i in start until end) {
                val cp = points[i]
                append(when (cp) {
                    10 -> "\\n"
                    13 -> "\\r"
                    9 -> "\\t"
                    32 -> "␠"
                    92 -> "\\\\"
                    0x2420 -> "\\u{2420}"
                    else -> if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || Character.isSpaceChar(cp))
                        "\\u{${cp.toString(16).uppercase()}}" else String(Character.toChars(cp))
                })
            }
            if (first == points.size) append(localizedText("［末尾］", "[end]"))
            if (end < points.size) append("…")
        }
    }
    val x = excerpt(left, a)
    val y = excerpt(right, b)
    return DifferenceExcerpt(x, y, first + 1, truncated)
}
