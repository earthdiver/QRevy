package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
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
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port-mdpi")
class QuickAutoRetainedCandidateUiTest {
    @Test fun multipleCandidatesCanBeSelectedAndComparedAfterClosingTheFailureNotice() {
        for (language in AppLanguage.entries) withRetained(2, QuickAutoFailureReason.MULTIPLE_CANDIDATES, language = language) {
            activity, result, dialog, saved ->
            val state = state(activity)
            assertNotNull(state.workingMatrix)
            assertEquals(result.recovery.entries.first().candidate, state.selectedRecovery)
            assertFailure(activity, result, dialog)
            assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertNull(field("preprocessEditor").get(activity))
            val selector = field("upperResultSelector").get(activity) as ViewGroup
            assertEquals(View.VISIBLE, selector.visibility)
            assertNotNull(field("comparisonCandidate").get(activity))
            val radios = descendants(selector).filterIsInstance<RadioGroup>().single()
            assertEquals(2, radios.childCount)
            (radios.getChildAt(1) as android.widget.RadioButton).performClick()
            assertEquals(result.recovery.entries[1].candidate, state.selectedRecovery)
            assertEquals(result.recovery.entries[1].attempt.hypothesis.geometry, state.geometry)
            assertEquals(result.recovery.entries[1].profile, state.samplingProfile)
            val expectedMatrix = result.recovery.entries[1].attempt.matrix
            for (y in 0 until expectedMatrix.dimension) for (x in 0 until expectedMatrix.dimension)
                assertEquals(expectedMatrix[x, y], state.sourceMatrix!![x, y])
            assertEquals(state.selectedRecovery!!.text,
                (field("contentPreviewText").get(activity) as TextView).text.toString().substringAfter('\n').trim())
            assertEquals(saved, field("preprocessSettings").get(activity))
            assertNull(result.selected)
        }
    }

