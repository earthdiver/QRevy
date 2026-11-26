package qrevy.web

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.AppLanguage
import io.github.earthdiver.qrrepair.AppLanguageManager
import io.github.earthdiver.qrrepair.core.*
import org.junit.Test
import kotlin.test.*

class ResultContentPresentationTest {
    private fun candidate(text: String?): RecoveryCandidate {
        val bytes = (text ?: "binary").encodeToByteArray()
        val bits = "0100" + bytes.size.toString(2).padStart(8, '0') +
            bytes.joinToString("") { (it.toInt() and 255).toString(2).padStart(8, '0') } + "0000"
        val data = bits.padEnd((bits.length + 7) / 8 * 8, '0').chunked(8).map { it.toInt(2).toByte() }.toByteArray()
        return RecoveryCandidate(
            5, ErrorCorrectionLevel.M, 0, text, data,
            15, 0, 0, 0, 0, 0, 8, intArrayOf(), CodewordExtraction(emptyList()),
        )
    }

    @Test fun contentSurroundingsPreserveNativeDescriptionsWithoutDuplicatingTheOriginal() {
        val originalLanguage = AppLanguageManager.current
        try {
            for (language in listOf(AppLanguage.JAPANESE, AppLanguage.ENGLISH)) {
                AppLanguageManager.current = language
                for (text in listOf(
                    "https://example.com/qr?value=complete#fragment",
                    "BEGIN:VCARD\nVERSION:3.0\nFN:QR Test\nTEL:0123456789\nEND:VCARD",
                    "geo:35.68,139.76",
                    "WIFI:T:WPA;S:Test Network;P:test-secret;;",
                    "  raw <script> text\n\nwith whitespace 😀  ",
                    "",
                )) {
                    val candidate = candidate(text)
                    val native = QrContentPreviewParser.parse(candidate)!!.resultContentText(text)
                    val presentation = resultContentPresentation(candidate)
                    assertEquals(native, presentation.getValue("before") + text + presentation.getValue("after"))
                    if (text.isNotEmpty()) {
                        assertFalse(presentation.getValue("before").contains(text))
                        assertFalse(presentation.getValue("after").contains(text))
                    }
                }
            }
        } finally {
            AppLanguageManager.current = originalLanguage
        }
    }

    @Test fun nonTextCandidatesRetainTheirNativeDescription() {
        val candidate = candidate(null)
        val presentation = resultContentPresentation(candidate)
        assertEquals(QrContentPreviewParser.parse(candidate)!!.resultContentText(null), presentation.getValue("before"))
        assertEquals("", presentation.getValue("after"))
    }
}
