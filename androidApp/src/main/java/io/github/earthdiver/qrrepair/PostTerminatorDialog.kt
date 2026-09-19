package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.View
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
    fun render(view: PostTerminatorAnalysis.View) {
        details.removeAllViews()
        val report = view.report
        paragraph(details, when (view.source) {
            Source.OBSERVED -> label("入力の観測値です。不明ビットは ? のまま保持します。", "Observed input. Unknown bits remain ?.")
            Source.CONVENTIONAL_RS -> label("埋め草を強制しない通常RSの訂正値です。終端後の変更: ${view.changedTailBits} bit / 不明から補完: ${view.filledTailBits} bit",
                "Conventional RS without forced padding. Tail bits changed: ${view.changedTailBits}; filled from unknown: ${view.filledTailBits}.")
            Source.RECOVERY -> label("選択中の修復候補です。終端後の変更: ${view.changedTailBits} bit / 不明から補完: ${view.filledTailBits} bit",
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
            Status.UNPARSED_TAIL -> label("通常とは異なる、または不明な終端後データがあります。残りを追加セグメントとして解析できませんでした。",
                "The tail contains noncanonical or unknown data. The remaining bits could not be parsed as additional segments.")
            Status.LIMIT -> label("解析上限（16組）に達しました。残りは未解析です。", "Analysis reached its limit of 16 groups. Remaining data is unparsed.")
        })
        report.terminatorBit?.let { paragraph(details, label(
            "本文の終端: bit $it–${it + 3} / 終端後: ${report.tail.length} bit\n位置は並べ替え済みデータ列の先頭を0とします。",
            "Main terminator: bits $it–${it + 3}; tail: ${report.tail.length} bits.\nOffsets start at zero in the deinterleaved data stream.")) }
        report.groups.forEachIndexed { index, group ->
            val modes = group.modes.joinToString(" → ") { when (it) {
                1 -> label("数字", "Numeric")
                2 -> label("英数字", "Alphanumeric")
                4 -> label("バイト", "Byte")
                8 -> label("漢字", "Kanji")
                else -> "Hanzi"
            } }
            paragraph(details, label("追加セグメント ${index + 1}: bit ${group.startBit}–${group.endBit - 1}\n$modes",
                "Additional group ${index + 1}: bits ${group.startBit}–${group.endBit - 1}\n$modes"))
            if (group.text != null) paragraph(details, label("文字列候補（文字コードによっては正しく表示されません）",
                "Text interpretation (may display incorrectly depending on the encoding)"))
            paragraph(details, group.text ?: label("文字列に変換できません。生データを確認してください。",
                "Text conversion failed. Inspect the raw data.")).apply {
                group.text?.let { text = highlightedRecoveredText(it) }
            }
        }
        report.remainingStartBit?.let { paragraph(details, label("未解析部分の開始: bit $it", "Unparsed remainder starts at bit $it")) }
        if (report.tail.isNotEmpty()) {
            val raw = TextView(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                text = label("終端直後から8 bitずつ表示します。末尾の端数は補完しません。\n", "Groups of eight bits starting immediately after the terminator. Final partial bits are not padded.\n") +
                    report.tail.chunked(8).mapIndexed { i, bits ->
                        val hex = if (bits.length == 8 && '?' !in bits) "%02X".format(bits.toInt(2)) else "--"
                        "${report.terminatorBit!! + 4 + i * 8}: $bits  $hex"
                    }.joinToString("\n")
                visibility = View.GONE
            }
            details.addView(Button(activity).apply {
                text = label("終端後の生データを表示／閉じる", "Show/hide raw tail bits and HEX")
                setOnClickListener { raw.visibility = if (raw.visibility == View.GONE) View.VISIBLE else View.GONE }
            })
            details.addView(raw)
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
