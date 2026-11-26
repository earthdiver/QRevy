package qrevy.web

import io.github.earthdiver.qrrepair.localizedText
import io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis
import io.github.earthdiver.qrrepair.core.PayloadSegmentDiagnostics
import io.github.earthdiver.qrrepair.core.StrictQrPayloadValidator
import io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis.Source
import io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis.Status

// Text paragraphs from upstream PostTerminatorDialog, with Android styling removed.
internal fun tailReportText(view: PostTerminatorAnalysis.View, report: PostTerminatorAnalysis.Report): String {
    val paragraphs=mutableListOf<String>()
    fun label(ja:String,en:String)=localizedText(ja,en)
    fun appendParagraph(value:String){paragraphs.add(value)}
        if (view.report.alternativesTruncated) appendParagraph( label(
            "開始位置の組み合わせを16通り調べました。ほかにも未確認の組み合わせがあります。",
            "Analysis checked 16 combinations of starting positions. Other combinations remain unchecked."))
        appendParagraph( when (view.source) {
            Source.OBSERVED -> label("修復前に読み取ったデータです。値が分からないビットは ? で表示します。", "Observed input. Unknown bits remain ?.")
            Source.CONVENTIONAL_RS -> label("通常の誤り訂正（RS）を適用したデータです。終端後で値を変更したビット: ${view.changedTailBits} bit / 不明だった値を補ったビット: ${view.filledTailBits} bit",
                "Data after conventional RS correction. Tail bits changed: ${view.changedTailBits}; filled from unknown: ${view.filledTailBits}.")
            Source.RECOVERY -> label("選択中の修復候補のデータです。終端後で値を変更したビット: ${view.changedTailBits} bit / 不明だった値を補ったビット: ${view.filledTailBits} bit",
                "Selected recovery candidate. Tail bits changed: ${view.changedTailBits}; filled from unknown: ${view.filledTailBits}.")
        })
        if (view.structureForced) appendParagraph( label(
            "この候補にはデータ構造からの補正が含まれます。終端後が埋め草で上書きされた可能性があるため、入力・通常RSの表示も確認してください。",
            "This candidate includes payload-structure corrections that may have overwritten trailing data with padding. Also inspect the input and conventional RS views."))
        appendParagraph( when (report.status) {
            Status.UNREADABLE_PREFIX -> label("本文の構造を確認できず、終端を特定できません。不明ビットや不正なモード・長さが考えられます。",
                "The main segment structure could not be read, so the terminator is unknown. Bits may be unknown or the mode or length invalid.")
            Status.NO_TERMINATOR -> label("完全な4ビット終端はありません。終端後の追加セグメント解析はできません。",
                "No complete four-bit terminator. Additional segments after a terminator cannot be analyzed.")
            Status.STANDARD_PADDING -> label("終端後は通常の埋め草です。追加セグメントは検出されませんでした。",
                "The tail is canonical padding. No additional segments were detected.")
            Status.ADDITIONAL_SEGMENTS -> label("終端後の追加セグメントを構文解析できました。", "Additional segments after the terminator were structurally parsed.")
            Status.UNPARSED_TAIL -> label("終端後に、通常の埋め草と一致しないデータ、または不明ビットがあります。残りのデータは追加セグメントとして読み取れませんでした。",
                "The tail contains noncanonical or unknown data. The remaining bits could not be parsed as additional segments.")
            Status.LIMIT -> label("終端後の解析を16回まで行いました。残りのデータはまだ調べていません。", "Analysis reached its limit of 16 continuations after terminators. Remaining data is unparsed.")
        })
        report.terminatorBit?.let { appendParagraph( label(
            "本文の終端: bit $it–${it + 3} / 終端後に残るデータ: ${report.tail.length} bit\n位置は、本文のデータの先頭を bit 0 として数えます。誤り訂正用のデータは含みません。",
            "Main terminator: bits $it–${it + 3}; data after terminator: ${report.tail.length} bits.\nPositions count from bit 0 of the main payload data, excluding error-correction bytes.")) }
        report.boundaries.forEach { boundary ->
            val basis = when (boundary.kind) {
                PostTerminatorAnalysis.StartKind.IMMEDIATE -> label("終端直後", "Immediately after terminator")
                PostTerminatorAnalysis.StartKind.ZERO_MODES -> label("終端直後から連続する0000を読み飛ばした位置", "After skipping consecutive 0000 sequences following the terminator")
                PostTerminatorAnalysis.StartKind.BYTE_ALIGNED -> {
                    val aligned = (boundary.afterTerminatorBit + 7) / 8 * 8
                    label("次のバイトの先頭 bit $aligned から、連続する0000があれば読み飛ばした位置",
                        "From the next byte at bit $aligned, skipping any consecutive 0000 sequences")
                }
            }
            if (boundary.startBit > boundary.afterTerminatorBit) appendParagraph( label(
                "解析時に読み飛ばした範囲: bit ${boundary.afterTerminatorBit}–${boundary.startBit - 1} / ${boundary.startBit - boundary.afterTerminatorBit} bit（すべて0と確認できたため。生データには残ります）",
                "Skipped during parsing: bits ${boundary.afterTerminatorBit}–${boundary.startBit - 1} / ${boundary.startBit - boundary.afterTerminatorBit} bits (all confirmed zero; retained in raw data)."))
            appendParagraph( label("追加セグメントの開始位置: bit ${boundary.startBit}（$basis）", "Additional segment starting position: bit ${boundary.startBit} ($basis)"))
        }
        report.trace.firstOrNull { it.status == PayloadSegmentDiagnostics.Status.UNSUPPORTED_ECI }?.let {
            appendParagraph( label("最初の未対応指定: bit ${it.structure.startBit}（ECI ${it.structure.eci}）",
                "First unsupported designation: bit ${it.structure.startBit} (ECI ${it.structure.eci})"))
        }
        if (report.trace.isNotEmpty()) appendParagraph( label("追加セグメントの解析経過（仮の解釈）",
            "Additional segment trace (tentative interpretation)"))
        report.trace.forEach { entry ->
            val item = entry.structure
            val name = when (item.mode) {
                1 -> label("数字", "Numeric")
                2 -> label("英数字", "Alphanumeric")
                3 -> "Structured Append"
                4 -> label("バイト", "Byte")
                5 -> "FNC1 (1)"
                7 -> "ECI"
                8 -> label("漢字", "Kanji")
                9 -> "FNC1 (2)"
                13 -> "Hanzi"
                else -> label("モード未確定・未対応", "Unknown or unsupported mode")
            }
            val end = maxOf(item.endBit, item.problemEndBit ?: item.endBit)
            val range = if (end > item.startBit) "bit ${item.startBit}–${end - 1}" else "bit ${item.startBit}"
            val fields = buildString {
                append("$range / $name")
                item.mode?.let { append(" / ${it.toString(2).padStart(4, '0')}") }
                item.eci?.let { append(label(" / 指定値 $it", " / assignment $it")) }
                entry.charsetName?.let { append(" / $it") }
                item.count?.let { append(label(" / 長さ $it", " / count $it")) }
                if (item.issue != null && item.requiredBits != null) append(label(
                    "\n宣言されたデータ長: ${item.requiredBits} bit / ヘッダー後の残り: ${item.availableBits} bit",
                    "\nDeclared payload: ${item.requiredBits} bits / Available after header: ${item.availableBits} bits"))
            }
            val state = when (entry.status) {
                PayloadSegmentDiagnostics.Status.UNSUPPORTED_ECI -> label("指定形式は解析済みですが、このECIには対応していません。指定値が規格上不正とは断定しません。",
                    "Header parsed; this ECI is unsupported. This does not establish that the assignment is invalid in the standard.")
                PayloadSegmentDiagnostics.Status.UNKNOWN_ENCODING -> label("構造は解析済み。未対応ECIが有効なため、バイトを文字列に変換していません。",
                    "Structure parsed. Bytes are not converted to text while an unsupported ECI is active.")
                PayloadSegmentDiagnostics.Status.INVALID_TEXT -> label("構造は解析済みですが、文字列に変換できません。", "Structure parsed, but text conversion failed.")
                PayloadSegmentDiagnostics.Status.PARSED -> if (item.count == 0) label("解析済み。長さ0のため本文は空です。", "Parsed; zero-length payload is empty.")
                    else if (item.mode in listOf(3, 5, 7, 9)) label("制御指定を解析済み（本文データなし）。", "Control header parsed (no payload data).")
                    else label("構造を解析済み。", "Structure parsed.")
                PayloadSegmentDiagnostics.Status.FAILED -> when (item.issue) {
                    StrictQrPayloadValidator.Issue.INVALID_CONTEXT -> label("この位置では使用できない制御指定です。", "Control header is not allowed in this context.")
                    StrictQrPayloadValidator.Issue.INCOMPLETE -> label("項目を読み切るためのビットが不足しています。", "Insufficient bits to complete this item.")
                    StrictQrPayloadValidator.Issue.UNKNOWN_BITS -> label("不明ビットがあるため解析を継続できません。", "Unknown bits prevent further parsing.")
                    StrictQrPayloadValidator.Issue.INVALID_ECI -> label("ECI指定のビット形式が不正です。", "Malformed ECI bit format.")
                    StrictQrPayloadValidator.Issue.UNSUPPORTED_MODE -> label("未対応のモード値です。", "Unsupported mode value.")
                    else -> label("内容がこのモードの規則に合いません。", "Content does not satisfy the rules for this mode.")
                }
            }
            appendParagraph( "$fields\n$state")
            item.problemStartBit?.let { start ->
                val last = (item.problemEndBit ?: start) - 1
                val location = if (last >= start) "bit $start–$last" else "bit $start"
                appendParagraph( label("解析を妨げた範囲: $location", "Field preventing parsing: $location"))
            }
            // Show individually decoded pieces when the group cannot be rendered as complete text.
            if (report.groups.any { it.text == null && item.startBit >= it.startBit && item.startBit < it.endBit }) {
                entry.text?.let { value -> appendParagraph( label("この項目の文字列", "Text for this item"))
                    appendParagraph( value) }
                entry.bytes?.let { bytes -> appendParagraph( "HEX: " + bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) })
                     }
            }
        }
        report.stop?.let { stop ->
            val reason = when (stop.reason) {
                PostTerminatorAnalysis.StopReason.UNKNOWN_MODE_BITS -> label("データ形式を示すモードに不明ビット（?）があるため、読み取れません。", "Mode contains unknown bits")
                PostTerminatorAnalysis.StopReason.UNSUPPORTED_MODE -> label("この位置のモードには対応していません。", "Unsupported mode at this position.")
                PostTerminatorAnalysis.StopReason.INVALID_OR_INCOMPLETE_SEGMENT -> label("内容や長さがQRの規則に合わない、データが不足・不明、またはこの位置では使えないモードのため、読み取れません。", "Segment content, length or context is invalid, incomplete or unknown")
                PostTerminatorAnalysis.StopReason.NO_PAYLOAD -> label("この位置からは、追加の文字列やバイトデータを確認できません。", "No additional payload was found")
            }
            appendParagraph( label("構文解析の停止位置（項目の先頭）: bit ${stop.bit} / モード（データ形式）: ${stop.modeBits.ifEmpty { "—" }}\n$reason",
                "Syntax parsing stopped at item starting at bit ${stop.bit} / Mode (data format): ${stop.modeBits.ifEmpty { "—" }}\n$reason"))
        }
        report.paddingCandidateStartBit?.let { start -> appendParagraph( label(
            "bit $start 以降は、QRの空き容量を埋める値（16進数のEC、11の繰り返し）と一致します。偶然の一致もあり得るため、生データには残します。",
            "Possible trailing padding: alternating EC / 11 from bit $start. This can occur by chance in binary data and is retained in the raw view.")) }
        if (report.groups.isNotEmpty()) appendParagraph( label(
            "次の終端または解析停止までに構造を読めた追加セグメントを1組として表示します。",
            "Completed additional segments up to the next terminator or parsing failure are displayed as one group."))
        report.groups.forEachIndexed { index, group ->
            val modes = group.modes.joinToString(" → ") { when (it) {
                1 -> label("数字", "Numeric")
                2 -> label("英数字", "Alphanumeric")
                4 -> label("バイト", "Byte")
                8 -> label("漢字", "Kanji")
                else -> "Hanzi"
            } }
            appendParagraph( label("追加セグメント ${index + 1}組目: bit ${group.startBit}–${group.endBit - 1}\n$modes",
                "Additional group ${index + 1}: bits ${group.startBit}–${group.endBit - 1}\n$modes"))
            if (group.text != null) appendParagraph( label("読み取った文字列（文字コードによっては正しく表示されません）",
                "Text interpretation (may display incorrectly depending on the encoding)"))
            appendParagraph( group.text ?: label("文字列に変換できません。生データを確認してください。",
                "Text conversion failed. Inspect the raw data."))
        }
        report.combinedContent?.let { combined ->
            appendParagraph( label("本文＋解析済み追加セグメント（連結）", "Main content + parsed additional segments (combined)"))
            combined.text?.let { value ->
                appendParagraph( label("読み取った文字列（文字コードによっては正しく表示されません）",
                    "Text interpretation (may display incorrectly depending on the encoding)"))
                appendParagraph( value)
            } ?: appendParagraph( label("文字列に変換できません。生データを確認してください。",
                "Text conversion failed. Inspect the raw data."))
            combined.bytes?.let { bytes ->
                appendParagraph( label("連結した実データ (HEX)", "Combined payload bytes (HEX)"))
                appendParagraph( bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) })
            }
        }
        report.remainingStartBit?.let { appendParagraph( label("追加セグメントとして読み取れていないデータの開始位置: bit $it", "Data not parsed as additional segments starts at bit $it")) }
    return paragraphs.joinToString("\n\n")
}
