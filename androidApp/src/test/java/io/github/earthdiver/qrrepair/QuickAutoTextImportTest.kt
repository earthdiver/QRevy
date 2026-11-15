package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.zxing.EncodeHintType
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
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class QuickAutoTextImportTest {
    private fun text(damaged: Boolean = true, extraPriority: Int = 0): String {
        val marks = QrCellRegions(3).codewordCoordinates.take(extraPriority).toSet()
        val qr = Encoder.encode("TXT AUTO 42", ErrorCorrectionLevel.H,
            mapOf(EncodeHintType.QR_VERSION to 3, EncodeHintType.QR_MASK_PATTERN to 2)).matrix
        return (0 until qr.height).joinToString("\n") { y -> (0 until qr.width).map { x ->
            when {
                damaged && ((x == 28 && y == 28) || Coord(x, y) in marks) -> '*'
                damaged && (x == 8 || y == 8) -> '?'
                qr[x, y].toInt() == 1 -> '1'
                else -> '0'
            }
        }.joinToString("") }
    }

    private fun setup(enabled: Boolean = true) {
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        AutomaticRepairPreferences.save(RuntimeEnvironment.getApplication(), enabled)
    }

    private fun load(activity: MainActivity, text: String = text()) {
        val uri = Uri.parse("content://synthetic-text/automatic")
        shadowOf(activity.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(text.toByteArray()) }
        MainActivity::class.java.getDeclaredMethod("loadTextMatrix", Uri::class.java)
            .apply { isAccessible = true }.invoke(activity, uri)
    }

    private fun drain(activity: MainActivity) {
        repeat(5) {
            for (name in listOf("executor", "ioExecutor"))
                (field(activity, name) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun failedOrdinaryReadingRunsQuickRepairAndRestoresItsResultWithPriorityMarks() {
        setup()
        val bitmap = BitmapUtils.renderTextMatrixSource(QrTextMatrixParser.parse(text()).matrix)
        try { assertNull(decodeStandardQr(bitmap)) } finally { bitmap.recycle() }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            load(activity); drain(activity)
            assertEquals("TXT AUTO 42", (field(activity, "recoveryState") as RecoveryUiState).selectedRecovery?.text)
            val diagnostic = field(activity, "automaticDiagnostic") as AutomaticRepairDiagnosticSession
            assertTrue(diagnostic.textMatrix)
            assertFalse(diagnostic.baselineReport.contains("CURVE_GENERATION: START"))
            val snapshot = activity.workspaceSessionForTest().history.current!!.snapshot
            assertNotNull(snapshot.automaticScanAssumption)
            assertEquals(setOf(Coord(28, 28)), snapshot.preprocessSettings!!.preRecoveryPrioritySearch)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            controller.recreate()
            activity = controller.get(); drain(activity)
            assertEquals("TXT AUTO 42", (field(activity, "recoveryState") as RecoveryUiState).selectedRecovery?.text)
            val settings = field(activity, "preprocessSettings") as PreprocessSettings
            assertEquals(setOf(Coord(28, 28)), settings.preRecoveryPrioritySearch)
            assertEquals(ModuleValue.ERASED, settings.preRecoveryEdits[Coord(28, 28)])
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun disabledPreferenceOpensDetailedAnalysisWithoutQuickRepair() {
        setup(enabled = false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); load(activity); drain(activity)
            assertNull(field(activity, "automaticDiagnostic"))
            assertNotNull(field(activity, "preprocessEditor"))
            assertNull((field(activity, "recoveryState") as RecoveryUiState).selectedRecovery)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unsuccessfulQuickRepairShowsAReasonAndKeepsDiagnosticsAvailable() {
        setup()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            load(activity, List(21) { "?".repeat(21) }.joinToString("\n")); drain(activity)
            assertNotNull(field(activity, "automaticDiagnostic"))
            assertNull(field(activity, "preprocessEditor"))
            val state = field(activity, "recoveryState") as RecoveryUiState
            val retained = state.autoResult!!.entries
            assertEquals(retained.firstOrNull()?.candidate, state.selectedRecovery)
            if (retained.isNotEmpty()) assertNotNull(state.workingMatrix)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotEquals("Copy diagnostics", dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.text?.toString())
            assertTrue(shadowOf(dialog).message.toString().isNotBlank())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(field(activity, "preprocessEditor"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun automaticTextImportAcceptsMoreThanFourteenStarsAndRestoresWithoutTheDetailedLimit() {
        setup()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get(); load(activity, text(extraPriority = 32)); drain(activity)
            assertNotNull(field(activity, "automaticDiagnostic"))
            val snapshot = activity.workspaceSessionForTest().history.current!!.snapshot
            assertTrue(snapshot.preprocessSettings!!.preRecoveryPrioritySearch.size >= 32)
            assertEquals("TXT AUTO 42", (field(activity, "recoveryState") as RecoveryUiState).selectedRecovery?.text)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            controller.recreate(); activity = controller.get(); drain(activity)
            assertEquals("TXT AUTO 42", (field(activity, "recoveryState") as RecoveryUiState).selectedRecovery?.text)
            assertTrue((field(activity, "preprocessSettings") as PreprocessSettings).preRecoveryPrioritySearch.size >= 32)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ordinarySuccessStillBypassesQuickRepair() {
        setup()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); load(activity, text(damaged = false)); drain(activity)
            assertEquals(true, field(activity, "immediateResultShown"))
            assertNull(field(activity, "automaticDiagnostic"))
            assertFalse(activity.workspaceSessionForTest().history.entries.any { it.kind == HistoryEventKind.AUTO_RECOVERY })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun choosingDetailedAnalysisDuringOrdinarySuccessOpensTheEditorAndKeepsTheReadResult() {
        choosingDetailedAnalysisDuringImport(damaged = false)
    }

    @Test fun choosingDetailedAnalysisDuringImportSkipsQuickRepairAndKeepsMarks() {
        choosingDetailedAnalysisDuringImport(damaged = true)
    }

    private fun choosingDetailedAnalysisDuringImport(damaged: Boolean) {
        setup()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val release = CountDownLatch(1)
        try {
            val activity = controller.get()
            val started = CountDownLatch(1)
            (field(activity, "executor") as ExecutorService).execute { started.countDown(); release.await() }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            load(activity, text(damaged = damaged))
            val dialog = field(activity, "inputProgressDialog") as AlertDialog
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                .single { it.text.toString() == "Detailed analysis" }.performClick()
            release.countDown(); drain(activity)
            assertNull(field(activity, "automaticDiagnostic"))
            assertNotNull(field(activity, "preprocessEditor"))
            assertEquals(if (damaged) setOf(Coord(28, 28)) else emptySet<Coord>(),
                (field(activity, "preprocessSettings") as PreprocessSettings).preRecoveryPrioritySearch)
            if (!damaged) assertEquals(true, field(activity, "immediateResultShown"))
        } finally { release.countDown(); controller.pause().stop().destroy() }
    }

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
