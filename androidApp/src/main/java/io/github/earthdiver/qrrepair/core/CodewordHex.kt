package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

object CodewordHex {
    fun masked(matrix: QrMatrix, extraction: CodewordExtraction): String = format(
        extraction.codewords.map { codeword ->
            var value = 0
            var unknown = 0
            codeword.coordinates.forEach { coord ->
                value = value shl 1
                unknown = unknown shl 1
                when (matrix[coord.x, coord.y].value) {
                    ModuleValue.ONE -> value = value or 1
                    ModuleValue.ERASED -> unknown = unknown or 1
                    ModuleValue.ZERO -> Unit
                }
            }
            PartialCodeword(value and 0xFF, unknown and 0xFF, codeword.coordinates)
        },
    )

    fun unmasked(extraction: CodewordExtraction): String = format(extraction.codewords)

    fun corrected(values: IntArray): String = format(values.map { PartialCodeword(it and 0xFF, 0, emptyList()) })

    fun correctedMasked(values: IntArray, extraction: CodewordExtraction, mask: Int): String =
        format(remasked(values, extraction, mask).codewords)

    fun correctedBinary(values: IntArray): String = binary(CodewordExtraction(
        values.map { PartialCodeword(it and 0xFF, 0, emptyList()) },
    ))

    fun correctedMaskedBinary(values: IntArray, extraction: CodewordExtraction, mask: Int): String =
        binary(remasked(values, extraction, mask))

    /**
     * Displays recovered payload content without attempting to decode its bytes a second time.
     *
     * [dataBytes] are deinterleaved QR data codewords. [decodedText], when present, is the text
     * already validated by ZXing, including ECI, Kanji mode, and charset handling.
     */
    fun interpretedRecovered(dataBytes: ByteArray, version: Int, decodedText: String?): String {
        val extraction = CodewordExtraction(
            dataBytes.map { PartialCodeword(it.toInt() and 0xFF, 0, emptyList()) },
        )
        if (decodedText == null) return interpreted(extraction, version)
        val firstMode = dataBytes.firstOrNull()?.toInt()?.ushr(4)?.and(0x0F)
        val mode = firstMode?.let(::modeName) ?: localizedText("不明", "unknown")
        return localizedText(
            "モード: $mode / QR復号済み\n$decodedText",
            "Mode: $mode / decoded QR payload\n$decodedText",
        )
    }

    private fun remasked(values: IntArray, extraction: CodewordExtraction, mask: Int): CodewordExtraction =
        extraction.copy(codewords = extraction.codewords.mapIndexed { index, original ->
            val corrected = values.getOrElse(index) { original.value } and 0xFF
            var observed = 0
            original.coordinates.forEachIndexed { bitIndex, coord ->
                val unmasked = (corrected shr (7 - bitIndex)) and 1
                val bit = unmasked xor if (CodewordExtractor.maskApplies(mask, coord.x, coord.y)) 1 else 0
                observed = (observed shl 1) or bit
            }
            PartialCodeword(observed and 0xFF, 0, original.coordinates)
        })

    fun binary(extraction: CodewordExtraction): String = extraction.codewords.chunked(6).mapIndexed { row, bytes ->
        "%04X  ".format(row * 6) + bytes.joinToString(" ") { byte ->
            (7 downTo 0).joinToString("") { bit ->
                when {
                    byte.unknownBits and (1 shl bit) != 0 -> "?"
                    byte.value and (1 shl bit) != 0 -> "1"
                    else -> "0"
                }
            }
        }
    }.joinToString("\n")

    fun maskedBinary(matrix: QrMatrix, extraction: CodewordExtraction): String = binary(
        extraction.copy(codewords = extraction.codewords.map { codeword ->
            var value = 0
            var unknown = 0
            codeword.coordinates.forEach { coord ->
                value = value shl 1; unknown = unknown shl 1
                when (matrix[coord.x, coord.y].value) {
                    ModuleValue.ONE -> value = value or 1
                    ModuleValue.ERASED -> unknown = unknown or 1
                    ModuleValue.ZERO -> Unit
                }
            }
            PartialCodeword(value and 0xFF, unknown and 0xFF, codeword.coordinates)
        }),
    )

    /** Best-effort view of the leading QR segment before RS correction. */
    fun interpreted(extraction: CodewordExtraction, version: Int): String {
        val bits = extraction.codewords.flatMap { byte ->
            (7 downTo 0).map { bit -> if (byte.unknownBits and (1 shl bit) != 0) null else (byte.value shr bit) and 1 }
        }
        var position = 0
        fun read(count: Int): Int? {
            if (position + count > bits.size) return null
            var value = 0
            repeat(count) { value = (value shl 1) or (bits[position++] ?: return null) }
            return value
        }
        val mode = read(4) ?: return localizedText("モード: 不明（先頭bitに ? があります）", "Mode: unknown (? in leading bits)")
        val countBits = when (mode) {
            1 -> if (version <= 9) 10 else if (version <= 26) 12 else 14
            2 -> if (version <= 9) 9 else if (version <= 26) 11 else 13
            4 -> if (version <= 9) 8 else 16
            else -> 0
        }
        if (mode == 0) return localizedText("モード: 終端", "Mode: terminator")
        if (countBits == 0) return localizedText("モード: ${modeName(mode)}（この簡易表示では未対応）", "Mode: ${modeName(mode)} (not supported by this preview)")
        val count = read(countBits) ?: return localizedText("モード: ${modeName(mode)} / 文字数: 不明", "Mode: ${modeName(mode)} / count: unknown")
        val content = when (mode) {
            1 -> buildString {
                repeat(count / 3) { val v = read(10) ?: return@buildString; append("%03d".format(v)) }
                if (count % 3 == 2) read(7)?.let { append("%02d".format(it)) }
                if (count % 3 == 1) read(4)?.let(::append)
            }
            2 -> buildString {
                val table = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
                repeat(count / 2) { val v = read(11) ?: return@buildString; append(table.getOrElse(v / 45) { '?' }); append(table.getOrElse(v % 45) { '?' }) }
                if (count % 2 == 1) read(6)?.let { append(table.getOrElse(it) { '?' }) }
            }
            else -> ByteArray(count) { read(8)?.toByte() ?: 0 }.toString(Charsets.UTF_8)
        }
        return localizedText("モード: ${modeName(mode)} / 長さ: $count\n$content", "Mode: ${modeName(mode)} / length: $count\n$content")
    }

    private fun modeName(mode: Int): String = when (mode) {
        1 -> localizedText("数字", "numeric"); 2 -> localizedText("英数字", "alphanumeric"); 4 -> localizedText("バイト", "byte"); 8 -> localizedText("漢字", "Kanji"); 7 -> "ECI"; else -> "0x${mode.toString(16)}"
    }

    private fun format(codewords: List<PartialCodeword>): String = codewords.chunked(16).mapIndexed { row, bytes ->
        "%04X  ".format(row * 16) + bytes.joinToString(" ") {
            if (it.unknownBits != 0) "??" else "%02X".format(it.value and 0xFF)
        }
    }.joinToString("\n")
}
