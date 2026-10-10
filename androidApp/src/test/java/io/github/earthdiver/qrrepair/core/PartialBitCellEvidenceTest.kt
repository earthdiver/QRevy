package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.AppLanguage
import io.github.earthdiver.qrrepair.AppLanguageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PartialBitCellEvidenceTest {
    @Test fun structureChangedKnownBitIsNotDescribedAsPreservedByPartialBitConstraints() {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 2))
        val input = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) {
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
            }
        }
        val extraction = CodewordExtractor.extract(input, 1, qr.maskPattern)
        // Erase two pad codewords and all ECC; a separate known pad bit is incorrect.
        for (raw in 14..25) for (coord in extraction.codewords[raw].coordinates) {
            input.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        val changed = extraction.codewords[13].coordinates.first()
        assertEquals(ModuleValue.ONE, input[changed.x, changed.y].value)
        input.set(changed.x, changed.y, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)

        val engine = RecoveryEngine()
        val candidate = engine.recover(input, trustKnownPayloadCount = true,
            formatConstraint = QrFormatConstraint(qr.ecLevel, qr.maskPattern),
            maxKnownDataEccMismatches = 1).single { it.text == "HELLO" }
        assertTrue(candidate.constraintForcedByUserSetting)
        assertTrue(candidate.constraintFilledBits > 0)
        assertEquals(1, candidate.knownBitMismatchesAfterCorrection)
        val after = RepairTraceBuilder.candidateMatrix(input, candidate, engine)
        val trace = RepairTraceBuilder.build(input, after, candidate).getValue(changed)
        assertEquals(RepairReasonKind.PAYLOAD_PADDING, trace.primaryReason)
        assertEquals(ModuleValue.ZERO, trace.originalValue)
        assertEquals(ModuleValue.ONE, trace.finalValue)
        assertTrue(trace.bitConstraintSolved)

        val app = RuntimeEnvironment.getApplication()
        val previous = AppLanguageManager.current
        try {
            for (language in AppLanguage.entries) {
                AppLanguageManager.applyTo(app, language)
                val japanese = language == AppLanguage.JAPANESE
                for (includeBasic in listOf(true, false)) {
                    val text = trace.explanationJa(includeBasic)
                    assertTrue(text, text.contains(if (japanese) "RS部分bit制約で誤り訂正の条件を満たす値"
                        else "RS partial-bit constraints to find values satisfying the error-correction conditions"))
                    assertTrue(text, text.contains(if (japanese) "構造補完・変更や仮置きがある場合は、その値も条件に含みます"
                        else "Any values filled, changed or tentatively assigned before this calculation are included in those conditions"))
                    assertFalse(text, text.contains(if (japanese) "入力済みのbitを保つ"
                        else "preserving the entered bits"))
                }
            }
        } finally { AppLanguageManager.applyTo(app, previous) }
    }
}
