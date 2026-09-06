package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText
import java.net.URI

enum class QrContentKind {
    URL,
    WIFI,
    VCARD,
    EMAIL,
    PHONE,
    SMS,
    GEO,
    TEXT,
    BINARY,
}

data class QrContentField(val label: String, val value: String, val sensitive: Boolean = false)

data class QrContentPreview(
    val kind: QrContentKind,
    val title: String,
    val summary: String,
    val fields: List<QrContentField>,
    val payloadValid: Boolean,
    val utf8Like: Boolean,
    val structuredAppend: StructuredAppendInfo? = null,
    val fnc1ApplicationIndicator: Int? = null,
) {
    fun toDisplayText(maskSensitive: Boolean = true): String = buildString {
        append(localizedText("内容プレビュー: ", "Content preview: ")).append(title).append('\n')
        structuredAppend?.let { append(it.description()).append('\n') }
        fnc1ApplicationIndicator?.let {
            append(localizedText("FNC1アプリケーション識別子: ", "FNC1 application indicator: "))
                .append(it).append('\n')
        }
        if (summary.isNotBlank()) append(summary).append('\n')
        for (field in fields) {
            val shown = if (maskSensitive && field.sensitive) "••••••••" else field.value
            append(field.label).append(": ").append(shown).append('\n')
        }
        append(localizedText("QRデータ構造: ", "QR data structure: "))
            .append(if (payloadValid) localizedText("適合", "valid") else localizedText("要確認", "check"))
        append(localizedText("\nテキスト: ", "\nText: "))
            .append(if (utf8Like) localizedText("適合", "valid") else localizedText("バイナリまたは不明", "binary or unknown"))
    }.trimEnd()
}

object QrContentPreviewParser {
    fun parse(candidate: RecoveryCandidate?): QrContentPreview? = candidate?.let {
        parseBody(it).copy(structuredAppend = it.structuredAppend,
            fnc1ApplicationIndicator = it.fnc1ApplicationIndicator)
    }

    private fun parseBody(candidate: RecoveryCandidate): QrContentPreview {
        val text = candidate.text
        if (text == null) {
            return QrContentPreview(
                QrContentKind.BINARY,
                localizedText("バイナリ/未解釈データ", "Binary/unparsed data"),
                localizedText("RS整合候補ですが、ZXingでテキストpayloadとして解釈できません。", "The candidate is RS-consistent but ZXing cannot interpret it as a text payload."),
                listOf(QrContentField(localizedText("データバイト数", "Data bytes"), candidate.dataBytes.size.toString())),
                payloadValid = false,
                utf8Like = false,
            )
        }
        // A recovery preview must not silently change payload bytes represented as text. Leading
        // and trailing whitespace can be meaningful and must also prevent structured reclassification.
        val t = text
        parseWifi(t)?.let { return it }
        parseVCard(t)?.let { return it }
        parseMail(t)?.let { return it }
        parseSms(t)?.let { return it }
        parsePhone(t)?.let { return it }
        parseGeo(t)?.let { return it }
        parseUrl(t)?.let { return it }
        return QrContentPreview(
            QrContentKind.TEXT,
            localizedText("テキスト", "Text"),
            t.take(220),
            listOf(QrContentField(localizedText("文字数", "Characters"), text.length.toString())),
            payloadValid = true,
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
            payloadValid = true,
            utf8Like = true,
        )
    }

    private fun parseWifi(t: String): QrContentPreview? {
        if (!t.startsWith("WIFI:", ignoreCase = true)) return null
        val body = t.substringAfter(':').removeSuffix(";")
        val fields = parseEscapedFields(body)
        val ssid = fields["S"].orEmpty()
        val auth = fields["T"].orEmpty().ifBlank { "nopass" }
        val pass = fields["P"].orEmpty()
        val hidden = fields["H"].orEmpty()
        return QrContentPreview(
            QrContentKind.WIFI,
            "Wi-Fi",
            if (ssid.isBlank()) localizedText("SSID未取得", "SSID unavailable") else ssid,
            buildList {
                add(QrContentField("SSID", ssid))
                add(QrContentField(localizedText("セキュリティ", "Security"), auth))
                if (pass.isNotEmpty()) add(QrContentField(localizedText("パスワード", "Password"), pass, sensitive = true))
                if (hidden.isNotEmpty()) add(QrContentField(localizedText("非表示", "Hidden"), hidden))
            },
            payloadValid = ssid.isNotBlank(),
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
            payloadValid = lines.any { it.equals("END:VCARD", ignoreCase = true) },
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
            payloadValid = address.contains('@'),
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

    private fun parseEscapedFields(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val token = StringBuilder()
        val parts = ArrayList<String>()
        var escaped = false
        for (ch in body) {
            when {
                escaped -> { token.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == ';' -> { parts += token.toString(); token.setLength(0) }
                else -> token.append(ch)
            }
        }
        if (token.isNotEmpty()) parts += token.toString()
        for (part in parts) {
            val i = part.indexOf(':')
            if (i > 0) out[part.substring(0, i)] = part.substring(i + 1)
        }
        return out
    }
}
