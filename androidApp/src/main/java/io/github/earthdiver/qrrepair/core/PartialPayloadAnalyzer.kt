package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.charset.CodingErrorAction

data class PartialPayloadReport(
    val mode: Int?,
    val count: Int?,
    val countRequired: Boolean,
    val knownBytePrefix: List<Int>,
    val knownTextPrefix: String,
    val stoppedAtUnknownBit: Boolean,
    val stoppedAtInvalidValue: Boolean = false,
    val note: String? = null,
    /** Hanzi subset indicator; null when its four bits are not fully observed. */
    val hanziSubset: Int? = null,
    val eci: Int? = null,
    val fnc1Mode: Int? = null,
    val fnc1ApplicationIndicator: Int? = null,
    val controlIncomplete: Boolean = false,
    val knownEncodedCharacters: Int? = null,
    val textUnavailable: Boolean = false,
) {
    fun displayText(japanese: Boolean): String = buildString {
        append(if (japanese) "未検証の診断情報（復号結果ではありません）" else "Unvalidated diagnostic information (not a decoded result)")
        append('\n')
        eci?.let { append("ECI: ").append(it).append('\n') }
        fnc1Mode?.let {
            append(if (it == 5) "FNC1 (1st position)" else "FNC1 (2nd position)")
            fnc1ApplicationIndicator?.let { value -> append(" / ").append(value) }
            append('\n')
        }
        if (controlIncomplete) {
            append(if (japanese) "制御情報が不明または不正なため、後続の本文は解釈しません。" else
                "Control information is unknown or invalid; the following payload is not interpreted.")
            return@buildString
        }
        if (mode == null) {
            append(if (japanese) "Mode: 不明（先頭4bitに?を含みます）" else "Mode: unknown (? in the first 4 bits)")
            return@buildString
        }
        append("Mode: ").append(modeName(mode, japanese)).append(" (0x").append(mode.toString(16).uppercase()).append(')')
        if (mode == 13) {
            append('\n').append(if (japanese) "サブセット: " else "Subset: ")
            when (hanziSubset) {
                null -> {
                    append(if (japanese) "不明（指示子が欠損、または?を含みます）。文字数は読み取りません。" else
                        "unknown (indicator missing or contains ?). Character count is not read.")
                    return@buildString
                }
                1 -> append("1 (GB2312)")
                else -> {
                    append(hanziSubset).append(if (japanese) "（未対応）。文字数は読み取りません。" else
                        " (unsupported). Character count is not read.")
                    return@buildString
                }
            }
        }
        if (countRequired) {
            append('\n').append(if (japanese) "文字数／バイト数: " else "Character/byte count: ")
            append(count ?: if (japanese) "不明（count bitに?を含みます）" else "unknown (? in count bits)")
        }
        if (mode == 4 && count != null) {
            append('\n').append(if (japanese) "先頭から連続して確定したpayload byte: " else "Known consecutive payload bytes from start: ")
            append(knownBytePrefix.size).append('/').append(count)
            if (knownBytePrefix.isNotEmpty()) {
                append("\nHEX: ").append(knownBytePrefix.joinToString(" ") { "%02X".format(it) })
                append("\nASCII: ").append(knownBytePrefix.joinToString("") { value ->
                    if (value in 0x20..0x7E) value.toChar().toString() else "."
                })
            }
            if (knownTextPrefix.isNotEmpty()) append("\nText: ").append(knownTextPrefix)
            if (textUnavailable) append('\n').append(if (japanese) "ECIの文字コードが未対応、または既知バイト列を文字列に変換できません。HEXで確認してください。" else
                "The ECI charset is unsupported or known bytes cannot be converted to text. Inspect HEX.")
            if (stoppedAtUnknownBit) append('\n').append(if (japanese) "次のbyteに?があるため、そこで表示を停止しました。" else "Stopped because the next byte contains ?.")
        }
        if (mode in setOf(1, 2, 8) && count != null) {
            append('\n').append(if (japanese) "先頭から連続して確定したpayload文字: " else "Known consecutive payload characters from start: ")
            append(knownEncodedCharacters ?: knownTextPrefix.length).append('/').append(count)
            if (knownTextPrefix.isNotEmpty()) append("\nText: ").append(knownTextPrefix)
            if (stoppedAtUnknownBit) append('\n').append(if (japanese) "次の文字groupに?があるため、そこで表示を停止しました。" else "Stopped because the next character group contains ?.")
            if (stoppedAtInvalidValue) append('\n').append(if (japanese) "次の文字groupが規格上の値域外のため、そこで表示を停止しました。" else "Stopped because the next character group is outside the valid range.")
        }
        if (mode == 13) append('\n').append(if (japanese) "Hanziの本文の部分表示には対応していません。" else
            "Partial body display is not supported for Hanzi.")
        note?.let { append('\n').append(if (japanese) "このModeは部分情報表示では解釈しません。" else it) }
    }

    private fun modeName(value: Int, japanese: Boolean): String = when (value) {
        0 -> if (japanese) "終端" else "terminator"
        1 -> if (japanese) "数字" else "numeric"
        2 -> if (japanese) "英数字" else "alphanumeric"
        3 -> if (japanese) "構造的連接" else "structured append"
        4 -> if (japanese) "バイト" else "byte"
        5 -> "FNC1"
        7 -> "ECI"
        8 -> if (japanese) "漢字" else "Kanji"
        9 -> if (japanese) "FNC1第2位置" else "FNC1 second position"
        13 -> "Hanzi"
        else -> if (japanese) "未対応" else "unsupported"
    }
}

