package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText
import java.net.URI

enum class QrContentKind {
    URL,
    VCARD,
    EMAIL,
    PHONE,
    SMS,
    GEO,
    TEXT,
    BINARY,
}

data class QrContentField(val label: String, val value: String)

data class QrContentPreview(
    val kind: QrContentKind,
    val title: String,
    val summary: String,
    val fields: List<QrContentField>,
    val contentFormatValid: Boolean,
    val utf8Like: Boolean,
    val structuredAppend: StructuredAppendInfo? = null,
    val fnc1ApplicationIndicator: Int? = null,
    val encodingModes: List<Int>? = null,
    val textRepresentation: Boolean = false,
    val qrStructureValid: Boolean = false,
    val byteSegments: List<Pair<Int, ByteArray>> = emptyList(),
    val recoveredText: String? = null,
) {
    fun toDisplayText(): String = buildString {
        append(if (qrStructureValid) localizedText("表示中の候補は、QRのデータ形式として読み取れました。", "The displayed candidate was read as QR-formatted data.")
            else localizedText("QRのデータ構造を確認できませんでした。", "The QR data structure could not be verified."))
        append('\n')
        if (textRepresentation && !contentFormatValid) append(localizedText(
            "${title}として必要な情報が不足しているか、形式に不備があります。\n",
            "Required information is missing or malformed for ${title}.\n",
        ))
        encodingModes?.takeIf { it.isNotEmpty() }?.let { modes ->
            append(localizedText("符号化モード: ", "Encoding mode: "))
            if (modes.size > 1) append(localizedText("複数セグメント — ", "Multiple segments — "))
            append(modes.joinToString(" → ") { mode -> when (mode) {
                1 -> localizedText("数字", "Numeric")
                2 -> localizedText("英数字", "Alphanumeric")
                4 -> localizedText("バイト", "Byte")
                8 -> localizedText("漢字", "Kanji")
                13 -> localizedText("中国語漢字（Hanzi）", "Hanzi")
                else -> localizedText("不明", "Unknown")
            } }).append('\n')
        }
        if (byteSegments.isNotEmpty()) {
            append('\n').append(CodewordHex.payloadByteSegments(byteSegments))
                .append("\n\n")
            if (textRepresentation) append(localizedText(
                "参考: 文字列変換（文字コードによっては正しく表示されません）\n",
                "Reference: text conversion (may display incorrectly depending on the encoding)\n",
            ))
        }
        append(if (textRepresentation && byteSegments.isNotEmpty()) localizedText("内容プレビュー（文字列に変換した表示）: ", "Content preview (converted to text): ")
            else localizedText("内容プレビュー: ", "Content preview: ")).append(title).append('\n')
        structuredAppend?.let { append(it.description()).append('\n') }
        fnc1ApplicationIndicator?.let {
            append(localizedText("FNC1アプリケーション識別子: ", "FNC1 application indicator: "))
                .append(it).append('\n')
        }
        if (summary.isNotBlank()) append(summary).append('\n')
        for (field in fields) {
            val shown = field.value
            append(field.label).append(": ").append(shown).append('\n')
        }
        recoveredText?.takeIf { kind != QrContentKind.TEXT && it != summary }?.let { raw ->
            append(localizedText("\n復元した文字列（原文）\n", "\nRecovered text (original)\n"))
            append(raw).append('\n')
        }
        if (!textRepresentation) append(localizedText("\n内容はバイナリまたは未解釈データです。", "\nThe content is binary or uninterpreted data."))
    }.trimEnd()
}

object QrContentPreviewParser {
    fun parse(candidate: RecoveryCandidate?): QrContentPreview? = candidate?.let {
        parseBody(it.text, it.dataBytes.size).copy(structuredAppend = it.structuredAppend,
            fnc1ApplicationIndicator = it.fnc1ApplicationIndicator,
            encodingModes = StrictQrPayloadValidator.encodingModes(it.dataBytes, it.version),
            textRepresentation = it.text != null,
            qrStructureValid = StrictQrPayloadValidator.isValid(it.dataBytes, it.version),
            byteSegments = StrictQrPayloadValidator.byteSegments(it.dataBytes, it.version).orEmpty(),
            recoveredText = it.text)
    }

    /** A validated prefix is diagnostic evidence, never an accepted recovery candidate. */
    internal fun parseUnverifiedPrefix(data: ByteArray, version: Int,
        ecc: com.google.zxing.qrcode.decoder.ErrorCorrectionLevel): QrContentPreview? {
        val prefix = StrictQrPayloadValidator.inspectPrefix(data, version) ?: return null
        if (prefix.terminatorBit == null || prefix.modes.isEmpty()) return null
        val decoded = try { com.google.zxing.qrcode.decoder.ZxingPayloadBridge.decodeData(data, version, ecc, null) }
            catch (_: com.google.zxing.FormatException) { null }
            catch (_: IllegalArgumentException) { null }
        val text = decoded?.text
        return parseBody(text, prefix.byteSegments.sumOf { it.second.size }).copy(
            encodingModes = prefix.modes, textRepresentation = text != null,
            qrStructureValid = false, byteSegments = prefix.byteSegments, recoveredText = text,
            structuredAppend = decoded?.takeIf { it.hasStructuredAppend() }?.let {
                StructuredAppendInfo(it.structuredAppendSequenceNumber ushr 4,
                    (it.structuredAppendSequenceNumber and 15) + 1, it.structuredAppendParity)
            },
            fnc1ApplicationIndicator = if (decoded != null)
                com.google.zxing.qrcode.decoder.ZxingPayloadBridge.fnc1ApplicationIndicator(data) else null)
    }

