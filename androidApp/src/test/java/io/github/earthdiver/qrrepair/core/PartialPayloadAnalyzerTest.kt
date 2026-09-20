package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialPayloadAnalyzerTest {
    @Test fun reportsKnownBytePrefixAndStopsAtFirstUnknownByte() {
        // Byte mode, count 3, then A, one unknown byte, C.
        val bits = "0100" + "00000011" + "01000001" + "????????" + "01000011"
        val report = PartialPayloadAnalyzer.analyze(partials(bits), version = 1)

        assertEquals(4, report.mode)
        assertEquals(3, report.count)
        assertEquals(listOf(0x41), report.knownBytePrefix)
        assertTrue(report.stoppedAtUnknownBit)
        assertTrue(report.displayText(japanese = true).contains("HEX: 41"))
        assertTrue(!report.displayText(japanese = true).contains("43"))
    }

    @Test fun doesNotGuessModeOrCountContainingUnknownBits() {
        assertEquals(null, PartialPayloadAnalyzer.analyze(partials("01?0"), 1).mode)
        val countUnknown = PartialPayloadAnalyzer.analyze(partials("010000000?01"), 1)
        assertEquals(4, countUnknown.mode)
        assertEquals(null, countUnknown.count)
    }

    @Test fun reportsKnownNumericPrefixAndStopsBeforeUnknownGroup() {
        val report = PartialPayloadAnalyzer.analyze(partials(
            "0001" + binary(5, 10) + binary(123, 10) + "???????" + binary(9, 8),
        ), 1)
        assertEquals(1, report.mode)
        assertEquals(5, report.count)
        assertEquals("123", report.knownTextPrefix)
        assertTrue(report.stoppedAtUnknownBit)
        assertTrue(report.knownBytePrefix.isEmpty())
        assertTrue(!report.displayText(japanese = true).contains("09"))
    }

    @Test fun reportsKnownAlphanumericPrefix() {
        val table = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
        val ab = table.indexOf('A') * 45 + table.indexOf('B')
        val report = PartialPayloadAnalyzer.analyze(partials(
            "0010" + binary(3, 9) + binary(ab, 11) + binary(table.indexOf('1'), 6),
        ), 1)

        assertEquals("AB1", report.knownTextPrefix)
        assertTrue(!report.stoppedAtUnknownBit)
    }

    @Test fun decodesQrKanjiPrefixFromThirteenBitValues() {
        // Shift_JIS 0x8ABF (漢) -> QR Kanji value ((0x8ABF - 0x8140) high * 0xC0 + low).
        val report = PartialPayloadAnalyzer.analyze(partials(
            "1000" + binary(1, 8) + binary(0x73F, 13),
        ), 1)

        assertEquals("漢", report.knownTextPrefix)
        assertTrue(report.displayText(japanese = true).contains("Text: 漢"))
    }

    @Test fun rejectsOutOfRangeNumericGroupInsteadOfRenderingIt() {
        val report = PartialPayloadAnalyzer.analyze(partials(
            "0001" + binary(3, 10) + binary(1000, 10),
        ), 1)

        assertEquals("", report.knownTextPrefix)
        assertTrue(report.stoppedAtInvalidValue)
    }

    @Test fun readsHanziCountAfterTheSubsetAcrossVersionGroups() {
        for ((version, width) in listOf(1 to 8, 9 to 8, 10 to 10, 26 to 10, 27 to 12, 40 to 12)) {
            val report = PartialPayloadAnalyzer.analyze(partials("1101" + "0001" + binary(1, width) + binary(0, 13)), version)
            assertEquals("V$version", 1, report.count)
            assertTrue(report.countRequired)
            assertTrue(report.knownTextPrefix.isEmpty())
            for (japanese in listOf(true, false)) {
                assertTrue(report.displayText(japanese).contains(if (japanese) "本文の部分表示には対応していません" else "Partial body display is not supported"))
            }
        }
    }

    @Test fun doesNotReadHanziCountThroughUnknownOrUnsupportedSubset() {
        for ((version, width) in listOf(1 to 8, 10 to 10, 27 to 12)) {
            for (subset in listOf("00?1", "0010")) {
                val report = PartialPayloadAnalyzer.analyze(partials("1101" + subset + binary(1, width)), version)
                assertEquals(null, report.count)
                assertTrue(!report.countRequired)
                assertEquals(subset.contains('?'), report.stoppedAtUnknownBit)
                for (japanese in listOf(true, false)) {
                    val text = report.displayText(japanese)
                    assertTrue(text.contains(if (japanese) "サブセット" else "Subset"))
                    assertTrue(text.contains(if (subset.contains('?')) {
                        if (japanese) "不明" else "unknown"
                    } else if (japanese) "未対応" else "unsupported"))
                    assertTrue(!text.contains(if (japanese) "文字数／バイト数:" else "Character/byte count:"))
                }
            }
            val unknownCount = PartialPayloadAnalyzer.analyze(partials("1101" + "0001" + "?".repeat(width)), version)
            assertEquals(null, unknownCount.count)
            assertTrue(unknownCount.countRequired)
            assertTrue(unknownCount.stoppedAtUnknownBit)
        }
    }

    @Test fun readsUtf8BodyAfterEciWithoutIncludingPostTerminatorGroups() {
        val body = "人間".toByteArray(Charsets.UTF_8)
        val bits = "0111" + binary(26, 8) + byteBits(body) + "0000" + byteBits("万事".toByteArray()) +
            "0000" + byteBits("塞翁が馬".toByteArray())
        val report = PartialPayloadAnalyzer.analyze(partials(bits), 1)
        assertEquals(26, report.eci)
        assertEquals(4, report.mode)
        assertEquals("人間", report.knownTextPrefix)
        assertEquals(body.map { it.toInt() and 255 }, report.knownBytePrefix)
        for (japanese in listOf(true, false)) {
            val shown = report.displayText(japanese)
            assertTrue(shown.contains("HEX: E4 BA BA E9 96 93"))
            assertTrue(shown.contains("Text: 人間"))
            assertTrue(!shown.contains("万事"))
        }
    }

    @Test fun acceptsAllEciHeaderWidthsAndUsesTheLastAssignment() {
        for (assignment in listOf(binary(26, 8), "10" + binary(26, 14), "110" + binary(26, 21))) {
            val report = PartialPayloadAnalyzer.analyze(partials("0111" + binary(3, 8) + "0111" + assignment +
                byteBits("人間".toByteArray())), 1)
            assertEquals("人間", report.knownTextPrefix)
            assertEquals(26, report.eci)
        }
        val latin = PartialPayloadAnalyzer.analyze(partials("0111" + binary(3, 8) + byteBits(byteArrayOf(-23))), 1)
        assertEquals("é", latin.knownTextPrefix)
    }

    @Test fun unknownByteDoesNotInventTheRestOfAMultibyteCharacter() {
        val report = PartialPayloadAnalyzer.analyze(partials("0111" + binary(26, 8) + "0100" + binary(6, 8) +
            "人".toByteArray().joinToString("") { binary(it.toInt() and 255, 8) } + binary(0xE9, 8) + "????????" + binary(0x93, 8)), 1)
        assertEquals("人", report.knownTextPrefix)
        assertEquals(listOf(0xE4, 0xBA, 0xBA, 0xE9), report.knownBytePrefix)
        assertTrue(report.stoppedAtUnknownBit)
        assertTrue(!report.textUnavailable)
    }

    @Test fun unsupportedEciAndInvalidEncodedBytesKeepExactHex() {
        for ((eci, bytes) in listOf(899 to byteArrayOf(0, -1, -128), 26 to byteArrayOf(-1))) {
            val report = PartialPayloadAnalyzer.analyze(partials("0111" + "10" + binary(eci, 14) + byteBits(bytes)), 1)
            assertEquals(bytes.map { it.toInt() and 255 }, report.knownBytePrefix)
            assertTrue(report.knownTextPrefix.isEmpty())
            assertTrue(report.textUnavailable)
        }
    }

    @Test fun incompleteOrInvalidControlsNeverExposeFollowingBytes() {
        for (header in listOf("0111" + "?".repeat(8), "0111" + "11100000", "1001" + "?".repeat(8),
            "0101" + "1001", "0111" + "110" + binary(1000000, 21))) {
            val report = PartialPayloadAnalyzer.analyze(partials(header + byteBits("BODY".toByteArray())), 1)
            assertTrue(report.controlIncomplete)
            assertTrue(report.knownBytePrefix.isEmpty())
            assertTrue(report.knownTextPrefix.isEmpty())
        }
    }

    @Test fun fnc1FirstAndSecondPositionsInterpretPercentEscapes() {
        for (header in listOf("0101", "1001" + binary(42, 8))) {
            val report = PartialPayloadAnalyzer.analyze(partials(header + alphaBits("A%B%%C") + "0000" + byteBits("tail".toByteArray())), 1)
            assertEquals("A\u001DB%C", report.knownTextPrefix)
            assertEquals(6, report.knownEncodedCharacters)
            assertEquals(if (header.startsWith("1001")) 42 else null, report.fnc1ApplicationIndicator)
        }
        val unchanged = PartialPayloadAnalyzer.analyze(partials("0101" + byteBits("A%B".toByteArray())), 1)
        assertEquals(listOf(65, 37, 66), unchanged.knownBytePrefix)
    }

    @Test fun fnc1DoesNotGuessWhetherAnUnknownNextCharacterEscapesPercent() {
        val report = PartialPayloadAnalyzer.analyze(partials("0101" + "0010" + binary(3, 9) +
            binary(10 * 45 + 38, 11) + "??????"), 1)
        assertEquals("A", report.knownTextPrefix)
        assertTrue(report.stoppedAtUnknownBit)
        val complete = PartialPayloadAnalyzer.analyze(partials("0101" + alphaBits("A%")), 1)
        assertEquals("A\u001D", complete.knownTextPrefix)
    }

    @Test fun structuredAppendFnc1AndEciMayPrecedeTheByteBody() {
        val header = "0011" + binary(1, 8) + binary(42, 8) + "1001" + binary(37, 8) + "0111" + binary(26, 8)
        val report = PartialPayloadAnalyzer.analyze(partials(header + byteBits("人間".toByteArray())), 1)
        assertEquals("人間", report.knownTextPrefix)
        assertEquals(37, report.fnc1ApplicationIndicator)
    }

    private fun byteBits(bytes: ByteArray) = "0100" + binary(bytes.size, 8) +
        bytes.joinToString("") { binary(it.toInt() and 255, 8) }

    private fun alphaBits(text: String): String {
        val table = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
        return "0010" + binary(text.length, 9) + text.chunked(2).joinToString("") {
            if (it.length == 2) binary(table.indexOf(it[0]) * 45 + table.indexOf(it[1]), 11)
            else binary(table.indexOf(it[0]), 6)
        }
    }

    private fun binary(value: Int, width: Int): String = value.toString(2).padStart(width, '0')

    private fun partials(bits: String): List<PartialCodeword> = bits.padEnd((bits.length + 7) / 8 * 8, '?')
        .chunked(8)
        .map { chunk ->
            var value = 0
            var unknown = 0
            chunk.forEach { symbol ->
                value = value shl 1
                unknown = unknown shl 1
                when (symbol) {
                    '1' -> value = value or 1
                    '?' -> unknown = unknown or 1
                }
            }
            PartialCodeword(value, unknown, emptyList())
        }
}
