package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class QuickAutoWiderListTest {
    @Test fun listTimeRemainsCumulativeAcrossTrialsAndDoesNotCountOtherWork() {
        var now = 0L
        val repair = AutomaticScanBudget(clockMillis = { now })
        val lists = repair.listDecodingBudget()
        lists.measure { now += 400 }
        now += 1000
        assertEquals(400L, lists.elapsedMillis)
        assertFalse(lists.exhausted)
        lists.measure { now += 300 }
        assertTrue(lists.exhausted)
        assertFalse(repair.stopped())
        assertThrows(ListDecodingTimeLimitException::class.java) { lists.measure { fail("Expired allowance ran another trial") } }
    }

    private class Fixture(errors: Int = 10) {
        val text = "RADIUS"
        val qr = Encoder.encode(text, ErrorCorrectionLevel.H,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 0)).matrix
        val matrix = QrMatrix(qr.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        init {
            // Ten one-bit parity errors exceed the +1 radius (nine symbols), but fit the
            // automatic eleven-bit mismatch allowance. Payload assumptions cannot repair parity.
            for (word in CodewordExtractor.extract(matrix, 1, 0).codewords.drop(9).take(errors)) {
                val p = word.coordinates.last()
                matrix.set(p.x, p.y, if (matrix[p.x, p.y].value == ModuleValue.ONE)
                    ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.IMAGE)
            }
        }
        private val pitch = 5
        private val margin = 20
        private val size = matrix.dimension * pitch + margin * 2
        val image = LumaImage(size, size, IntArray(size * size) { i ->
            val x = (i % size - margin); val y = (i / size - margin)
            if (x < 0 || y < 0 || x / pitch >= matrix.dimension || y / pitch >= matrix.dimension) 255
            else if (matrix[x / pitch, y / pitch].value == ModuleValue.ONE) 0 else 255
        })
        val saved = PreprocessSettings(version = 1,
            manualQuad = ImageQuad(PointD(22.0, 22.0), PointD(122.0, 22.0),
                PointD(122.0, 122.0), PointD(22.0, 122.0)),
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.H, 0),
            automaticThresholds = false, blackMax = 40, whiteMin = 210,
            lmCorrectionEnabled = false, listDecodingAdditionalErrors = 3, knownBitErrorLimit = 2)
        fun run(stopAtWider: Boolean = false, expireAtWider: Boolean = false): QuickAutoRecoveryResult {
            val trace = AutomaticScanTrace { 0L }
            fun widerStarted() = trace.report().contains("WIDER_LIST: START")
            return QuickAutoRecovery().run(image, saved, AutomaticScanBudget(trace = trace,
                timeLimitsEnabled = expireAtWider,
                clockMillis = { if (expireAtWider && widerStarted()) 3000L else 0L },
                stopRequested = { stopAtWider && widerStarted() }))
        }
    }

    @Test fun widerRadiusRecoversParityDamageAfterOrdinaryTrialsWithoutChangingSavedSettings() {
        val f = Fixture()
        val smaller = RecoveryEngine().recoverWithDiagnostics(f.matrix,
            formatConstraint = f.saved.formatConstraint, maxKnownDataEccMismatches = 11,
            listDecodingAdditionalErrors = 1, executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_EXTENDED)
        assertTrue(smaller.candidates.none { it.text == f.text })
        val result = f.run()
        val report = result.diagnosticReport!!
        assertEquals(report, f.text, result.selected?.text)
        assertEquals(2, result.selected!!.displayConditions!!.listDecodingAdditionalErrors)
        assertEquals(0, result.selected.displayConditions.partialBitSearchLimit)
        assertEquals(10, result.selected.actualBlockMethods.single().listDecoding!!.radius)
        assertFalse(result.recovery.searchDiagnostic!!.knownBitSearchAttempted)
        val split = report.indexOf("WIDER_LIST: START")
        assertTrue(report, split > 0)
        assertFalse(report.substring(0, split).contains("listExtension=2"))
        assertTrue(report.substring(0, split).contains("policy=AUTOMATIC_EXTENDED"))
        assertTrue(report.substring(split).contains("listExtension=2"))
        assertFalse(report.substring(split).contains("listExtension=1"))
        assertEquals(2, result.recovery.entries.first { it.candidate.identity() == result.selected.identity() }
            .profile!!.settings.listDecodingAdditionalErrors)
        assertEquals(3, f.saved.listDecodingAdditionalErrors)
        assertEquals(2, f.saved.knownBitErrorLimit)
        assertFalse(f.saved.lmCorrectionEnabled)
    }

    @Test fun ordinarySuccessDoesNotStartWiderLists() {
        val result = Fixture(errors = 0).run()
        assertEquals("RADIUS", result.selected?.text)
        assertFalse(result.diagnosticReport!!.contains("WIDER_LIST: START"))
        assertFalse(result.diagnosticReport.contains("listExtension=2"))
    }

    @Test fun cancellationAtTheFallbackBoundaryPreventsWiderDecoding() {
        val result = Fixture().run(stopAtWider = true)
        assertNull(result.selected)
        assertTrue(result.diagnosticReport!!.contains("WIDER_LIST: START"))
        assertTrue(result.diagnosticReport.contains("reason=STOP_REQUESTED"))
        assertFalse(result.diagnosticReport.contains("listExtension=2"))
    }

    @Test fun theOriginalDeadlineStillAppliesAtTheFallbackBoundary() {
        val result = Fixture().run(expireAtWider = true)
        assertNull(result.selected)
        assertTrue(result.diagnosticReport!!.contains("WIDER_LIST: START"))
        assertTrue(result.diagnosticReport.contains("reason=TOTAL_TIME_LIMIT"))
        assertFalse(result.diagnosticReport.contains("listExtension=2"))
    }
}
