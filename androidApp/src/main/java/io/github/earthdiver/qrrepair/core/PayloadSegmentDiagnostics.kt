package io.github.earthdiver.qrrepair.core

import com.google.zxing.common.CharacterSetECI
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.ZxingPayloadBridge
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Interpretation of completed diagnostic segments; never used to accept recovery candidates. */
internal object PayloadSegmentDiagnostics {
    enum class Status { PARSED, UNSUPPORTED_ECI, UNKNOWN_ENCODING, INVALID_TEXT, FAILED }
    data class Entry(
        val structure: StrictQrPayloadValidator.SegmentTrace,
        val status: Status,
        val charsetName: String? = null,
        val text: String? = null,
        val bytes: ByteArray? = null,
    ) {
        fun shifted(offset: Int) = copy(structure = structure.copy(
            startBit = structure.startBit + offset, endBit = structure.endBit + offset,
            problemStartBit = structure.problemStartBit?.plus(offset), problemEndBit = structure.problemEndBit?.plus(offset)))
    }

    fun interpret(bits: List<Int?>, version: Int, ecc: ErrorCorrectionLevel,
        segments: List<StrictQrPayloadValidator.SegmentTrace>): List<Entry> {
        var eci: Int? = null
        var charset: java.nio.charset.Charset? = null
        var fnc1 = emptyList<Int?>()
        return segments.map { segment ->
            val mode = segment.mode
            val raw = bits.subList(segment.startBit, segment.endBit)
            when {
                segment.issue != null -> Entry(segment, Status.FAILED)
                mode == 7 -> {
                    eci = segment.eci
                    charset = try { CharacterSetECI.getCharacterSetECIByValue(eci!!)?.charset }
                    catch (_: com.google.zxing.FormatException) { null }
                    catch (_: IllegalArgumentException) { null }
                    Entry(segment, if (charset == null) Status.UNSUPPORTED_ECI else Status.PARSED, charset?.name())
                }
                mode == 5 || mode == 9 -> { fnc1 = raw; Entry(segment, Status.PARSED) }
                mode in listOf(1, 2, 4, 8, 13) -> {
                    val bytes = if (mode == 4) pack(raw.takeLast(segment.count!! * 8)) else null
                    val unknownEncoding = mode == 4 && eci != null && charset == null && bytes!!.isNotEmpty()
                    val text = if (unknownEncoding) null else try {
                        if (bytes != null && charset != null) charset.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString()
                        else ZxingPayloadBridge.decodeData(pack(fnc1 + raw + listOf(0, 0, 0, 0)), version, ecc, null).text
                    } catch (_: com.google.zxing.FormatException) { null }
                    catch (_: IllegalArgumentException) { null }
                    catch (_: CharacterCodingException) { null }
                    Entry(segment, when {
                        unknownEncoding -> Status.UNKNOWN_ENCODING
                        text == null -> Status.INVALID_TEXT
                        else -> Status.PARSED
                    }, if (mode == 4) charset?.name() else null, text, bytes)
                }
                else -> Entry(segment, Status.PARSED)
            }
        }
    }

    private fun pack(bits: List<Int?>): ByteArray = ByteArray((bits.size + 7) / 8) { index ->
        (0..7).fold(0) { value, bit -> (value shl 1) or (bits.getOrNull(index * 8 + bit) ?: 0) }.toByte()
    }
}
