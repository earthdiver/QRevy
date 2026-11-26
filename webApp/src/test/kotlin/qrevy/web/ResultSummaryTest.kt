package qrevy.web

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.*
import org.junit.Test
import kotlin.test.*

class ResultSummaryTest {
    private fun stream(bits: String): ByteArray {
        val terminated = bits + "0000"
        val aligned = terminated.padEnd((terminated.length + 7) / 8 * 8, '0')
        return aligned.chunked(8).map { it.toInt(2).toByte() }.toByteArray()
    }

    private fun candidate(text: String?, byte: String) = RecoveryCandidate(
        1, ErrorCorrectionLevel.M, 0, text, stream("0100" + "00000001" + byte),
        15, 0, 0, 0, 0, 0, 8, intArrayOf(), CodewordExtraction(emptyList()),
    )

    @Test fun distinctCountIncludesSelectableCandidatesThatAreNotText() {
        val candidates = listOf(candidate("A", "01000001"), candidate(null, "11111111"), candidate(null, "11111110"))
        assertTrue(candidates.all { it.isSelectableRecovery })
        val summary = resultSummary(candidates, "search", candidates.first())
        assertTrue(summary.contains("3種類") || summary.contains("3 distinct"), summary)
    }
}