    @Test fun aCandidateFromAnUnfinishedTrialIsSavedWithoutTurningTimeoutIntoSuccess() =
        withRetained(1, QuickAutoFailureReason.TIME_LIMIT) { activity, result, dialog, saved ->
            assertFailure(activity, result, dialog)
            val state = state(activity)
            assertNotNull(state.sourceMatrix)
            assertNotNull(state.workingMatrix)
            assertNotNull(state.inspectorMatrix)
            assertTrue(SearchIncompleteReason.TIME_LIMIT in state.searchDiagnostic!!.incompleteReasons)
            assertEquals("Retry with more time (10 seconds)", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
            val history = activity.workspaceSessionForTest().history.current!!
            assertEquals(state.selectedRecovery!!.text, result.recovery.entries.first().candidate.text)
            assertNotNull(history.snapshot.sourceMatrix)
            assertNotNull(history.snapshot.workingMatrix)
            assertNotNull(history.snapshot.selectedQr)
            assertEquals(saved, history.snapshot.preprocessSettings)
            assertEquals(AutomaticScanAssumption.UTF8, history.snapshot.automaticScanAssumption)
            assertNull(result.selected)
        }

    @Test fun detailedSettingsCanReturnToRetainedResultsWithoutStartingAnotherRepair() =
        withRetained(2, QuickAutoFailureReason.MULTIPLE_CANDIDATES) { activity, result, dialog, saved ->
            val selected = state(activity).selectedRecovery
            val historyId = activity.workspaceSessionForTest().history.current!!.id
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(field("preprocessEditor").get(activity))
            val editorRoot = (ShadowDialog.getLatestDialog() as AlertDialog).window!!.decorView
            val resultTab = descendants(editorRoot).filterIsInstance<TextView>()
                .first { it.text.toString() == activity.getString(R.string.step_result) && it.isShown }
            assertTrue(resultTab.isEnabled)
            resultTab.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(editorRoot.isShown)
            assertTrue((field("contentPreviewText").get(activity) as TextView).isShown)
            assertSame(selected, state(activity).selectedRecovery)
            assertEquals(historyId, activity.workspaceSessionForTest().history.current!!.id)
            assertEquals(saved, field("preprocessSettings").get(activity))
            assertNull(result.selected)
        }

    @Test fun retainedZeroErrorCandidateKeepsItsActualConditionInHistory() =
        withRetained(1, QuickAutoFailureReason.TIME_LIMIT, assumption = AutomaticScanAssumption.EXACT_KNOWN_BITS) {
            activity, result, dialog, saved ->
            assertFailure(activity, result, dialog)
            val history = activity.workspaceSessionForTest().history.current!!
            assertTrue(history.detail.contains("zero data/ECC bit errors"))
            assertFalse(history.detail.contains("5%"))
            assertEquals(AutomaticScanAssumption.EXACT_KNOWN_BITS, history.snapshot.automaticScanAssumption)
            assertEquals(saved, history.snapshot.preprocessSettings)
            assertNull(result.assumption)
        }

    private fun assertFailure(activity: MainActivity, result: QuickAutoRecoveryResult, dialog: AlertDialog) {
        assertNull(result.selected)
        assertTrue(shadowOf(dialog).message.toString().contains(result.failureReason!!.message()))
        assertTrue((field("statusText").get(activity) as TextView).text.toString().startsWith(result.failureReason.message()))
        assertEquals(if (AppLanguageManager.current == AppLanguage.JAPANESE) "詳細解析へ" else "Detailed analysis",
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
    }

    private fun withRetained(count: Int, reason: QuickAutoFailureReason,
        assumption: AutomaticScanAssumption = AutomaticScanAssumption.UTF8,
        language: AppLanguage = AppLanguage.ENGLISH,
        check: (MainActivity, QuickAutoRecoveryResult, AlertDialog, PreprocessSettings) -> Unit) {
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
        AppStylePreferences.save(RuntimeEnvironment.getApplication(), AppStyle.MODERN)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            ShadowDialog.getLatestDialog()?.dismiss()
            val source = BitmapUtils.prepare(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
            val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 30.0, 30.0, 5.0, 0.0).asProjective()
            val saved = PreprocessSettings(manualQuad = geometry.imageQuad, lmCorrectionEnabled = false).withInitialErrorLimits()
            field("prepared").set(activity, source)
            field("analysisPrepared").set(activity, source)
            field("preprocessSettings").set(activity, saved)
            val actual = QuickAutoRecovery.executionSettings(saved, assumption)
            val profile = SamplingProfile(actual, false)
            val attempts = (0 until count).map { index ->
                val qr = Encoder.encode("RETAINED $index", ErrorCorrectionLevel.L)
                val matrix = QrMatrix(qr.matrix.width)
                for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension)
                    matrix.set(x, y, if (qr.matrix[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
                val outcome = RecoveryEngine().recoverWithDiagnostics(matrix,
                    assumeUtf8ByteSegments = actual.assumeUtf8ByteSegments,
                    maxKnownDataEccMismatches = actual.effectiveDataEccMismatchLimit,
                    listDecodingAdditionalErrors = actual.listDecodingAdditionalErrors,
                    executionPolicy = if (assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS)
                        RecoveryExecutionPolicy.AUTOMATIC_EXACT else RecoveryExecutionPolicy.AUTOMATIC_BASIC)
                val diagnostic = if (reason == QuickAutoFailureReason.TIME_LIMIT)
                    outcome.searchDiagnostic.copy(incompleteReasons = setOf(SearchIncompleteReason.TIME_LIMIT), unsearched = true)
                else outcome.searchDiagnostic
                GeometryAttempt(GeometryHypothesis(emptyList(), geometry, 0.0), matrix,
                    MatrixSampler.SamplingStats(128.0, 64.0, 32.0), outcome.candidates, searchDiagnostic = diagnostic)
            }
            val result = QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), attempts, profile,
                discoveredCandidates = RecoveryDiscoveryCount.Exact(count)), failureReason = reason)
            field("automaticDiagnostic").set(activity, AutomaticRepairDiagnosticSession(source.luma, saved, ""))
            for (method in listOf("retainAutomaticResult", "showAutomaticResult"))
                MainActivity::class.java.getDeclaredMethod(method, QuickAutoRecoveryResult::class.java)
                    .apply { isAccessible = true }.invoke(activity, result)
            check(activity, result, ShadowDialog.getLatestDialog() as AlertDialog, saved)
        } finally { controller.pause().stop().destroy() }
    }
    private fun state(activity: MainActivity) = field("recoveryState").get(activity) as RecoveryUiState
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
