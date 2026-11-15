package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class QuickAutoSchedulingTest {
    private fun interruptedFirstTrial(eraseHeader: Boolean = false, totalTimeout: Boolean = false,
        userStop: Boolean = false, formatConstraint: QrFormatConstraint? = null,
        firstRun: Boolean = false): QuickAutoRecoveryResult {
        val qr = Encoder.encode("timed trial", ErrorCorrectionLevel.H,
            mapOf(EncodeHintType.QR_VERSION to 2, EncodeHintType.QR_MASK_PATTERN to 3)).matrix
        val matrix = QrMatrix(qr.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        if (eraseHeader) for (p in CodewordExtractor.extract(matrix, 2, 3).codewords.first().coordinates)
            matrix.set(p.x, p.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        val pitch = 5; val margin = 20; val n = matrix.dimension
        val size = n * pitch + margin * 2
        val image = LumaImage(size, size, IntArray(size * size) { i ->
            val px = i % size - margin; val py = i / size - margin
            val x = px / pitch; val y = py / pitch
            if (px < 0 || py < 0 || x >= n || y >= n) 255 else when (matrix[x, y].value) {
                ModuleValue.ZERO -> 255
                ModuleValue.ONE -> 0
                ModuleValue.ERASED -> 120
            }
        })
        val a = margin + (pitch - 1) / 2.0; val b = a + (n - 1) * pitch
        val settings = PreprocessSettings(version = 2,
            manualQuad = ImageQuad(PointD(a, a), PointD(b, a), PointD(b, b), PointD(a, b)),
            blackMax = 40, whiteMin = 210, automaticThresholds = false, photographicSource = true,
            lmCorrectionEnabled = false, formatConstraint = formatConstraint)
        var now = 0L
        var interrupted = false
        val trace = AutomaticScanTrace { now }
        fun decodingStarted() = trace.report().contains("DECODE: START policy=AUTOMATIC_BASIC assumption=NONE ")
        val budget = AutomaticScanBudget.forImport(firstRun, trace = trace, clockMillis = {
            if (!interrupted && decodingStarted()) {
                interrupted = true
                now = if (totalTimeout) 3000 else 201
            }
            now
        }, stopRequested = {
            (userStop && decodingStarted()) || trace.report().contains("LM: START") ||
                trace.report().contains("CURVE_GENERATION: START")
        })
        return QuickAutoRecovery().run(image, settings, budget)
    }

    @Test fun confirmedHeaderGetsPayloadAssumptionsBeforeImageCorrectionAfterASliceTimeout() {
        val result = interruptedFirstTrial()
        val report = result.diagnosticReport!!
        assertTrue(report, report.contains("stop=TRIAL_TIME_LIMIT candidates=Exact(value=0)"))
        assertEquals("timed trial", result.selected?.text)
        assertEquals(AutomaticScanAssumption.UTF8, result.assumption)
        assertTrue(report, report.contains("reason=TRIAL_TIME_LIMIT_WITH_CONFIRMED_HEADER"))
        assertFalse(report.contains("LM: START"))
        assertFalse(report.contains("CURVE_GENERATION: START"))
    }

    @Test fun startupAllowanceFinishesTheSameBasicTrialDespiteInitialOverhead() {
        val result = interruptedFirstTrial(firstRun = true)
        assertEquals("timed trial", result.selected?.text)
        assertEquals(AutomaticScanAssumption.NONE, result.assumption)
        assertFalse(result.diagnosticReport!!.contains("stop=TRIAL_TIME_LIMIT"))
        assertFalse(result.diagnosticReport.contains("LM: START"))
        assertTrue(result.diagnosticReport.contains("firstRun=true"))
    }

    @Test fun curveDeadlineInsideGenerationStillRunsLaterDecodingTrials() {
        var now = 0L
        var curveChecks = 0
        val trace = AutomaticScanTrace { now }
        val budget = AutomaticScanBudget(trace = trace, clockMillis = {
            if (trace.report().contains("CURVE_GENERATION: START") && ++curveChecks >= 3) now = 2100
            now
        })
        val image = LumaImage(125, 125, IntArray(125 * 125) { 120 })
        val settings = PreprocessSettings(version = 1, automaticThresholds = false, blackMax = 40, whiteMin = 210,
            manualQuad = ImageQuad(PointD(12.0, 12.0), PointD(112.0, 12.0), PointD(112.0, 112.0), PointD(12.0, 112.0)),
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0))
        val result = QuickAutoRecovery().run(image, settings, budget)
        val report = result.diagnosticReport!!
        val deferred = report.indexOf("DEFER reason=DECODE_RESERVE")
        assertTrue(report, deferred >= 0)
        assertTrue(report, report.indexOf("DECODE: START", deferred) > deferred)
        assertTrue(report, report.contains("policy=AUTOMATIC_EXTENDED"))
        assertFalse(report, report.contains("LM: START"))
        assertFalse(report, report.contains("reason=TOTAL_TIME_LIMIT"))
        assertNull(result.selected)
        assertEquals(900L, budget.remainingMillis)
    }

    @Test fun missingHeaderDoesNotPrioritizePayloadAssumptionsAfterATimeout() {
        val result = interruptedFirstTrial(eraseHeader = true)
        val report = result.diagnosticReport!!
        assertTrue(report, report.contains("stop=TRIAL_TIME_LIMIT"))
        assertFalse(report.contains("reason=TRIAL_TIME_LIMIT_WITH_CONFIRMED_HEADER"))
        assertFalse(report.contains("assumption=UTF8 "))
        assertNull(result.selected)
    }

    @Test fun totalDeadlineDoesNotStartAnotherAssumptionTrial() {
        val result = interruptedFirstTrial(totalTimeout = true)
        assertNull(result.selected)
        assertTrue(result.diagnosticReport!!.contains("reason=TOTAL_TIME_LIMIT"))
        assertFalse(result.diagnosticReport.contains("assumption=UTF8 "))
    }

    @Test fun anExplicitMatchingFormatAlsoAllowsHeaderBasedScheduling() {
        val result = interruptedFirstTrial(formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.H, 3))
        assertEquals("timed trial", result.selected?.text)
        assertTrue(result.diagnosticReport!!.contains("reason=TRIAL_TIME_LIMIT_WITH_CONFIRMED_HEADER"))
    }

    @Test fun conflictingFormatSelectionDoesNotUseTheImageConfirmedHeader() {
        val result = interruptedFirstTrial(formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L))
        assertNull(result.selected)
        assertFalse(result.diagnosticReport!!.contains("reason=TRIAL_TIME_LIMIT_WITH_CONFIRMED_HEADER"))
    }

    @Test fun userCancellationDoesNotStartAnotherAssumptionTrial() {
        val result = interruptedFirstTrial(userStop = true)
        assertNull(result.selected)
        assertTrue(result.diagnosticReport!!.contains("reason=STOP_REQUESTED"))
        assertFalse(result.diagnosticReport.contains("assumption=UTF8 "))
    }
}