object PartialPayloadAnalyzer {
    fun fromInterleaved(
        extraction: CodewordExtraction,
        version: Int,
        ecLevel: ErrorCorrectionLevel,
    ): PartialPayloadReport {
        val deinterleaved = QrBlockInterleaver.deinterleave(extraction, version, ecLevel)
        val data = buildList {
            deinterleaved.blocks.forEach { block ->
                repeat(block.numDataCodewords) { index ->
                    add(PartialCodeword(block.codewords[index], block.unknownMasks[index], emptyList()))
                }
            }
        }
        return analyze(data, version)
    }

    fun analyze(dataCodewords: List<PartialCodeword>, version: Int): PartialPayloadReport {
        require(version in 1..40)
        val bits = dataCodewords.flatMap { byte ->
            (7 downTo 0).map { bit ->
                if ((byte.unknownBits and (1 shl bit)) != 0) null else (byte.value shr bit) and 1
            }
        }
        var offset = 0
        var eci: Int? = null
        var fnc1: Int? = null
        var application: Int? = null
        var structured = false
        var sawEci = false
        fun readControl(width: Int): Int? {
            if (offset + width > bits.size || bits.subList(offset, offset + width).any { it == null }) return null
            var result = 0
            repeat(width) { result = (result shl 1) or bits[offset++]!! }
            return result
        }
        fun stopped(mode: Int, unknown: Boolean) = PartialPayloadReport(mode, null, false, emptyList(), "", unknown,
            stoppedAtInvalidValue = !unknown, eci = eci, fnc1Mode = fnc1,
            fnc1ApplicationIndicator = application, controlIncomplete = true)
        while (true) {
            val start = offset
            val mode = readControl(4) ?: break
            when (mode) {
                3 -> {
                    if (start != 0 || structured) return stopped(mode, false)
                    val header = readControl(16) ?: return stopped(mode, true)
                    val sequence = header ushr 8
                    if ((sequence and 15) == 0 || (sequence ushr 4) > (sequence and 15)) return stopped(mode, false)
                    structured = true
                }
                5, 9 -> {
                    if (fnc1 != null || sawEci) return stopped(mode, false)
                    fnc1 = mode
                    if (mode == 9) application = readControl(8) ?: return stopped(mode, true)
                }
                7 -> {
                    val first = readControl(8) ?: return stopped(mode, true)
                    eci = when {
                        first and 0x80 == 0 -> first and 0x7F
                        first and 0xC0 == 0x80 -> ((first and 0x3F) shl 8) or (readControl(8) ?: return stopped(mode, true))
                        first and 0xE0 == 0xC0 -> ((first and 0x1F) shl 16) or (readControl(16) ?: return stopped(mode, true))
                        else -> return stopped(mode, false)
                    }
                    if (eci > 999999) return stopped(mode, false)
                    sawEci = true
                }
                else -> { offset = start; break }
            }
        }
        var report = analyzePayload(bits.drop(offset), version)
        if (fnc1 != null && report.mode == 2) {
            val encoded = report.knownTextPrefix
            val converted = StringBuilder()
            var index = 0
            while (index < encoded.length) {
                val ch = encoded[index++]
                if (ch != '%') converted.append(ch)
                else if (index < encoded.length && encoded[index] == '%') { converted.append('%'); index++ }
                else if (index != encoded.length || (!report.stoppedAtUnknownBit && !report.stoppedAtInvalidValue)) converted.append('\u001D')
            }
            report = report.copy(knownTextPrefix = converted.toString(), knownEncodedCharacters = encoded.length)
        }
        if (eci != null && report.mode == 4 && report.knownBytePrefix.isNotEmpty()) {
            val converted = decodeEciPrefix(report.knownBytePrefix, eci, !report.stoppedAtUnknownBit)
            report = report.copy(knownTextPrefix = converted.orEmpty(), textUnavailable = converted == null)
        }
        return report.copy(eci = eci, fnc1Mode = fnc1, fnc1ApplicationIndicator = application)
    }

    private fun decodeEciPrefix(bytes: List<Int>, eci: Int, complete: Boolean): String? = runCatching {
        val charset = com.google.zxing.common.CharacterSetECI.getCharacterSetECIByValue(eci)?.charset ?: return null
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = java.nio.ByteBuffer.wrap(bytes.map { it.toByte() }.toByteArray())
        val output = java.nio.CharBuffer.allocate((bytes.size * decoder.maxCharsPerByte()).toInt() + 1)
        val result = decoder.decode(input, output, complete)
        if (result.isError) result.throwException()
        output.flip()
        output.toString()
    }.getOrNull()

