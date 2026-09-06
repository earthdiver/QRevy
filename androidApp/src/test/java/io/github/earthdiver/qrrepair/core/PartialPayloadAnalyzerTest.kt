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
