package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SingleSegmentRetryUiTest {
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun drain(activity: MainActivity) {
        // Dialog buttons dispatch their listeners through the UI queue before submitting work.
        shadowOf(Looper.getMainLooper()).idle()
        (field("executor").get(activity) as ExecutorService).submit {}.get(60, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun settings(): PreprocessSettings {
        val qr = Encoder.encode("qrevy-a", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 0))
        val matrix = QrMatrix(21)
        for (y in 0 until 21) for (x in 0 until 21) matrix.set(x, y,
            if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        val coords = CodewordExtractor.extract(matrix, 1, 0).codewords.take(19).flatMap { it.coordinates }
        coords.drop(74).forEach { matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        val flipped = coords[68]
        matrix.set(flipped.x, flipped.y, if (matrix[flipped.x, flipped.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
            1f, ModuleSource.IMAGE)
        val edits = (0 until 21).flatMap { y -> (0 until 21).map { x -> Coord(x, y) to matrix[x, y].value } }.toMap()
        return PreprocessSettings(version = 1, logicalGridAssigned = true, automaticThresholds = false,
            searchQuadNeighborhood = false, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0),
            dataEccMismatchLimitMode = ErrorLimitMode.MANUAL, dataEccMismatchLimit = 1,
            preRecoveryEdits = edits, preRecoverySoftEdits = edits.keys,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
    }

    @Test fun acceptingTheOfferRunsOnceAndHistoryKeepsPreferenceOff() = withOffer { activity, dialog, saved ->
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain(activity)
        val state = field("recoveryState").get(activity) as RecoveryUiState
        assertTrue(state.recoveries.any { it.text == "qrevy-a" })
        assertTrue(state.oneTimeSingleSegment)
        assertEquals(saved, field("preprocessSettings").get(activity))
        val history = field("sessionHistory").get(activity) as RecoverySessionHistory
        val snapshot = history.current!!.snapshot
        assertTrue(snapshot.oneTimeSingleSegment)
        assertFalse(snapshot.preprocessSettings!!.trustKnownPayloadCount)
        assertTrue(state.selectedRecovery!!.displayConditions!!.trustSingleSegment)
        state.clear()
        MainActivity::class.java.getDeclaredMethod("restoreSnapshot", RecoveryHistoryEntry::class.java,
            String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            .invoke(activity, history.current!!, "Restore", null)
        drain(activity)
        assertTrue(state.recoveries.any { it.text == "qrevy-a" })
        assertTrue(state.oneTimeSingleSegment)
        assertEquals(saved, field("preprocessSettings").get(activity))
        MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply { isAccessible = true }.invoke(activity)
        val advanced = ShadowDialog.getLatestDialog() as AlertDialog
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        val option = views(advanced.window!!.decorView).filterIsInstance<CheckBox>()
            .single { it.text.toString() == "Force single-segment terminator and padding" }
        assertFalse(option.isChecked)
        advanced.dismiss()
    }

    @Test fun obsoleteOfferCannotLaunchAnotherRun() = withOffer { activity, dialog, _ ->
        val generation = field("workGeneration").get(activity) as AtomicInteger
        val editedGeneration = generation.incrementAndGet()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain(activity)
        assertEquals(editedGeneration, generation.get())
        assertFalse((field("recoveryState").get(activity) as RecoveryUiState).oneTimeSingleSegment)
    }

    private fun withOffer(check: (MainActivity, AlertDialog, PreprocessSettings) -> Unit) {
        val previousLanguage = AppLanguageManager.current
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            field("prepared").set(activity, BitmapUtils.prepare(bitmap))
            val saved = settings()
            field("preprocessSettings").set(activity, saved)
            MainActivity::class.java.getDeclaredMethod("startAutoRecovery").apply { isAccessible = true }.invoke(activity)
            drain(activity)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertEquals("Retry this time only", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertEquals(saved, field("preprocessSettings").get(activity))
            check(activity, dialog, saved)
        } finally {
            controller.pause().stop().destroy()
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), previousLanguage)
        }
    }
}