    private fun analyzePayload(bits: List<Int?>, version: Int): PartialPayloadReport {
        var position = 0
        fun read(width: Int): Int? {
            if (position + width > bits.size) return null
            var value = 0
            repeat(width) {
                val bit = bits[position++] ?: return null
                value = (value shl 1) or bit
            }
            return value
        }

        val mode = read(4) ?: return PartialPayloadReport(null, null, false, emptyList(), "", true)
        // Hanzi inserts a four-bit subset indicator before the version-dependent count.
        val hanziSubset = if (mode == 13) {
            val subset = read(4) ?: return PartialPayloadReport(mode, null, false, emptyList(), "", true)
            if (subset != 1) return PartialPayloadReport(mode, null, false, emptyList(), "", false,
                stoppedAtInvalidValue = true, hanziSubset = subset)
            subset
        } else null
        val countWidth = countBits(mode, version)
        if (countWidth == null) {
            val note = if (mode in setOf(0, 3, 5, 7, 9)) null else "Mode 0x${mode.toString(16).uppercase()} is not interpreted by this diagnostic view."
            return PartialPayloadReport(mode, null, false, emptyList(), "", false, note = note)
        }
        val count = read(countWidth) ?: return PartialPayloadReport(mode, null, true, emptyList(), "", true,
            hanziSubset = hanziSubset)
        if (mode == 13) return PartialPayloadReport(mode, count, true, emptyList(), "", false,
            hanziSubset = hanziSubset)

        fun readAtomic(width: Int): Int? {
            val before = position
            return read(width).also { if (it == null) position = before }
        }

        if (mode in setOf(1, 2, 8)) {
            val text = StringBuilder()
            var remaining = count
            var unknown = false
            var invalid = false
            fun take(width: Int, valid: (Int) -> Boolean, render: (Int) -> String): Boolean {
                val value = readAtomic(width)
                if (value == null) {
                    unknown = true
                    return false
                }
                if (!valid(value)) {
                    invalid = true
                    return false
                }
                text.append(render(value))
                return true
            }
            when (mode) {
                1 -> {
                    while (remaining >= 3 && !unknown && !invalid) {
                        if (take(10, { it < 1000 }, { it.toString().padStart(3, '0') })) remaining -= 3
                    }
                    if (!unknown && !invalid && remaining == 2) take(7, { it < 100 }, { it.toString().padStart(2, '0') })
                    if (!unknown && !invalid && remaining == 1) take(4, { it < 10 }, { it.toString() })
                }
                2 -> {
                    val table = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
                    while (remaining >= 2 && !unknown && !invalid) {
                        if (take(11, { it < 45 * 45 }, { "${table[it / 45]}${table[it % 45]}" })) remaining -= 2
                    }
                    if (!unknown && !invalid && remaining == 1) take(6, { it < 45 }, { table[it].toString() })
                }
                8 -> repeat(count) {
                    if (!unknown && !invalid) take(13, { qrKanji(it) != null }, { qrKanji(it)!! })
                }
            }
            return PartialPayloadReport(mode, count, true, emptyList(), text.toString(), unknown, invalid)
        }
        if (mode != 4) return PartialPayloadReport(mode, count, true, emptyList(), "", false)

        val prefix = mutableListOf<Int>()
        var stoppedAtUnknown = false
        val availablePayloadBytes = ((bits.size - position).coerceAtLeast(0) + 7) / 8
        repeat(minOf(count, availablePayloadBytes)) {
            if (stoppedAtUnknown) return@repeat
            val before = position
            val value = read(8)
            if (value == null) {
                position = before
                stoppedAtUnknown = true
                return@repeat
            }
            prefix += value
        }
        if (prefix.size < count) stoppedAtUnknown = true
        return PartialPayloadReport(mode, count, true, prefix, "", stoppedAtUnknown)
    }

    private fun countBits(mode: Int, version: Int): Int? {
        val group = when (version) { in 1..9 -> 0; in 10..26 -> 1; else -> 2 }
        return when (mode) {
            1 -> intArrayOf(10, 12, 14)[group]
            2 -> intArrayOf(9, 11, 13)[group]
            4 -> intArrayOf(8, 16, 16)[group]
            8, 13 -> intArrayOf(8, 10, 12)[group]
            else -> null
        }
    }

    private fun qrKanji(value: Int): String? {
        if (value !in 0 until (1 shl 13)) return null
        val assembled = (value / 0xC0 shl 8) or (value % 0xC0)
        val shiftJis = assembled + if (assembled < 0x1F00) 0x8140 else 0xC140
        if (shiftJis !in 0..0xFFFF) return null
        return runCatching {
            val decoder = java.nio.charset.Charset.forName("Shift_JIS").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(byteArrayOf((shiftJis ushr 8).toByte(), shiftJis.toByte()))).toString()
                .takeIf { it.isNotEmpty() && '\uFFFD' !in it }
        }.getOrNull()
    }
}
