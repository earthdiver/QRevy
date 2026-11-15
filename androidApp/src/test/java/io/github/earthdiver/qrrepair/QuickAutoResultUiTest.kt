package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class QuickAutoResultUiTest {
    @Test fun resultDiagnosticsSeparateTheSelectedTrialFromCumulativeResultsAndShowBothBitLimits() =
        withResult { activity, _, saved ->
            val report = field("diagnosticReport").get(activity) as RecoveryDiagnosticReport
            val text = report.fullText()
            assertTrue(text.contains("Trial that produced the selected candidate (displayed settings)"))
            assertTrue(text.contains("Candidates retained from this trial: 1"))
            assertTrue(text.contains("Cumulative results across trial conditions and grids"))
            assertTrue(report.sections.first().summary.contains("Search by assuming black/white values for ?: OFF"))
            assertTrue(report.sections.first().summary.contains("Exact RS residual dimension limit: 8 bits"))
            assertEquals(saved, field("preprocessSettings").get(activity))
        }

    @Test fun widerListExecutionIsSavedSeparatelyFromDetailedPreferences() =
        withResult(listExtension = 2) { activity, _, saved ->
            val snapshot = activity.workspaceSessionForTest().history.current!!.snapshot
            assertEquals(2, snapshot.automaticScanListExtension)
            assertEquals(saved, snapshot.preprocessSettings)
            assertEquals(1, saved.listDecodingAdditionalErrors)
            val actual = QuickAutoRecovery.executionSettings(saved, snapshot.automaticScanAssumption!!,
                snapshot.automaticScanListExtension)
            assertEquals(2, actual.listDecodingAdditionalErrors)
        }

    @Test fun combinedAssumptionsAreDisplayedAndSavedTogetherWithoutChangingPreferences() =
        withResult(AutomaticScanAssumption.UTF8_SINGLE_SEGMENT) { activity, dialog, saved ->
            assertTrue(shadowOf(dialog).message.toString().contains("UTF-8 and a single segment"))
            val snapshot = activity.workspaceSessionForTest().history.current!!.snapshot
            assertEquals(AutomaticScanAssumption.UTF8_SINGLE_SEGMENT, snapshot.automaticScanAssumption)
            assertEquals(saved, snapshot.preprocessSettings)
            val actual = QuickAutoRecovery.executionSettings(saved, snapshot.automaticScanAssumption!!)
            assertTrue(actual.assumeUtf8ByteSegments)
            assertTrue(actual.trustKnownPayloadCount)
        }

    @Test fun detailedAnalysisKeepsTheInitialGridSavedPreferencesAndCandidate() = withResult { activity, dialog, saved ->
        val state = field("recoveryState").get(activity) as RecoveryUiState
        val sampled = state.sourceMatrix
        val snapshot = activity.workspaceSessionForTest().history.current!!.snapshot
        assertEquals(AutomaticScanAssumption.UTF8, snapshot.automaticScanAssumption)
        assertEquals(saved, snapshot.preprocessSettings)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(field("preprocessEditor").get(activity))
        assertEquals(saved, field("preprocessSettings").get(activity))
        assertSame(sampled, state.sourceMatrix)
        assertEquals(TEXT, state.selectedRecovery?.text)
    }

    @Test fun doneKeepsTheResultAndCopyPreservesAllCharactersWithoutOpeningUrls() = withResult { activity, dialog, _ ->
        assertTrue(shadowOf(dialog).message.toString().contains(TEXT))
        assertTrue(shadowOf(dialog).message.toString().contains("UTF-8"))
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(TEXT, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(TEXT, (field("recoveryState").get(activity) as RecoveryUiState).selectedRecovery?.text)
    }

    @Test fun exactResultExplainsAndSavesItsConditionWithoutChangingDetailedLimits() =
        withResult(AutomaticScanAssumption.EXACT_KNOWN_BITS) { activity, dialog, saved ->
            assertTrue(shadowOf(dialog).message.toString().contains("observed data/ECC bits are correct"))
            val snapshot = activity.workspaceSessionForTest().history.current!!.snapshot
            assertEquals(AutomaticScanAssumption.EXACT_KNOWN_BITS, snapshot.automaticScanAssumption)
            assertEquals(saved, snapshot.preprocessSettings)
            assertEquals(0, QuickAutoRecovery.executionSettings(saved, snapshot.automaticScanAssumption!!).effectiveDataEccMismatchLimit)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(saved, field("preprocessSettings").get(activity))
        }

    private fun withResult(assumption: AutomaticScanAssumption = AutomaticScanAssumption.UTF8,
        listExtension: Int = 1,
        check: (MainActivity, AlertDialog, PreprocessSettings) -> Unit) {
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            ShadowDialog.getLatestDialog()?.dismiss()
            val qr = Encoder.encode(TEXT, ErrorCorrectionLevel.L)
            val matrix = QrMatrix(qr.matrix.width).apply {
                for (y in 0 until dimension) for (x in 0 until dimension)
                    set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            }
            val geometry = GridGeometry((matrix.dimension - 17) / 4, FinderRole.TOP_LEFT, 30.0, 30.0, 5.0, 0.0).asProjective()
            val saved = PreprocessSettings(version = geometry.version, manualQuad = geometry.imageQuad,
                lmCorrectionEnabled = false, automaticThresholds = true).withInitialErrorLimits()
            val source = BitmapUtils.prepare(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
            field("prepared").set(activity, source)
            field("analysisPrepared").set(activity, source)
            field("preprocessSettings").set(activity, saved)
            val exact = assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS
            val actual = QuickAutoRecovery.executionSettings(saved, assumption, listExtension)
            val outcome = RecoveryEngine().recoverWithDiagnostics(matrix, assumeUtf8ByteSegments = actual.assumeUtf8ByteSegments,
                trustKnownPayloadCount = actual.trustKnownPayloadCount,
                listDecodingAdditionalErrors = actual.listDecodingAdditionalErrors,
                maxKnownDataEccMismatches = if (exact) 0 else ErrorLimitPolicy.automaticScanData(saved.version),
                executionPolicy = if (exact) RecoveryExecutionPolicy.AUTOMATIC_EXACT else RecoveryExecutionPolicy.AUTOMATIC_BASIC)
            val candidate = outcome.candidates.single { it.isSelectableRecovery }
            val profile = SamplingProfile(actual, false)
            val attempt = GeometryAttempt(GeometryHypothesis(emptyList(), geometry, 0.0), matrix,
                MatrixSampler.SamplingStats(128.0, 64.0, 32.0), outcome.candidates, searchDiagnostic = outcome.searchDiagnostic)
            val result = QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), listOf(attempt), profile,
                discoveredCandidates = outcome.discoveredCandidates), candidate, assumption)
            for (method in listOf("retainAutomaticResult", "showAutomaticResult")) {
                MainActivity::class.java.getDeclaredMethod(method, QuickAutoRecoveryResult::class.java)
                    .apply { isAccessible = true }.invoke(activity, result)
            }
            check(activity, ShadowDialog.getLatestDialog() as AlertDialog, saved)
        } finally { controller.pause().stop().destroy() }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    companion object { private const val TEXT = "https://example.org/a?x=1&y=2\ncomplete text" }
}
