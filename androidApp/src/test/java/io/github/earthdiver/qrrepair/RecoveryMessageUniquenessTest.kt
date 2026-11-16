package io.github.earthdiver.qrrepair

import android.widget.TextView
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryMessageUniquenessTest {
    @Test fun resultScreenStatesSearchCompletionOnceWithOrWithoutCandidatesAndExpandedDiagnostics() {
        val encoded = Encoder.encode("MESSAGE CHECK", ErrorCorrectionLevel.M).matrix
        val matrix = QrMatrix(encoded.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (encoded[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val candidate = RecoveryEngine().recover(matrix).single { it.isSelectableRecovery }
        val geometry = GridGeometry(candidate.version, FinderRole.TOP_LEFT, 30.0, 30.0, 5.0, 0.0)
        for (language in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                ShadowDialog.getLatestDialog()?.dismiss()
                val state = field("recoveryState").get(activity) as RecoveryUiState
                for (aggregated in listOf(false, true)) for (withCandidate in listOf(false, true)) {
                    for (unresolved in listOf(false, true)) for (interrupted in listOf(false, true)) {
                        state.clear()
                        val diagnostic = RecoverySearchDiagnostic(solverCompleteness = SearchCompleteness.COMPLETE,
                            unresolvedCalculations = unresolved, completedSteps = 1,
                            incompleteReasons = if (interrupted) setOf(SearchIncompleteReason.USER_INTERRUPTED) else emptySet())
                        val candidates = if (withCandidate) listOf(candidate.copy(searchDiagnostic = diagnostic)) else emptyList()
                        val failure = if (withCandidate) null else RecoveryFailure(ErrorCorrectionLevel.M, 0,
                            30, 0, 30, RecoveryFailureStage.REED_SOLOMON, searchDiagnostic = diagnostic)
                        if (aggregated) {
                            val attempt = GeometryAttempt(GeometryHypothesis(emptyList(), geometry, 0.0), matrix,
                                MatrixSampler.SamplingStats(128.0, 64.0, 32.0), candidates, failure, diagnostic)
                            val result = AutoRecoveryResult(emptyList(), listOf(attempt))
                            state.autoResult = result
                            if (withCandidate) state.showResults(result)
                            else state.showAttempt(0, geometry, matrix, candidates, failure, -1)
                        } else {
                            state.restoreAttempt(geometry, matrix, matrix.copy())
                            state.updateRecoveries(candidates, if (withCandidate) 0 else -1, failure, diagnostic)
                        }
                        MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java)
                            .apply { isAccessible = true }.invoke(activity, null)
                        val completion = diagnostic.terminationText().lineSequence().first()
                        fun checkCount() {
                            val text = listOf("statusText", "contentPreviewText").joinToString("\n") {
                                (field(it).get(activity) as TextView).text.toString()
                            } + "\n" + diagnosticText(activity)
                            assertEquals(text, 1, text.split(completion).size - 1)
                            if (interrupted) assertTrue(text.contains(localizedText("利用者が探索を中断", "interrupted by the user")))
                        }
                        checkCount()
                        expandDiagnosticSections(activity)
                        checkCount()
                    }
                }
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
