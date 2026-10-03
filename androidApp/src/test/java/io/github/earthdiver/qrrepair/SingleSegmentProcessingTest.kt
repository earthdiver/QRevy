package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog
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
class SingleSegmentProcessingTest {
    private val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 1))
    private val format = QrFormatConstraint(qr.ecLevel, qr.maskPattern)
    private fun matrix() = QrMatrix(qr.matrix.width).apply {
        for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
            if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
    }
    private fun setBit(matrix: QrMatrix, index: Int, value: Int?) {
        val coord = CodewordExtractor.extract(matrix, 1, qr.maskPattern).codewords[index / 8].coordinates[index % 8]
        val masked = value?.xor(if (CodewordExtractor.maskApplies(qr.maskPattern, coord.x, coord.y)) 1 else 0)
        matrix.set(coord.x, coord.y, when (masked) { 0 -> ModuleValue.ZERO; 1 -> ModuleValue.ONE; else -> ModuleValue.ERASED },
            if (value == null) 0f else 1f, ModuleSource.IMAGE)
    }


    @Test fun editingHeaderBetweenUnknownAndPriorityImmediatelyUpdatesTheOption() {
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val original = matrix()
            val cells = CodewordExtractor.extract(original, 1, qr.maskPattern).codewords.flatMap { it.coordinates }
            val headerCell = cells[2]
            val edits = cells.associateWith { if (it == headerCell) ModuleValue.ERASED else original[it.x, it.y].value }
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            MainActivity::class.java.getDeclaredField("prepared").apply { isAccessible = true }.set(activity, BitmapUtils.prepare(bitmap))
            MainActivity::class.java.getDeclaredField("preprocessSettings").apply { isAccessible = true }.set(activity,
                PreprocessSettings(version = 1, formatConstraint = format, preRecoveryEdits = edits,
                    logicalGridAssigned = true, manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0),
                        PointD(220.0, 220.0), PointD(20.0, 220.0))))
            MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply { isAccessible = true }.invoke(activity)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
            val all = views(dialog.window!!.decorView)
            val option = all.filterIsInstance<CheckBox>().first { it.text.contains("single segment") }
            val overlay = all.filterIsInstance<PreprocessOverlayView>().single()
            assertFalse(option.isEnabled)
            overlay.onEditCellSelected!!.invoke(headerCell.x, headerCell.y)
            all.filterIsInstance<TextView>().first { it.text.toString() == "*" }.performClick()
            assertTrue("Marking * must immediately enable header forcing", option.isEnabled)
            option.isChecked = true
            all.filterIsInstance<TextView>().first { it.text.toString() == "?" }.performClick()
            assertTrue("The selected condition must remain editable", option.isEnabled)
            assertTrue("Changing the input must not silently relax the condition", option.isChecked)
            all.filterIsInstance<TextView>().first { it.text.toString() == activity.getString(R.string.undo) }.performClick()
            assertTrue(option.isEnabled)
            all.filterIsInstance<TextView>().first { it.text.toString() == activity.getString(R.string.redo) }.performClick()
            assertTrue(option.isEnabled)
            assertTrue(option.isChecked)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun failedRecoveryReportsWhyForcingWasUnavailableInBothLanguages() {
        for ((status, header) in listOf(
            SingleSegmentStatus.INCOMPLETE_HEADER to null,
            SingleSegmentStatus.UNSUPPORTED_MODE to "0111000000000",
            SingleSegmentStatus.COUNT_EXCEEDS_CAPACITY to "0010111111111",
        )) {
            val input = matrix()
            (0 until 208).forEach { setBit(input, it, null) }
            header?.forEachIndexed { index, bit -> setBit(input, index, bit.digitToInt()) }
            val outcome = RecoveryEngine().recoverWithDiagnostics(input, trustKnownPayloadCount = true,
                formatConstraint = format, maxKnownDataEccMismatches = 0)
            assertTrue(outcome.candidates.isEmpty())
            assertEquals(setOf(status), outcome.searchDiagnostic.singleSegmentStatuses)
            val failure = requireNotNull(outcome.failure)
            for (language in AppLanguage.entries) {
                AppLanguageManager.applyTo(RuntimeEnvironment.getApplication(), language)
                val report = RecoveryDiagnosticPresenter(language == AppLanguage.JAPANESE).failureReport(failure, null,
                    RecoveryDiagnosticContext(0, outcome.searchDiagnostic.unsearched, false, outcome.searchDiagnostic, null))
                val processing = report.sections.single { it.stage == DiagnosticStage.PROCESSING }.summary
                assertTrue(processing.contains(status.trialDescription()))
                assertTrue(processing.contains("ON"))
                val off = RecoveryDiagnosticPresenter(language == AppLanguage.JAPANESE).failureReport(
                    failure.copy(displayConditions = failure.displayConditions!!.copy(trustSingleSegment = false)), null,
                    RecoveryDiagnosticContext(0, true, false, outcome.searchDiagnostic, null))
                assertFalse(off.sections[2].summary.contains(status.trialDescription()))
            }
        }
    }

    @Test fun priorityModeAndCountEnableForcingAndRetainHypothesisProvenance() {
        val input = matrix()
        val coords = CodewordExtractor.extract(input, 1, qr.maskPattern).codewords.flatMap { it.coordinates }
        // Contradict the suffix beyond RS's five-error capacity. Header trials must force it.
        for (index in 48 until 128) {
            val coord = coords[index]
            input.set(coord.x, coord.y, if (input[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
                1f, ModuleSource.IMAGE)
        }
        val engine = RecoveryEngine()
        for (index in listOf(2, 12)) setBit(input, index, null)
        assertFalse(engine.hasTrustablePayloadHeader(input, format))
        listOf(2, 12).forEach { index -> coords[index].let { input.setPrioritySearch(it.x, it.y, true) } }
        assertTrue(engine.hasTrustablePayloadHeader(input, format))
        val outcome = engine.recoverWithDiagnostics(input, trustKnownPayloadCount = true, formatConstraint = format)
        val routes = outcome.candidates.flatMap { listOf(it) + it.alternativeDerivations }
        val candidate = routes.first { it.text == "HELLO" && it.singleSegmentStatus == SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER }
        assertTrue(candidate.constraintForcedByUserSetting)
        assertTrue(candidate.constraintFilledBits > 0)
        assertEquals(2, candidate.priorityGuessedBits)
        assertTrue(candidate.priorityAssignments.keys.all { input[it.x, it.y].value == ModuleValue.ERASED })
        assertTrue(outcome.searchDiagnostic.singleSegmentStatuses.contains(SingleSegmentStatus.INCOMPLETE_HEADER))
        assertTrue(outcome.searchDiagnostic.singleSegmentStatuses.contains(SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER))
        assertTrue(candidate.usedMethodsText().contains(SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER.description()))
        val rejected = engine.recoverWithDiagnostics(input, trustKnownPayloadCount = true,
            formatConstraint = format, maxKnownDataEccMismatches = 0)
        assertTrue(rejected.candidates.isEmpty())
        assertNotNull(rejected.failure)
        assertTrue(rejected.searchDiagnostic.singleSegmentStatuses.contains(SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER))
        assertEquals(2, rejected.searchDiagnostic.priorityHeaderHypotheses.size)
        assertFalse(rejected.searchDiagnostic.singleSegmentProcessingText().contains(SingleSegmentStatus.INCOMPLETE_HEADER.trialDescription()))
        // Ordinary '?' in the header must still prevent enabling the option.
        setBit(input, 4, null)
        assertFalse(engine.hasTrustablePayloadHeader(input, format))
    }

    @Test fun appliedStructureWithNoChangesIsDistinguishedFromAnUnavailableHeader() {
        val candidate = RecoveryEngine().recover(matrix(), trustKnownPayloadCount = true, formatConstraint = format).first()
        assertEquals(SingleSegmentStatus.APPLIED, candidate.singleSegmentStatus)
        assertEquals(0, candidate.constraintFilledBits)
        assertTrue(candidate.constraintForcedByUserSetting)
        for (language in AppLanguage.entries) {
            AppLanguageManager.applyTo(RuntimeEnvironment.getApplication(), language)
            assertTrue(candidate.usedMethodsText().contains(if (language == AppLanguage.JAPANESE) "補完・変更は0bit" else "0 bits filled/changed"))
        }
    }

    @Test fun mixedTrialOutcomesDoNotDescribeTheOriginalQrModeAsUnsupported() {
        for (language in AppLanguage.entries) {
            AppLanguageManager.applyTo(RuntimeEnvironment.getApplication(), language)
            val ja = language == AppLanguage.JAPANESE
            val mixedSummary = if (ja) "適用できた試行と、適用できなかった試行" else "applicable in some trials and unavailable in others"
            for (unavailable in listOf(
                setOf(SingleSegmentStatus.UNSUPPORTED_MODE, SingleSegmentStatus.COUNT_EXCEEDS_CAPACITY),
                setOf(SingleSegmentStatus.INCOMPLETE_HEADER),
            )) {
                val statuses = unavailable + SingleSegmentStatus.APPLIED
                val text = RecoverySearchDiagnostic(singleSegmentStatuses = statuses).singleSegmentProcessingText()
                assertTrue(text.contains(if (ja) "各試行（ECC・マスク候補や * による仮定）" else "trial outcomes across ECC/mask candidates and * hypotheses"))
                assertTrue(text.contains(if (ja) "元のQRコードの種類を確定する診断ではありません" else "does not establish the original QR code's mode"))
                assertTrue(text.contains(mixedSummary))
                assertTrue(text.indexOf(mixedSummary) < text.indexOf(SingleSegmentStatus.APPLIED.trialDescription()))
                assertEquals(statuses.size, text.lineSequence().count { it.startsWith("• ") })
                for (status in statuses) assertTrue(text.lines().contains("• ${status.trialDescription()}"))
                assertFalse(text.contains(SingleSegmentStatus.UNSUPPORTED_MODE.description()))
            }
            for (status in SingleSegmentStatus.entries) {
                val text = RecoverySearchDiagnostic(singleSegmentStatuses = setOf(status)).singleSegmentProcessingText()
                assertFalse(text.contains(mixedSummary))
                assertEquals(listOf("• ${status.trialDescription()}"), text.lines().filter { it.startsWith("• ") })
            }
        }
    }

    @Test fun multipleHeaderHypothesesSummarizeApplicationWithoutListingIndividualFailures() {
        val reasons = setOf(SingleSegmentStatus.INCOMPLETE_HEADER, SingleSegmentStatus.UNSUPPORTED_MODE,
            SingleSegmentStatus.COUNT_EXCEEDS_CAPACITY)
        val failed = RecoverySearchDiagnostic(singleSegmentStatuses = reasons,
            priorityHeaderHypotheses = setOf("0010111111110", "0010111111111"), unsearched = true)
        val applied = RecoverySearchDiagnostic.combine(listOf(failed,
            RecoverySearchDiagnostic(singleSegmentStatuses = setOf(SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER),
                priorityHeaderHypotheses = setOf("0010000000101")), null))!!
        assertFalse(applied.coverageComplete)
        assertEquals(2, applied.priorityHeaderHypotheses.size)
        for (language in AppLanguage.entries) {
            AppLanguageManager.applyTo(RuntimeEnvironment.getApplication(), language)
            val ja = language == AppLanguage.JAPANESE
            val failedText = failed.singleSegmentProcessingText()
            val appliedText = applied.singleSegmentProcessingText()
            assertEquals(1, failedText.lineSequence().count { it.startsWith("• ") })
            assertEquals(1, appliedText.lineSequence().count { it.startsWith("• ") })
            assertTrue(failedText.contains(if (ja) "調べた範囲では" else "checked range"))
            assertTrue(failedText.contains(if (ja) "組合せは見つかりませんでした" else "no combination"))
            assertFalse(failedText.contains(if (ja) "確認・適用しました" else "checked/applied"))
            assertTrue(appliedText.contains(if (ja) "適用可能な試行で" else "in applicable trials"))
            assertTrue(appliedText.contains(if (ja) "仮定した値は画像から読み取れた値ではありません" else "hypothesized values were not observed"))
            for (reason in reasons) {
                assertFalse(failedText.contains(reason.trialDescription()))
                assertFalse(appliedText.contains(reason.trialDescription()))
            }
            val before = RecoverySearchDiagnostic(singleSegmentStatuses = reasons).singleSegmentProcessingText()
            assertTrue(before.contains(SingleSegmentStatus.INCOMPLETE_HEADER.trialDescription()))
            assertFalse(before.contains(SingleSegmentStatus.UNSUPPORTED_MODE.trialDescription()))
            assertFalse(before.contains(SingleSegmentStatus.COUNT_EXCEEDS_CAPACITY.trialDescription()))
        }
    }

    @Test fun rejectedPriorityCountsAreRecordedEvenWhenNoStructureCanBeApplied() {
        val input = matrix()
        (0 until 208).forEach { setBit(input, it, null) }
        "0010111111111".forEachIndexed { index, bit -> setBit(input, index, bit.digitToInt()) }
        setBit(input, 12, null)
        val coord = CodewordExtractor.extract(input, 1, qr.maskPattern).codewords[1].coordinates[4]
        input.setPrioritySearch(coord.x, coord.y, true)
        val outcome = RecoveryEngine().recoverWithDiagnostics(input, trustKnownPayloadCount = true,
            formatConstraint = format, maxKnownDataEccMismatches = 0)
        assertTrue(outcome.candidates.isEmpty())
        assertEquals(2, outcome.searchDiagnostic.priorityHeaderHypotheses.size)
        assertEquals(setOf(SingleSegmentStatus.INCOMPLETE_HEADER, SingleSegmentStatus.COUNT_EXCEEDS_CAPACITY),
            outcome.searchDiagnostic.singleSegmentStatuses)
        val report = RecoveryDiagnosticPresenter(true).failureReport(requireNotNull(outcome.failure), null,
            RecoveryDiagnosticContext(0, true, false, outcome.searchDiagnostic, null))
        assertFalse(report.sections[2].summary.contains(SingleSegmentStatus.COUNT_EXCEEDS_CAPACITY.trialDescription()))
        var stopped = false
        val interrupted = RecoveryEngine().recoverWithDiagnostics(input, trustKnownPayloadCount = true,
            formatConstraint = format, maxKnownDataEccMismatches = 0, stopRequested = { stopped },
            onPriorityProgress = { _, _ -> stopped = true })
        assertTrue(interrupted.searchDiagnostic.priorityHeaderHypotheses.isEmpty())
        assertTrue(interrupted.searchDiagnostic.singleSegmentProcessingText().contains(SingleSegmentStatus.INCOMPLETE_HEADER.trialDescription()))
    }

    @Test fun bodyGuessesDoNotCountAsHeaderHypothesesAndCountWidthsFollowVersion() {
        for (version in listOf(1, 10, 27)) {
            val data = intArrayOf(0x40, 0, 0)
            val known = IntArray(3)
            val headerUnknown = intArrayOf(0, 0x80, 0)
            assertNotNull(priorityHeaderHypothesis(data, known, headerUnknown, version))
            val lastByteUnknown = intArrayOf(0, 0, 0x80)
            if (version == 1) assertNull(priorityHeaderHypothesis(data, known, lastByteUnknown, version))
            else assertNotNull(priorityHeaderHypothesis(data, known, lastByteUnknown, version))
            assertNull(priorityHeaderHypothesis(data, known, intArrayOf(0, 0, 1), version))
            assertNull(priorityHeaderHypothesis(data, headerUnknown, headerUnknown, version))
        }
    }
}
