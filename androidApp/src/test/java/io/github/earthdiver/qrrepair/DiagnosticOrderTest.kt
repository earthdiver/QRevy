package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w412dp-h892dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticOrderTest {
    private data class Fixture(val name: String, val matrix: QrMatrix, val result: List<RecoveryCandidate>, val failure: RecoveryFailure?)

    private fun fixtures(): List<Fixture> {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 1))
        val original = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(original, 1, qr.maskPattern)
        fun run(name: String, erased: List<Int>, errors: List<Coord> = emptyList(), trust: Boolean = false): Fixture {
            val input = original.copy()
            erased.forEach { raw -> extraction.codewords[raw].coordinates.forEach {
                input.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            } }
            errors.forEach { input.set(it.x, it.y, if (input[it.x, it.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.IMAGE) }
            val engine = RecoveryEngine()
            val outcome = engine.recoverWithDiagnostics(input, trustKnownPayloadCount = trust,
                formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, qr.maskPattern))
            val results = outcome.candidates
            return Fixture(name, input, results, outcome.failure)
        }
        return listOf(
            run("complete", emptyList()),
            run("rs", listOf(8, 9), extraction.codewords[5].coordinates.take(3)),
            // Version 1-M has 16 data codewords and 10 ECC codewords. The last two data words are padding.
            run("structure", (14..25).toList(), trust = true),
            // Keep the mode/count so this fixture requires more payload bits than the search limit.
            run("insufficient", (2..25).toList()),
        )
    }

    @Test fun conventionalEqualityIsShownWhenEvidenceUsesExactBitConstraints() {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val word = CodewordExtractor.extract(matrix, 1, qr.maskPattern).codewords[8]
        word.coordinates.forEach { matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        val results = RecoveryEngine().recover(matrix, maxKnownDataEccMismatches = 0,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, qr.maskPattern))
        val candidate = results.first { it.text == "HELLO" }
        assertTrue(candidate.actualBlockMethods.any { it.bitConstraintSolved })
        assertEquals(true, candidate.conventionalRsDiagnostic!!.matches(candidate.correctedInterleaved))
        for (language in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                install(controller.get(), Fixture("exact-bit", matrix, results, null))
                val report = field<RecoveryDiagnosticReport>(controller.get(), "diagnosticReport")
                val input = report.sections.single { it.stage == DiagnosticStage.INPUT }.details
                val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }.details
                assertTrue(input.contains(if (language == AppLanguage.JAPANESE) "同じデータ・ECCコード語" else "same data/ECC codewords"))
                assertTrue(processing.contains(if (language == AppLanguage.JAPANESE) "この候補を得た方法" else "Methods used to obtain this candidate"))
                assertTrue(processing.contains(if (language == AppLanguage.JAPANESE) "部分bit制約" else "Partial-bit constraints"))
                assertTrue(processing.contains(if (language == AppLanguage.JAPANESE) "不一致上限が0bit" else "mismatch limit is zero"))
                assertTrue(input.contains(if (language == AppLanguage.JAPANESE) "参考診断" else "Reference diagnostic"))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: MainActivity, name: String): T = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T

    private fun install(activity: MainActivity, fixture: Fixture) {
        field<RecoveryUiState>(activity, "recoveryState").apply {
            autoResult = null
            sourceMatrix = fixture.matrix.copy()
            workingMatrix = fixture.matrix.copy()
            geometry = GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
            recoveries = fixture.result
            selectRecovery(fixture.result.indexOfFirst { it.text == "HELLO" })
            failure = fixture.failure
        }
        MainActivity::class.java.getDeclaredMethod("refreshInspector").apply { isAccessible = true }.invoke(activity)
        MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java).apply { isAccessible = true }.invoke(activity, null)
    }

    @Test fun finalFailureUsesAllReadingResultsAndSavedSettingsInsteadOfOneRepresentativeFailure() {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 1))
        val input = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(input, 1, qr.maskPattern)
        val bad = input.copy().apply {
            val cell = extraction.codewords.last().coordinates.first()
            set(cell.x, cell.y, if (input[cell.x, cell.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
                1f, ModuleSource.IMAGE)
        }
        val unknown = input.copy().apply { extraction.codewords.drop(2).flatMap { it.coordinates }.forEach {
            set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        } }
        val engine = RecoveryEngine()
        val format = QrFormatConstraint(qr.ecLevel, qr.maskPattern)
        val firstOutcome = engine.recoverWithDiagnostics(bad, maxKnownDataEccMismatches = 0, formatConstraint = format)
        val badFailure = firstOutcome.failure!!
        val secondOutcome = engine.recoverWithDiagnostics(unknown, maxKnownDataEccMismatches = 0, formatConstraint = format)
        val unknownFailure = secondOutcome.failure!!
        for (language in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                install(activity, Fixture("input-conflict", bad, emptyList(), badFailure))
                val state = field<RecoveryUiState>(activity, "recoveryState")
                val hypothesis = GeometryHypothesis(emptyList(), state.geometry!!, 0.0)
                val stats = MatrixSampler.SamplingStats(128.0, 255.0, 32.0)
                state.autoResult = AutoRecoveryResult(emptyList(), listOf(
                    GeometryAttempt(hypothesis, bad, stats, emptyList(), badFailure),
                    GeometryAttempt(hypothesis, unknown, stats, emptyList(), unknownFailure)))
                MainActivity::class.java.getDeclaredField("preprocessSettings").apply { isAccessible = true }
                    .set(activity, PreprocessSettings(dataEccMismatchLimit = 8))
                MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java)
                    .apply { isAccessible = true }.invoke(activity, null)
                val ja = language == AppLanguage.JAPANESE
                val report = field<RecoveryDiagnosticReport>(activity, "diagnosticReport")
                assertTrue(report.sections[0].summary.contains(if (ja) "不一致上限: 0bit" else "mismatch limit: 0 bits"))
                assertTrue(report.sections[2].details.contains(if (ja) "不一致上限が0bit" else "mismatch limit is zero"))
                assertFalse(report.sections[2].details.contains(if (ja) "RS候補はありません" else "No RS candidate"))
                assertTrue(report.overview.contains(if (ja) "探索を完了しました" else "Search completed"))
                val content = field<TextView>(activity, "contentPreviewText").text.toString()
                assertFalse(content.contains(if (ja) "RS候補はありません" else "No RS candidate"))
                assertFalse(report.fullText().contains(if (ja) "この試行" else "this attempt"))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun allFourRealRecoveryCasesKeepStageBoundariesInBothLanguagesAndAppearances() {
        val fixtures = fixtures()
        assertTrue(fixtures.take(3).all { f -> f.result.any { it.text == "HELLO" } })
        assertTrue(fixtures.last().result.isEmpty())
        val ordinary = fixtures[1].result.first { it.text == "HELLO" }
        assertEquals(16, ordinary.rsBitDiagnostic!!.unknownBits)
        assertEquals(3, ordinary.knownBitMismatchesAfterCorrection)
        val structural = fixtures[2].result.first { it.text == "HELLO" }
        assertTrue(structural.constraintFilledBits > 0)
        assertEquals(96, structural.rsBitDiagnostic!!.unknownBits)
        for (style in AppStyle.entries) for (language in AppLanguage.entries) {
            val app = RuntimeEnvironment.getApplication()
            AppStylePreferences.save(app, style)
            AppLanguageManager.save(app, language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            try {
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
                for (fixture in fixtures) {
                    install(activity, fixture)
                    val report = field<RecoveryDiagnosticReport>(activity, "diagnosticReport")
                    assertEquals(DiagnosticStage.entries, report.sections.map { it.stage })
                    val (settings, input, processing, verification) = report.sections
                    assertFalse(settings.summary.contains(if (language == AppLanguage.JAPANESE) "との不一致" else "mismatches against"))
                    assertFalse(input.summary.contains(if (language == AppLanguage.JAPANESE) "不一致" else "mismatches"))
                    assertFalse(input.summary.contains(if (language == AppLanguage.JAPANESE) "上限で" else "stopped"))
                    assertTrue(verification.details.contains(if (language == AppLanguage.JAPANESE) "比較" else "compar"))
                    if (fixture.name == "rs") {
                        assertTrue(input.details.contains(if (language == AppLanguage.JAPANESE) "通常RSでは入力済みの誤りを訂正できる" else "Conventional RS may still correct"))
                        assertTrue(verification.details.contains(if (language == AppLanguage.JAPANESE) "192bitとの不一致 3bit" else "3 mismatching bits out of 192"))
                    }
                    if (fixture.name == "structure") {
                        assertTrue(processing.summary.contains(if (language == AppLanguage.JAPANESE) "仮定" else "assumption"))
                        assertTrue(input.summary.contains("96"))
                    }
                    if (fixture.name == "insufficient") {
                        assertTrue(input.summary.contains("192"))
                        assertTrue(verification.details.contains(if (language == AppLanguage.JAPANESE) "見つかりませんでした" else "was found"))
                        assertFalse(report.fullText().contains(if (language == AppLanguage.JAPANESE) "この試行" else "this attempt"))
                        assertFalse(verification.details.contains(if (language == AppLanguage.JAPANESE) "データ・ECC領域: 比較対象" else "Data/ECC region: 0 mismatching"))
                    }
                    val view = diagnosticView(activity)
                    if (view.visibility != View.VISIBLE) field<View>(activity, "diagnosticToggle").performClick()
                    val initial = diagnosticText(view)
                    assertTrue(initial.contains(report.overview))
                    assertFalse(initial.contains(if (language == AppLanguage.JAPANESE) "同じ値になる不明bit" else "Unknown bits with the same value"))
                    expandDiagnosticSections(activity)
                    val expanded = diagnosticText(view)
                    val positions = report.sections.map { expanded.indexOf(it.stage.title()) }
                    assertTrue(positions.zipWithNext().all { (a, b) -> a >= 0 && a < b })
                    assertTrue(expanded.contains(input.details))
                    assertTrue(expanded.indexOf(input.details) < positions[2])
                    DiagnosticStage.entries.forEach { stage ->
                        val button = diagnosticButton(view, stage)
                        if (button.visibility == View.VISIBLE) button.performClick()
                    }
                    assertEquals(initial, diagnosticText(view))
                    if (style == AppStyle.MODERN) {
                        render(view, "diagnostic-${fixture.name}-${language.tag}")
                        view.showReport(report, resetExpanded = true)
                        diagnosticButton(view, DiagnosticStage.PROCESSING).performClick()
                        render(view, "processing-${fixture.name}-${language.tag}")
                        diagnosticButton(view, DiagnosticStage.PROCESSING).performClick()
                    }
                }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun diagnosticContentCanBeBuiltWithoutAnActivityAndUsesSearchWideRetentionEvidence() {
        val fixture = fixtures().first()
        val candidate = fixture.result.first { it.text == "HELLO" }
        for (language in AppLanguage.entries) {
            AppLanguageManager.applyTo(RuntimeEnvironment.getApplication(), language)
            val japanese = language == AppLanguage.JAPANESE
            val presenter = RecoveryDiagnosticPresenter(japanese)
            val report = presenter.candidateReport(candidate, null, RecoveryDiagnosticContext(
                candidateCount = 3, unsearched = true, candidatesOmitted = true,
                searchDiagnostic = candidate.searchDiagnostic, selectedEntry = null,
            ))
            assertEquals(DiagnosticStage.entries, report.sections.map { it.stage })
            val verification = report.sections.last()
            assertTrue(verification.details.contains(if (japanese) "3件保持" else "Retained 3"))
            assertTrue(verification.details.contains(if (japanese) "未探索" else "remain unchecked"))
            assertTrue(verification.details.contains("HELLO"))
            assertEquals(candidate.displayConditions.runSettingsText(), report.sections.first().details)
        }
    }

    @Test fun automaticSettingsAreSeparateFromUsedFormatAndEnabledAssumptionFromActualUse() {
        val snapshot = RecoveryDisplayConditions(true, true, null, 2, 3)
        val app = RuntimeEnvironment.getApplication()
        for (language in AppLanguage.entries) {
            AppLanguageManager.applyTo(app, language)
            val settings = snapshot.runSettingsText()
            assertTrue(settings.contains(if (language == AppLanguage.JAPANESE) "ECC: 自動" else "ECC: Automatic"))
            assertFalse(settings.contains("ECC M"))
            val initial = initialDiagnosticSection(1, ErrorCorrectionLevel.M, 2, "", RsBitDiagnostic(16, 16, 0, 16, emptyList()), 16, 2)
            assertTrue(initial.summary.contains("ECC M"))
            assertTrue(initial.details.contains(if (language == AppLanguage.JAPANESE) "参考診断のQRバージョン・ECC・マスクと修復開始時の0/1を正しいもの" else "assumes its QR version/ECC/mask and the 0/1 values at repair start are correct"))
        }
    }

    private fun render(view: View, name: String) {
        System.getenv("QR_REPAIR_DESIGN_PREVIEWS")?.let { path ->
            view.measure(View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, 380, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(380, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xff111815.toInt())
            view.draw(canvas)
            val directory = File(path).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun totalInternalFailureDoesNotClaimThatSearchWasPerformed() {
        val diagnostic = RecoverySearchDiagnostic(incompleteReasons = setOf(SearchIncompleteReason.INTERNAL_ERROR))
        val text = RecoveryDiagnosticPresenter(true).searchResultDescription(
            RecoveryDiagnosticContext(0, true, false, diagnostic, null))
        assertTrue(text.contains(localizedText("修復処理を実行できませんでした", "prevented repair from running")))
        assertFalse(text.contains(localizedText("指定の条件下で探索を行いました", "Searched under the specified conditions")))
    }

}
