package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.text.style.ClickableSpan
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
            val results = engine.recover(input, trustKnownPayloadCount = trust,
                formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, qr.maskPattern))
            return Fixture(name, input, results, engine.lastFailure)
        }
        return listOf(
            run("complete", emptyList()),
            run("rs", listOf(8, 9), extraction.codewords[5].coordinates.take(3)),
            // Version 1-M has 16 data codewords and 10 ECC codewords. The last two data words are padding.
            run("structure", (14..25).toList(), trust = true),
            run("insufficient", (0..25).toList()),
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
                val input = report.sections.single { it.stage == DiagnosticStage.INPUT }.summary
                val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }.summary
                assertTrue(input.contains(if (language == AppLanguage.JAPANESE) "同じデータ・ECCコード語" else "same data/ECC codewords"))
                assertTrue(processing.contains(if (language == AppLanguage.JAPANESE) "表示中の修復根拠" else "displayed repair evidence"))
                assertTrue(processing.contains(if (language == AppLanguage.JAPANESE) "部分bit制約" else "Partial-bit constraints"))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: MainActivity, name: String): T = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T

    private fun install(activity: MainActivity, fixture: Fixture) {
        field<RecoveryUiState>(activity, "recoveryState").apply {
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
                    assertTrue(verification.summary.contains(if (language == AppLanguage.JAPANESE) "比較" else "compar"))
                    if (fixture.name == "rs") {
                        assertTrue(input.details.contains(if (language == AppLanguage.JAPANESE) "通常RSでは入力済みの誤りを訂正できる" else "Conventional RS may still correct"))
                        assertTrue(verification.summary.contains(if (language == AppLanguage.JAPANESE) "192bitとの不一致 3bit" else "3 mismatching bits out of 192"))
                    }
                    if (fixture.name == "structure") {
                        assertTrue(processing.summary.contains(if (language == AppLanguage.JAPANESE) "仮定" else "assumption"))
                        assertTrue(input.summary.contains("96"))
                    }
                    if (fixture.name == "insufficient") {
                        assertTrue(input.summary.contains("208"))
                        assertTrue(verification.summary.contains(if (language == AppLanguage.JAPANESE) "読み取れませんでした" else "did not produce readable content"))
                        assertFalse(verification.summary.contains(if (language == AppLanguage.JAPANESE) "データ・ECC領域: 比較対象" else "Data/ECC region: 0 mismatching"))
                    }
                    val view = field<TextView>(activity, "diagnosticDetailsText")
                    if (view.visibility != View.VISIBLE) field<View>(activity, "diagnosticToggle").performClick()
                    val initial = view.text.toString()
                    assertFalse(initial.contains(if (language == AppLanguage.JAPANESE) "同じ値になる不明bit" else "Unknown bits with the same value"))
                    val spans = (view.text as Spanned).getSpans(0, view.text.length, ClickableSpan::class.java)
                    assertTrue(spans.isNotEmpty())
                    spans.forEach { it.onClick(view) }
                    val expanded = view.text.toString()
                    val positions = report.sections.map { expanded.indexOf(it.stage.title()) }
                    assertTrue(positions.zipWithNext().all { (a, b) -> a >= 0 && a < b })
                    if (input.details.isNotBlank()) {
                        assertTrue(expanded.contains(input.details))
                        assertTrue(expanded.indexOf(input.details) < positions[2])
                    }
                    spans.forEach { it.onClick(view) }
                    assertEquals(initial, view.text.toString())
                    if (style == AppStyle.MODERN) render(view, "diagnostic-${fixture.name}-${language.tag}")
                }
            } finally { controller.pause().stop().destroy() }
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
            assertTrue(initial.details.contains(if (language == AppLanguage.JAPANESE) "この形式と入力済みの白黒を正しいもの" else "assumes the format and entered values are correct"))
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
}
