package qrevy.web

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.test.*

class CharsetsTest {
    private val utf8 = Charsets.forName("UTF-8")

    @Test fun legacyBytePairsPreserveTheSameFollowingCharactersAsTheJvm() {
        for (name in listOf("Shift_JIS", "GB2312", "GBK", "Big5", "EUC-KR", "GB18030")) {
            val expected = java.nio.charset.Charset.forName(name)
            val actual = Charsets.forName(name)
            for (first in 0..255) for (second in 0..255) {
                val bytes = byteArrayOf(first.toByte(), second.toByte(), 32, 65)
                assertEquals(String(bytes, expected), String(bytes, actual), "$name bytes=$first,$second")
            }
        }
    }

    @Test fun gb18030FourByteErrorsConsumeOnlyTheMalformedSequence() {
        val expected = java.nio.charset.Charset.forName("GB18030")
        val actual = Charsets.forName("GB18030")
        for (third in 0..255) for (fourth in 0..255) {
            val bytes = byteArrayOf(0x81.toByte(), 0x30, third.toByte(), fourth.toByte(), 32, 65)
            assertEquals(String(bytes, expected), String(bytes, actual), "bytes=81,30,$third,$fourth")
        }
        for (input in listOf(listOf(0xff,0x30,0x81,0x30), listOf(0x81,0x30,0x81), listOf(0x81,0x30))) {
            val bytes = input.map { it.toByte() }.toByteArray()
            assertEquals(String(bytes, expected), String(bytes, actual))
        }
    }

    @Test fun utf16BePreservesTrailingSurrogatePairsAndMatchesJvmReplacement() {
        val actual = Charsets.forName("UTF-16BE")
        for (text in listOf("", "A", "😀", "𠮷", "A😀", "😀A", "日本語😀", "\uFEFFtext")) {
            val bytes = text.toByteArray(StandardCharsets.UTF_16BE)
            assertEquals(text, String(bytes, actual))
        }
        for (input in listOf(listOf(0), listOf(0xd8,0x3d), listOf(0xd8,0x3d,0),
            listOf(0xd8,0x3d,0,0x41), listOf(0xdc,0,0,0x41))) {
            val bytes = input.map { it.toByte() }.toByteArray()
            assertEquals(String(bytes, StandardCharsets.UTF_16BE), String(bytes, actual))
        }
        val decoder = actual.newDecoder()
        val input = ByteBuffer.wrap("😀".toByteArray(StandardCharsets.UTF_16BE))
        assertTrue(decoder.decode(input, java.nio.CharBuffer.allocate(1), true).isOverflow)
        assertEquals(0, input.position())
        val output = java.nio.CharBuffer.allocate(2)
        assertTrue(decoder.decode(input, output, true).isUnderflow)
        output.flip()
        assertEquals("😀", output.toString())
    }

    @Test fun bomUtf16MatchesJvmAndResetsByteOrderBetweenStreams() {
        val actual = Charsets.forName("UTF-16")
        val cases = listOf(
            byteArrayOf(0xfe.toByte(), 0xff.toByte(), 0xd8.toByte(), 0x3d, 0xde.toByte(), 0),
            byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x3d, 0xd8.toByte(), 0, 0xde.toByte()),
            byteArrayOf(0, 65), byteArrayOf(0), byteArrayOf(0xfe.toByte(), 0xff.toByte()),
            byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0xd8.toByte(), 65, 0),
            byteArrayOf(0xfe.toByte(), 0xff.toByte(), 0xdc.toByte(), 0),
        )
        val decoder = actual.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
        for (bytes in cases) {
            assertEquals(String(bytes, StandardCharsets.UTF_16), decoder.decode(ByteBuffer.wrap(bytes)).toString())
        }
        decoder.reset()
        val input = ByteBuffer.wrap(cases[1])
        assertTrue(decoder.decode(input, java.nio.CharBuffer.allocate(1), true).isOverflow)
        assertEquals(2, input.position())
        val output = java.nio.CharBuffer.allocate(2)
        assertTrue(decoder.decode(input, output, true).isUnderflow)
        output.flip()
        assertEquals("😀", output.toString())
    }

    @Test fun malformedUtf8PreservesFollowingAsciiAndValidSequences() {
        for (first in 0..255) for (second in 0..255) {
            val bytes = byteArrayOf(first.toByte(), second.toByte(), 72, 240.toByte(), 159.toByte(), 152.toByte(), 128.toByte())
            assertEquals(String(bytes, StandardCharsets.UTF_8), String(bytes, utf8), "bytes=$first,$second")
        }
    }

    @Test fun utf8RejectsOverlongSurrogateAndOutOfRangeSequences() {
        val cases = listOf(intArrayOf(0xC0,0xAF), intArrayOf(0xE0,0x80,0xAF), intArrayOf(0xED,0xA0,0x80),
            intArrayOf(0xF0,0x80,0x80,0xAF), intArrayOf(0xF4,0x90,0x80,0x80), intArrayOf(0xF5,0x80,0x80,0x80),
            intArrayOf(0xF0,0x9F,0x98))
        for (input in cases) {
            val bytes = input.map { it.toByte() }.toByteArray()
            assertEquals(String(bytes, StandardCharsets.UTF_8), String(bytes, utf8))
            assertFailsWith<java.nio.charset.CharacterCodingException> {
                utf8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            }
        }
        for (text in listOf("ASCII", "日本語", "𠮷😀", "\uFEFFtext")) assertEquals(text, String(text.toByteArray(), utf8))
    }
}
