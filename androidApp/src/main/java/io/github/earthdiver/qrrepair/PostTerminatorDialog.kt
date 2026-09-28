package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.text.InputType
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis
import io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis.Source
import io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis.Status

internal fun showPostTerminatorDialog(activity: Activity, attempt: PostTerminatorAnalysis.Attempt,
    version: Int, ecc: String, mask: Int): AlertDialog {
    fun label(ja: String, en: String) = localizedText(ja, en)
    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
    }
    fun paragraph(parent: LinearLayout, value: String) = TextView(activity).apply {
        text = value
        textSize = 14f
        setPadding(0, 8, 0, 8)
        parent.addView(this)
    }
    paragraph(content, label(
        "診断用の解析です。追加セグメントが読めても、隠しデータの存在や修復の正しさは確定しません。本文・修復候補は変更しません。\nV$version / ECC $ecc / mask $mask",
        "Diagnostic analysis. Readable additional segments do not prove intentional hidden data or correct recovery. The main content and recovery candidates are unchanged.\nV$version / ECC $ecc / mask $mask"))
    if (attempt.rsCorrectionRejected) paragraph(content, label(
        "通常RSの訂正結果は固定セルまたは誤り上限の条件を満たさないため、解析対象にしていません。",
        "The conventional RS result conflicts with fixed cells or the error limit and is excluded from analysis."))
    val selector = RadioGroup(activity)
    content.addView(selector)
    val details = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    content.addView(details)
    fun render(view: PostTerminatorAnalysis.View, interpretation: Int = 0) {
        details.removeAllViews()
        val reports = listOf(view.report) + view.report.alternatives
        val report = reports[interpretation]
        if (reports.size > 1) {
            paragraph(details, label("追加セグメントの開始位置を選択してください。開始位置によって、読み取れる内容が異なる場合があります。",
                "Choose the starting position of the additional segments. Different starting positions may produce different content."))
            details.addView(Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                    reports.mapIndexed { index, candidate ->
                        val starts = candidate.boundaries.joinToString(" → ") { "bit ${it.startBit}" }
                        label("${index + 1}. 開始位置：$starts ／ 読み取れた追加セグメント：${candidate.groups.size} 組",
                            "${index + 1}. Start: $starts / Additional segments read: ${candidate.groups.size} groups")
                    })
                setSelection(interpretation)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, selected: View?, position: Int, id: Long) {
                        if (position != interpretation) render(view, position)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            })
        }
        if (view.report.alternativesTruncated) paragraph(details, label(
            "開始位置の組み合わせを16通り調べました。ほかにも未確認の組み合わせがあります。",
            "Analysis checked 16 combinations of starting positions. Other combinations remain unchecked."))
        paragraph(details, when (view.source) {
            Source.OBSERVED -> label("修復前に読み取ったデータです。値が分からないビットは ? で表示します。", "Observed input. Unknown bits remain ?.")
            Source.CONVENTIONAL_RS -> label("通常の誤り訂正（RS）を適用したデータです。終端後で値を変更したビット: ${view.changedTailBits} bit / 不明だった値を補ったビット: ${view.filledTailBits} bit",
                "Data after conventional RS correction. Tail bits changed: ${view.changedTailBits}; filled from unknown: ${view.filledTailBits}.")
            Source.RECOVERY -> label("選択中の修復候補のデータです。終端後で値を変更したビット: ${view.changedTailBits} bit / 不明だった値を補ったビット: ${view.filledTailBits} bit",
                "Selected recovery candidate. Tail bits changed: ${view.changedTailBits}; filled from unknown: ${view.filledTailBits}.")
        })
        if (view.structureForced) paragraph(details, label(
            "この候補にはデータ構造からの補正が含まれます。終端後が埋め草で上書きされた可能性があるため、入力・通常RSの表示も確認してください。",
            "This candidate includes payload-structure corrections that may have overwritten trailing data with padding. Also inspect the input and conventional RS views."))
        paragraph(details, when (report.status) {
            Status.UNREADABLE_PREFIX -> label("本文の構造を確認できず、終端を特定できません。不明ビットや不正なモード・長さが考えられます。",
                "The main segment structure could not be read, so the terminator is unknown. Bits may be unknown or the mode or length invalid.")
            Status.NO_TERMINATOR -> label("完全な4ビット終端はありません。終端後の追加セグメント解析はできません。",
                "No complete four-bit terminator. Additional segments after a terminator cannot be analyzed.")
            Status.STANDARD_PADDING -> label("終端後は通常の埋め草です。追加セグメントは検出されませんでした。",
                "The tail is canonical padding. No additional segments were detected.")
            Status.ADDITIONAL_SEGMENTS -> label("終端後の追加セグメントを構文解析できました。", "Additional segments after the terminator were structurally parsed.")
            Status.UNPARSED_TAIL -> label("終端後に、通常の埋め草と一致しないデータ、または不明ビットがあります。残りのデータは追加セグメントとして読み取れませんでした。",
                "The tail contains noncanonical or unknown data. The remaining bits could not be parsed as additional segments.")
            Status.LIMIT -> label("追加セグメントを16組まで解析しました。残りのデータはまだ調べていません。", "Analysis reached its limit of 16 groups. Remaining data is unparsed.")
        })
        report.terminatorBit?.let { paragraph(details, label(
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
            if (boundary.startBit > boundary.afterTerminatorBit) paragraph(details, label(
                "解析時に読み飛ばした範囲: bit ${boundary.afterTerminatorBit}–${boundary.startBit - 1} / ${boundary.startBit - boundary.afterTerminatorBit} bit（すべて0と確認できたため。生データには残ります）",
                "Skipped during parsing: bits ${boundary.afterTerminatorBit}–${boundary.startBit - 1} / ${boundary.startBit - boundary.afterTerminatorBit} bits (all confirmed zero; retained in raw data)."))
            paragraph(details, label("追加セグメントの開始位置: bit ${boundary.startBit}（$basis）", "Additional segment starting position: bit ${boundary.startBit} ($basis)"))
        }
        report.stop?.let { stop ->
            val reason = when (stop.reason) {
                PostTerminatorAnalysis.StopReason.UNKNOWN_MODE_BITS -> label("データ形式を示すモードに不明ビット（?）があるため、読み取れません。", "Mode contains unknown bits")
                PostTerminatorAnalysis.StopReason.UNSUPPORTED_MODE -> label("この位置のモードには対応していません。", "Unsupported mode at this position.")
                PostTerminatorAnalysis.StopReason.INVALID_OR_INCOMPLETE_SEGMENT -> label("内容や長さがQRの規則に合わない、データが不足・不明、またはこの位置では使えないモードのため、読み取れません。", "Segment content, length or context is invalid, incomplete or unknown")
                PostTerminatorAnalysis.StopReason.NO_PAYLOAD -> label("この位置からは、追加の文字列やバイトデータを確認できません。", "No additional payload was found")
            }
            paragraph(details, label("読み取れなかったセグメントの開始位置: bit ${stop.bit} / モード（データ形式）: ${stop.modeBits.ifEmpty { "—" }}\n$reason",
                "Unreadable segment starts at bit ${stop.bit} / Mode (data format): ${stop.modeBits.ifEmpty { "—" }}\n$reason"))
        }
        report.paddingCandidateStartBit?.let { start -> paragraph(details, label(
            "bit $start 以降は、QRの空き容量を埋める値（16進数のEC、11の繰り返し）と一致します。偶然の一致もあり得るため、生データには残します。",
            "Possible trailing padding: alternating EC / 11 from bit $start. This can occur by chance in binary data and is retained in the raw view.")) }
        if (report.groups.isNotEmpty()) paragraph(details, label(
            "次の終端までに続く追加セグメントを1組として表示します。",
            "Consecutive additional segments up to the next terminator are displayed as one group."))
        report.groups.forEachIndexed { index, group ->
            val modes = group.modes.joinToString(" → ") { when (it) {
                1 -> label("数字", "Numeric")
                2 -> label("英数字", "Alphanumeric")
                4 -> label("バイト", "Byte")
                8 -> label("漢字", "Kanji")
                else -> "Hanzi"
            } }
            paragraph(details, label("追加セグメント ${index + 1}組目: bit ${group.startBit}–${group.endBit - 1}\n$modes",
                "Additional group ${index + 1}: bits ${group.startBit}–${group.endBit - 1}\n$modes"))
            if (group.text != null) paragraph(details, label("読み取った文字列（文字コードによっては正しく表示されません）",
                "Text interpretation (may display incorrectly depending on the encoding)"))
            paragraph(details, group.text ?: label("文字列に変換できません。生データを確認してください。",
                "Text conversion failed. Inspect the raw data.")).apply {
                group.text?.let { text = highlightedRecoveredText(it) }
            }
        }
        report.combinedContent?.let { combined ->
            paragraph(details, label("本文＋解析済み追加セグメント（連結）", "Main content + parsed additional segments (combined)"))
            combined.text?.let { value ->
                paragraph(details, label("読み取った文字列（文字コードによっては正しく表示されません）",
                    "Text interpretation (may display incorrectly depending on the encoding)"))
                paragraph(details, value).apply {
                    text = highlightedRecoveredText(value)
                    setTextIsSelectable(true)
                }
            } ?: paragraph(details, label("文字列に変換できません。生データを確認してください。",
                "Text conversion failed. Inspect the raw data."))
            combined.bytes?.let { bytes ->
                paragraph(details, label("連結した実データ (HEX)", "Combined payload bytes (HEX)"))
                paragraph(details, bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }).apply {
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                }
            }
        }
        report.remainingStartBit?.let { paragraph(details, label("追加セグメントとして読み取れていないデータの開始位置: bit $it", "Data not parsed as additional segments starts at bit $it")) }
        if (report.tail.isNotEmpty()) {
            val rawPanel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            val tailStart = report.terminatorBit!! + 4
            val tailEnd = tailStart + report.tail.length
            val raw = TextView(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
            }
            val offset = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_DONE
                hint = label("生データの表示開始位置（bit）", "Raw data display start (bit)")
                contentDescription = hint
                setText(tailStart.toString())
            }
            fun showRaw(start: Int) {
                val omitted = report.tail.take(start - tailStart)
                val prefix = if (omitted.isEmpty()) "" else label(
                    "表示を省略した範囲: bit $tailStart–${start - 1} / ${omitted.length} bit（データは削除していません）\n",
                    "Before display start: bits $tailStart–${start - 1} / ${omitted.length} bits (display offset only).\n")
                raw.text = prefix + label("bit $start から8 bitずつ表示します。各行は「位置: ビット列  16進数」です。? は不明ビット、-- は8 bit揃わないか不明ビットがあり16進数で表せない箇所です。\n",
                    "Groups of eight bits from bit $start. Each row shows position: bits  HEX. ? marks unknown bits; -- means fewer than eight bits or unknown bits prevent a HEX value.\n") +
                    report.tail.drop(start - tailStart).chunked(8).mapIndexed { i, bits ->
                        val hex = if (bits.length == 8 && '?' !in bits) "%02X".format(bits.toInt(2)) else "--"
                        "${start + i * 8}: $bits  $hex"
                    }.joinToString("\n")
            }
            paragraph(rawPanel, label("生データの表示開始位置を選択してください。追加セグメントの解析結果は変わりません。",
                "Choose where to start displaying raw data. This does not change the additional segment analysis."))
            rawPanel.addView(Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf(
                    label("終端直後", "Immediately after terminator"),
                    label("終端後の次のバイトの先頭", "Start of the next byte after the terminator"),
                    label("表示開始位置を指定（bit）", "Custom display start (bit)")))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, selected: View?, position: Int, id: Long) {
                        offset.isEnabled = position == 2
                        if (position != 2) {
                            val start = if (position == 0) tailStart else ((tailStart + 7) / 8 * 8).coerceAtMost(tailEnd)
                            offset.setText(start.toString())
                            offset.error = null
                            showRaw(start)
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            })
            fun applyOffset() {
                val start = offset.text.toString().toIntOrNull()
                if (start == null || start !in tailStart..tailEnd) {
                    offset.error = label("$tailStart〜$tailEnd を指定してください", "Enter a value from $tailStart to $tailEnd")
                    return
                }
                offset.error = null
                showRaw(start)
                activity.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(offset.windowToken, 0)
                offset.clearFocus()
            }
            offset.setOnEditorActionListener { _, actionId, event ->
                if (event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.action == KeyEvent.ACTION_UP) applyOffset()
                    true
                } else if (actionId == EditorInfo.IME_ACTION_DONE) {
                    applyOffset()
                    true
                } else false
            }
            rawPanel.addView(offset)
            rawPanel.addView(Button(activity).apply {
                text = label("指定位置から表示", "Show from starting bit")
                setOnClickListener { applyOffset() }
            })
            rawPanel.addView(raw)
            showRaw(tailStart)
            details.addView(Button(activity).apply {
                text = label("終端後の生データを表示／閉じる", "Show/hide raw tail bits and HEX")
                setOnClickListener { rawPanel.visibility = if (rawPanel.visibility == View.GONE) View.VISIBLE else View.GONE }
            })
            details.addView(rawPanel)
        }
    }
    val initial = attempt.views.indexOfFirst { it.source == Source.CONVENTIONAL_RS }.coerceAtLeast(0)
    attempt.views.forEachIndexed { index, view -> selector.addView(RadioButton(activity).apply {
        id = View.generateViewId()
        text = when (view.source) {
            Source.OBSERVED -> label("入力（補正前）", "Input (before correction)")
            Source.CONVENTIONAL_RS -> label("通常RSの訂正後", "After conventional RS")
            Source.RECOVERY -> label("修復候補", "Recovery candidate")
        }
        isChecked = index == initial
        setOnClickListener { render(view) }
    }) }
    render(attempt.views[initial])
    return AlertDialog.Builder(activity).setTitle(R.string.analyze_post_terminator)
        .setView(ScrollView(activity).apply { addView(content) })
        .setPositiveButton(R.string.close, null).show()
}
