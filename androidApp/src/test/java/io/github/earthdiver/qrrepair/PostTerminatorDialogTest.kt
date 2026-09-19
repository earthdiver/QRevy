package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostTerminatorDialogTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: MainActivity, name: String): T = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
    private fun refresh(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("updateContentPreview")
        .apply { isAccessible = true }.invoke(activity)

    @Test fun resultActionShowsHiddenTextForFailedAttemptsInBothStylesAndLanguages() {
        val secret = "tail\u0000text"
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); byteSegment(secret) }.finish()
        val matrix = postTerminatorMatrix(data)
        val engine = RecoveryEngine()
        assertTrue(engine.recover(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0),
            maxKnownDataEccMismatches = 0).isEmpty())
        val failure = requireNotNull(engine.lastFailure)
        for (style in AppStyle.entries) for (language in AppLanguage.entries) {
            val app = RuntimeEnvironment.getApplication()
            AppStylePreferences.save(app, style)
            AppLanguageManager.save(app, language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
                val activity = controller.get()
                val state = field<RecoveryUiState>(activity, "recoveryState")
                state.showAttempt(0, GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0), matrix,
                    emptyList(), failure, -1)
                refresh(activity)
                val body = field<TextView>(activity, "contentPreviewText").text as android.text.Spanned
                val bodyHighlights = body.getSpans(0, body.length, android.text.style.BackgroundColorSpan::class.java)
                assertEquals(1, bodyHighlights.size)
                assertEquals("A", body.subSequence(body.getSpanStart(bodyHighlights.single()), body.getSpanEnd(bodyHighlights.single())).toString())
                assertTrue(body.contains("HEX"))
                assertTrue(body.contains("41"))
                assertFalse(body.contains(secret))
                val actions = field<View>(activity, "resultActions")
                assertEquals(View.VISIBLE, actions.visibility)
                val button = field<Button>(activity, "resultTailAnalysisButton")
                assertEquals(activity.getString(R.string.analyze_post_terminator), button.text)
                assertEquals(View.VISIBLE, button.visibility)
                val panel = button.parent as ViewGroup
                assertTrue(panel.indexOfChild(field<View>(activity, "diagnosticToggle")) < panel.indexOfChild(button))
                assertTrue(panel.indexOfChild(field<View>(activity, "diagnosticDetailsText")) < panel.indexOfChild(button))
                assertFalse(button.parent === actions)
                button.performClick()
                field<ExecutorService>(activity, "ioExecutor").submit {}.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(dialog.isShowing)
                val views = descendants(dialog.window!!.decorView)
                val extracted = views.filterIsInstance<TextView>().single { it.text.toString() == secret }
                val styled = extracted.text as android.text.Spanned
                val highlights = styled.getSpans(0, styled.length, android.text.style.BackgroundColorSpan::class.java)
                assertEquals(1, highlights.size)
                assertEquals(0, styled.getSpanStart(highlights.single()))
                assertEquals(secret.length, styled.getSpanEnd(highlights.single()))
                val radios = views.filterIsInstance<RadioButton>()
                assertEquals(2, radios.size)
                assertTrue(radios[1].isChecked)
                radios[0].performClick()
                assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == secret })
                assertNull(state.selectedRecovery)
                assertSame(failure, state.failure)
                assertEquals(View.GONE, field<View>(activity, "resultSavePngButton").visibility)
                dialog.dismiss()
                state.failure = failure.copy(conventionalRsDiagnostic = failure.conventionalRsDiagnostic!!.copy(
                    knownBitMismatches = 1, knownBitMismatchLimit = 0))
                refresh(activity)
                assertFalse(field<TextView>(activity, "contentPreviewText").text.contains("HEX"))
                state.clearAttempt()
                refresh(activity)
                assertEquals(View.GONE, actions.visibility)
                assertEquals(View.GONE, button.visibility)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun analysisActionIsHiddenForSuccessAndOtherFailureReasons() {
        val matrix = postTerminatorMatrix(PayloadBits().apply { byteSegment("VISIBLE") }.finish())
        val candidate = RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0)).first()
        for (style in AppStyle.entries) {
            val app = RuntimeEnvironment.getApplication()
            AppStylePreferences.save(app, style)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
                val activity = controller.get()
                val state = field<RecoveryUiState>(activity, "recoveryState")
                val structureFailure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 0, 0, 0, RecoveryFailureStage.PAYLOAD_VALIDATION)
                state.showAttempt(0, GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0), matrix,
                    emptyList(), structureFailure, -1)
                refresh(activity)
                val button = field<Button>(activity, "resultTailAnalysisButton")
                assertEquals(View.VISIBLE, button.visibility)
                field<Button>(activity, "diagnosticToggle").performClick()
                assertEquals(View.VISIBLE, button.visibility)
                for (stage in RecoveryFailureStage.entries.filter { it != RecoveryFailureStage.PAYLOAD_VALIDATION }) {
                    state.failure = structureFailure.copy(stage = stage)
                    refresh(activity)
                    assertEquals(stage.name, View.GONE, button.visibility)
                }
                for (reason in RecoveryFailureReason.entries) {
                    state.failure = structureFailure.copy(reason = reason)
                    refresh(activity)
                    assertEquals(reason.name, View.GONE, button.visibility)
                }
                state.failure = structureFailure
                state.recoveries = listOf(candidate)
                state.selectRecovery(0)
                refresh(activity)
                assertEquals(View.GONE, button.visibility)
                state.selectRecovery(-1)
                refresh(activity)
                assertEquals(View.VISIBLE, button.visibility)
                state.clearAttempt()
                refresh(activity)
                assertEquals(View.GONE, button.visibility)
            } finally { controller.pause().stop().destroy() }
        }
    }
}
