package io.github.earthdiver.qrrepair.core

import com.google.zxing.DecodeHintType
import com.google.zxing.common.CharacterSetECI
import com.google.zxing.common.StringUtils
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** Reassembles interpretation runs while preserving the original QR compaction segments. */
object QrPayloadTextDecoder {
    @JvmStatic
    fun decode(data: ByteArray, version: Int, hints: Map<DecodeHintType, *>?): String? {
        val assumeUtf8 = hints?.get(DecodeHintType.CHARACTER_SET)?.toString()?.equals("UTF-8", ignoreCase = true) == true
        val inspection = StrictQrPayloadValidator.inspectSegments(data, version, IntArray(data.size), data.size * 8)
        if (!inspection.valid) return null
        if (!assumeUtf8 && inspection.segments.none { it.mode == 7 }) return null
        val result = StringBuilder()
        val pending = ByteArrayOutputStream()
        var pendingCharset = Charsets.UTF_8
        var eci: Int? = null
        var explicitCharset: Charset? = null
        var fnc1 = false
        fun flush() {
            if (pending.size() > 0) result.append(String(pending.toByteArray(), pendingCharset))
            pending.reset()
        }
        fun append(bytes: ByteArray, charset: Charset, join: Boolean) {
            if (bytes.isEmpty()) return
            if (!join) {
                flush()
                result.append(String(bytes, charset))
            } else {
                if (pendingCharset != charset) flush()
                pendingCharset = charset
                pending.write(bytes)
            }
        }
        for (segment in inspection.segments) {
            when (segment.mode) {
                5, 9 -> fnc1 = true
                7 -> {
                    val next = requireNotNull(segment.eci)
                    val charset = try { CharacterSetECI.getCharacterSetECIByValue(next)?.charset }
                        catch (_: com.google.zxing.FormatException) { null }
                        catch (_: IllegalArgumentException) { null }
                    if (charset == null) return null
                    if (eci != next && !(eci == null && assumeUtf8 && next == 26)) flush()
                    eci = next
                    explicitCharset = charset
                }
                1, 2, 4, 8, 13 -> {
                    val mode = requireNotNull(segment.mode)
                    val count = requireNotNull(segment.count)
                    var position = segment.endBit - requireNotNull(segment.requiredBits)
                    fun read(width: Int): Int {
                        var value = 0
                        repeat(width) {
                            value = (value shl 1) or ((data[position / 8].toInt() ushr (7 - position % 8)) and 1)
                            position++
                        }
                        return value
                    }
                    val bytes = when (mode) {
                        1 -> buildString {
                            var remaining = count
                            while (remaining > 0) {
                                val digits = minOf(3, remaining)
                                append(read(1 + 3 * digits).toString().padStart(digits, '0'))
                                remaining -= digits
                            }
                        }.toByteArray(Charsets.US_ASCII)
                        2 -> {
                            val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
                            val encoded = buildString {
                                repeat(count / 2) {
                                    val pair = read(11)
                                    append(alphabet[pair / 45]); append(alphabet[pair % 45])
                                }
                                if (count % 2 != 0) append(alphabet[read(6)])
                            }
                            val text = if (!fnc1) encoded else buildString {
                                var i = 0
                                while (i < encoded.length) {
                                    val ch = encoded[i++]
                                    if (ch != '%') append(ch)
                                    else if (i < encoded.length && encoded[i] == '%') { append('%'); i++ }
                                    else append('\u001d')
                                }
                            }
                            text.toByteArray(Charsets.US_ASCII)
                        }
                        4 -> ByteArray(count) { read(8).toByte() }
                        else -> ByteArray(count * 2).also { output ->
                            repeat(count) { index ->
                                val value = read(13)
                                val divisor = if (mode == 8) 0xc0 else 0x60
                                val assembled = ((value / divisor) shl 8) or (value % divisor)
                                val code = assembled + if (mode == 8) {
                                    if (assembled < 0x1f00) 0x8140 else 0xc140
                                } else if (assembled < 0xa00) 0xa1a1 else 0xa6a1
                                output[index * 2] = (code ushr 8).toByte()
                                output[index * 2 + 1] = code.toByte()
                            }
                        }
                    }
                    val charset = explicitCharset ?: when (mode) {
                        4 -> if (assumeUtf8) Charsets.UTF_8 else StringUtils.guessCharset(bytes, hints)
                        8 -> Charset.forName("Shift_JIS")
                        13 -> Charset.forName("GB2312")
                        else -> Charsets.ISO_8859_1
                    }
                    append(bytes, charset, explicitCharset != null || (mode == 4 && assumeUtf8))
                }
            }
        }
        flush()
        return result.toString()
    }
}
