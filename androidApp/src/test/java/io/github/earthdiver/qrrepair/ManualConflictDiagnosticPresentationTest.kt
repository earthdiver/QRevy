package io.github.earthdiver.qrrepair

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualConflictDiagnosticPresentationTest {
    private val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M,
        mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 2))
    private val format = QrFormatConstraint(qr.ecLevel, qr.maskPattern)

    private fun matrix() = QrMatrix(qr.matrix.width).apply {
        for (y in 0 until dimension) for (x in 0 until dimension) {
            set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
    }

    private fun inBothLanguages(check: (Boolean) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val previous = AppLanguageManager.current
        try {
            for (language in AppLanguage.entries) {
                AppLanguageManager.applyTo(app, language)
                check(language == AppLanguage.JAPANESE)
            }
        } finally { AppLanguageManager.applyTo(app, previous) }
    }

    private fun report(failure: RecoveryFailure, japanese: Boolean) = RecoveryDiagnosticPresenter(japanese)
        .failureReport(failure, null, RecoveryDiagnosticContext(0, false, false, failure.searchDiagnostic, null))

    @Test fun fixedTimingAndDataConflictsIdentifyCoordinatesAndBothValuesInTheReferenceRecord() {
        val dataCoord = CodewordExtractor.extract(matrix(), 1, qr.maskPattern).codewords[4].coordinates.first()
        for (coord in listOf(Coord(6, 10), dataCoord)) {
            val input = matrix()
            val expected = input[coord.x, coord.y].value
            val entered = if (expected == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
            input.set(coord.x, coord.y, entered, 1f, ModuleSource.USER)
            val outcome = RecoveryEngine().recoverWithDiagnostics(input, formatConstraint = format)
            assertTrue(outcome.candidates.isEmpty())
            val failure = requireNotNull(outcome.failure)
            assertEquals(RecoveryFailureReason.MANUAL_PIN_CONFLICT, failure.reason)
            assertEquals(listOf(ManualCellConflict(coord, entered, expected)), failure.manualConflicts)

            inBothLanguages { japanese ->
                fun value(value: ModuleValue) = if (value == ModuleValue.ZERO) {
                    if (japanese) "白（0）" else "white (0)"
                } else if (japanese) "黒（1）" else "black (1)"
                val report = report(failure, japanese)
                val calculations = report.sections.single { it.stage == DiagnosticStage.PROCESSING }
                    .groups.single { it.id == DiagnosticDetailId.CALCULATIONS }
                assertTrue(calculations.summary, calculations.summary.contains(if (japanese)
                    "探索全体の失敗原因を示すものではありません" else "not a diagnosis of why the whole search failed"))
                assertTrue(calculations.text, calculations.text.contains(if (japanese) "座標は0から" else "zero-based coordinates"))
                val row = if (japanese) "セル (${coord.x},${coord.y}): 固定値 ${value(entered)} / この参考計算で必要な値: ${value(expected)}"
                    else "Cell (${coord.x},${coord.y}): fixed value ${value(entered)} / required by this reference calculation: ${value(expected)}"
                assertTrue(calculations.text, calculations.text.contains(row))
                assertTrue(report.fullText().contains(row))
            }
        }
    }

    @Test fun collectiveFormatConflictDoesNotInventRequiredValuesForIndividualCells() {
        val input = matrix()
        val eccOnly = QrFormatConstraint(qr.ecLevel, null)
        val copies = FormatSolver.copies(input.dimension)
        val fixed = FormatEditPolicy.fixedValues(1, eccOnly)
        val bit = copies.first().indices.first { index ->
            val (x, y) = copies.first()[index]
            Coord(x, y) !in fixed
        }
        val coordinates = copies.map { copy -> copy[bit].let { (x, y) -> Coord(x, y) } }
        coordinates.forEachIndexed { index, coord ->
            input.set(coord.x, coord.y, if (index == 0) ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.USER)
        }
        val outcome = RecoveryEngine().recoverWithDiagnostics(input, formatConstraint = eccOnly)
        assertTrue(outcome.candidates.isEmpty())
        val failure = requireNotNull(outcome.failure)
        assertEquals(RecoveryFailureReason.FORMAT_EDIT_CONFLICT, failure.reason)
        assertEquals(coordinates.toSet(), failure.manualConflicts.map { it.coord }.toSet())
        assertTrue(failure.manualConflicts.all { it.expected == null })

        inBothLanguages { japanese ->
            val calculations = report(failure, japanese).sections.single { it.stage == DiagnosticStage.PROCESSING }
                .groups.single { it.id == DiagnosticDetailId.CALCULATIONS }
            assertTrue(calculations.text, calculations.text.contains(if (japanese)
                "固定値の組合せが形式の条件と矛盾しています" else "The combination of fixed values conflicts with the format conditions"))
            assertTrue(calculations.text, calculations.text.contains(if (japanese)
                "個別に誤りと判定したわけではありません" else "have not individually been identified as incorrect"))
            failure.manualConflicts.forEach { conflict ->
                val row = calculations.text.lines().single { it.contains("(${conflict.coord.x},${conflict.coord.y})") }
                assertTrue(row, row.endsWith(if (japanese) "必要な値: 未確定" else "required value: not determined"))
                assertFalse(row, row.contains(if (japanese) "この参考計算で必要な値:" else "required by this reference calculation:"))
            }
        }
    }

    @Test fun reportsWithoutFixedCellConflictsDoNotAddAnEmptyConflictRecord() = inBothLanguages { japanese ->
        val failure = RecoveryFailure(ErrorCorrectionLevel.M, 2, 30, 0, 30, RecoveryFailureStage.REED_SOLOMON)
        val report = report(failure, japanese)
        assertFalse(report.fullText().contains(if (japanese) "固定セルとの矛盾（" else "Fixed-cell conflict details"))
        assertFalse(report.sections.single { it.stage == DiagnosticStage.PROCESSING }
            .groups.any { it.id == DiagnosticDetailId.CALCULATIONS })
    }
}