    private fun parseBody(text: String?, dataSize: Int): QrContentPreview {
        if (text == null) {
            return QrContentPreview(
                QrContentKind.BINARY,
                localizedText("バイナリ/未解釈データ", "Binary/unparsed data"),
                localizedText("内容を文字列として表示できませんでした。HEX／Binary表示で確認できます。", "The content could not be displayed as text. Check the Hex / Binary view."),
                listOf(QrContentField(localizedText("データバイト数", "Data bytes"), dataSize.toString())),
                contentFormatValid = false,
                utf8Like = false,
            )
        }
        // A recovery preview must not silently change payload bytes represented as text. Leading
        // and trailing whitespace can be meaningful and must also prevent structured reclassification.
        val t = text
        parseVCard(t)?.let { return it }
        parseMail(t)?.let { return it }
        parseSms(t)?.let { return it }
        parsePhone(t)?.let { return it }
        parseGeo(t)?.let { return it }
        parseUrl(t)?.let { return it }
        return QrContentPreview(
            QrContentKind.TEXT,
            localizedText("テキスト", "Text"),
            t,
            listOf(QrContentField(localizedText("文字数", "Characters"), text.length.toString())),
            contentFormatValid = true,
            utf8Like = true,
        )
    }

    private fun parseUrl(t: String): QrContentPreview? {
        val uri = try { URI(t) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https")) return null
        val host = uri.host ?: return null
        return QrContentPreview(
            QrContentKind.URL,
            "URL",
            t,
            listOf(
                QrContentField(localizedText("スキーム", "Scheme"), scheme),
                QrContentField(localizedText("ホスト", "Host"), host),
            ),
            contentFormatValid = true,
            utf8Like = true,
        )
    }

    private fun parseVCard(t: String): QrContentPreview? {
        if (!t.startsWith("BEGIN:VCARD", ignoreCase = true)) return null
        val lines = t.lines()
        fun first(prefix: String): String? = lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) }?.substringAfter(':')
        val name = first("FN:") ?: first("N:").orEmpty()
        val tel = lines.firstOrNull { it.startsWith("TEL", ignoreCase = true) }?.substringAfter(':')
        val email = lines.firstOrNull { it.startsWith("EMAIL", ignoreCase = true) }?.substringAfter(':')
        return QrContentPreview(
            QrContentKind.VCARD,
            "vCard",
            name.ifBlank { localizedText("連絡先", "Contact") },
            buildList {
                if (name.isNotBlank()) add(QrContentField(localizedText("氏名", "Name"), name))
                if (!tel.isNullOrBlank()) add(QrContentField(localizedText("電話番号", "Phone"), tel))
                if (!email.isNullOrBlank()) add(QrContentField(localizedText("メールアドレス", "Email"), email))
            },
            contentFormatValid = lines.any { it.equals("END:VCARD", ignoreCase = true) },
            utf8Like = true,
        )
    }

    private fun parseMail(t: String): QrContentPreview? {
        if (!t.startsWith("mailto:", ignoreCase = true) && !t.startsWith("MATMSG:", ignoreCase = true)) return null
        val address = if (t.startsWith("mailto:", ignoreCase = true)) t.substringAfter(':').substringBefore('?')
        else Regex("TO:([^;]+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1).orEmpty()
        return QrContentPreview(
            QrContentKind.EMAIL,
            localizedText("メール", "Email"),
            address,
            listOf(QrContentField(localizedText("宛先", "To"), address)),
            contentFormatValid = address.contains('@'),
            utf8Like = true,
        )
    }

    private fun parsePhone(t: String): QrContentPreview? {
        if (!t.startsWith("tel:", ignoreCase = true)) return null
        val number = t.substringAfter(':')
        return QrContentPreview(QrContentKind.PHONE, localizedText("電話番号", "Phone number"), number, listOf(QrContentField(localizedText("番号", "Number"), number)), number.isNotBlank(), true)
    }

    private fun parseSms(t: String): QrContentPreview? {
        if (!t.startsWith("sms:", ignoreCase = true) && !t.startsWith("smsto:", ignoreCase = true)) return null
        val body = t.substringAfter(':')
        val number = body.substringBefore(':').substringBefore('?')
        return QrContentPreview(QrContentKind.SMS, "SMS", number, listOf(QrContentField(localizedText("宛先", "To"), number)), number.isNotBlank(), true)
    }

    private fun parseGeo(t: String): QrContentPreview? {
        if (!t.startsWith("geo:", ignoreCase = true)) return null
        val coords = t.substringAfter(':').substringBefore('?')
        return QrContentPreview(QrContentKind.GEO, localizedText("位置情報", "Location"), coords, listOf(QrContentField(localizedText("座標", "Coordinates"), coords)), coords.contains(','), true)
    }

}
