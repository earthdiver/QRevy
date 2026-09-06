package io.github.earthdiver.qrrepair

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ToggleButton
import io.github.earthdiver.qrrepair.core.CodewordExtractor
import io.github.earthdiver.qrrepair.core.CodewordHex
import io.github.earthdiver.qrrepair.core.FormatSolver
import io.github.earthdiver.qrrepair.core.PartialPayloadAnalyzer
import io.github.earthdiver.qrrepair.core.QrFormatConstraint
import io.github.earthdiver.qrrepair.core.QrMatrix

internal data class PreRecoveryDataInput(val matrix: QrMatrix, val version: Int)
internal data class PreRecoveryDataContent(
    val text: String,
    val needsManualFormat: Boolean,
)

internal data class PreRecoveryDataStrings(
    val extractFailed: String = "データ領域を抽出できません。",
    val beforeUnmasking: String = "マスク解除前",
    val afterUnmasking: String = "マスク解除後",
    val specifiedFormat: String = "手動形式",
    val inferredFormat: String = "推定形式",
    val unknownFormat: String = "形式未確定",
    val unknownBit: String = "不明bit",
    val content: String = "内容",
    val partialInformation: String = "部分情報",
    val unvalidatedContent: String = "検証済みデータはありません。以下の部分情報は診断用です。",
    val selectFormat: String = "形式情報が未確定です。基本画面で誤り訂正レベルとマスクを指定してください。",
    val interpretAfterUnmasking: String = "マスク解除後に解釈できます。",
    val auto: String = "自動",
)

internal fun buildPreRecoveryDataContent(
    input: PreRecoveryDataInput,
    formatConstraint: QrFormatConstraint?,
    unmasked: Boolean,
    partialOnly: Boolean = false,
    strings: PreRecoveryDataStrings = PreRecoveryDataStrings(),
): PreRecoveryDataContent {
    val inferredFormat = FormatSolver.trustedCandidate(input.matrix)
        ?.takeIf { formatConstraint?.permits(it.ecLevel, it.dataMask) != false }
    val ecLevel = formatConstraint?.ecLevel ?: inferredFormat?.ecLevel
    val dataMask = formatConstraint?.dataMask ?: inferredFormat?.dataMask
    val extractionMask = dataMask ?: 0
    val extraction = runCatching {
        CodewordExtractor.extract(input.matrix, input.version, extractionMask)
    }.getOrNull()
    val text = if (extraction == null) {
        strings.extractFailed
    } else buildString {
        val japanese = strings.partialInformation == "部分情報"
        val separator = if (japanese) " ・ " else " · "
        val maskLabel = if (japanese) "マスク=" else "Mask="
        val formatSeparator = if (japanese) "、" else ", "
        append(if (unmasked) strings.afterUnmasking else strings.beforeUnmasking)
            .append(separator).append(if (japanese) "V" else "Version ").append(input.version)
            .append(separator).append(when {
                formatConstraint != null -> buildString {
                    append(strings.specifiedFormat).append(": ")
                    append("ECC=").append(formatConstraint.ecLevel ?: strings.auto)
                    append(formatSeparator).append(maskLabel).append(formatConstraint.dataMask ?: strings.auto)
                    if ((formatConstraint.ecLevel == null || formatConstraint.dataMask == null) && inferredFormat != null) {
                        append(" (").append(strings.inferredFormat).append(": ECC=")
                            .append(inferredFormat.ecLevel).append(formatSeparator).append(maskLabel)
                            .append(inferredFormat.dataMask).append(')')
                    }
                }
                inferredFormat != null -> buildString {
                    append(strings.inferredFormat).append(": ECC=").append(inferredFormat.ecLevel)
                    append(formatSeparator).append(maskLabel).append(inferredFormat.dataMask)
                }
                else -> strings.unknownFormat
            })
            .append("\n? = ").append(strings.unknownBit)
        if (!partialOnly) {
            if (unmasked && (ecLevel == null || dataMask == null)) {
                append("\n\n").append(strings.content).append("\n").append(strings.selectFormat)
            } else {
                append("\n\nHEX\n")
                append(if (unmasked) CodewordHex.unmasked(extraction) else CodewordHex.masked(input.matrix, extraction))
                append("\n\nBinary\n")
                    .append(if (unmasked) CodewordHex.binary(extraction) else CodewordHex.maskedBinary(input.matrix, extraction))
                append("\n\n").append(strings.content).append("\n")
                    .append(if (unmasked) strings.unvalidatedContent else strings.interpretAfterUnmasking)
            }
        }
        if (partialOnly) {
            append("\n\n").append(strings.partialInformation).append("\n")
            append(when {
                !unmasked -> strings.interpretAfterUnmasking
                ecLevel == null || dataMask == null -> strings.selectFormat
                else -> runCatching {
                    PartialPayloadAnalyzer.fromInterleaved(extraction, input.version, ecLevel)
                        .displayText(japanese = japanese)
                }.getOrElse { strings.extractFailed }
            })
        }
    }
    return PreRecoveryDataContent(text, needsManualFormat = ecLevel == null || dataMask == null)
}

/** Owns the pre-recovery data page while receiving the current matrix as immutable input. */
internal class PreRecoveryDataPage(
    context: Context,
    private val inputProvider: () -> PreRecoveryDataInput?,
    private val formatConstraintProvider: () -> QrFormatConstraint? = { null },
) {
    private val strings = if (AppLanguageManager.selected(context) == AppLanguage.JAPANESE) {
        PreRecoveryDataStrings()
    } else {
        PreRecoveryDataStrings(
            extractFailed = "Could not extract the data area.",
            beforeUnmasking = "Before unmasking",
            afterUnmasking = "After unmasking",
            specifiedFormat = "manual format",
            inferredFormat = "inferred format",
            unknownFormat = "format unknown",
            unknownBit = "unknown bit",
            content = "Content",
            partialInformation = "Partial information",
            unvalidatedContent = "No payload-validated content is available. The partial information below is diagnostic only.",
            selectFormat = "Format is unknown. Set the ECC level and mask on the Basic screen.",
            interpretAfterUnmasking = "Content can be interpreted after unmasking.",
            auto = "Auto",
        )
    }
    private val output = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setTextIsSelectable(true)
        setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
    }
    private val unmaskToggle = ToggleButton(context).apply {
        textOff = context.getString(R.string.mask_before)
        textOn = context.getString(R.string.mask_after)
        isChecked = true
        setOnCheckedChangeListener { _, _ -> refresh() }
    }
    private val partialToggle = ToggleButton(context).apply {
        textOff = context.getString(R.string.raw_data_view)
        textOn = context.getString(R.string.partial_info_view)
        isChecked = false
        setOnCheckedChangeListener { _, _ -> refresh() }
    }

    val view: View

    init {
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(unmaskToggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(partialToggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(
                ScrollView(context).apply { addView(output) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
    }

    fun refresh() {
        val input = inputProvider()
        if (input == null) {
            output.setText(R.string.data_extract_failed)
            return
        }
        val content = buildPreRecoveryDataContent(
            input,
            formatConstraintProvider(),
            unmaskToggle.isChecked,
            partialToggle.isChecked,
            strings,
        )
        output.text = content.text
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
