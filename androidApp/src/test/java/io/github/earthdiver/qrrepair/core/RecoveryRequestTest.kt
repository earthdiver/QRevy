package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryRequestTest {
    @Test fun settingsResolveTheActualGeometryLimitAndSnapshotOnlyFixedEvidence() {
        val fixed = Coord(10, 10)
        val soft = Coord(11, 10)
        val erased = Coord(12, 10)
        val edits = mutableMapOf(fixed to ModuleValue.ONE, soft to ModuleValue.ZERO, erased to ModuleValue.ERASED)
        val settings = PreprocessSettings(version = 1, dataEccMismatchLimitMode = ErrorLimitMode.AUTOMATIC,
            formatMismatchLimitMode = ErrorLimitMode.STRICT, preRecoveryEdits = edits,
            preRecoverySoftEdits = setOf(soft), lockManualBits = false,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.Q, 3))
        val request = RecoveryRequest.fromSettings(settings, version = 7)
        assertEquals(ErrorLimitPolicy.defaultData(7), request.maxKnownDataEccMismatches)
        assertNotEquals(settings.effectiveDataEccMismatchLimit, request.maxKnownDataEccMismatches)
        assertEquals(6, request.maxFormatMismatches)
        assertEquals(settings.formatConstraint, request.formatConstraint)
        assertEquals(false, request.displayLockManualBits)
        edits.clear()
        assertEquals(mapOf(fixed to ModuleValue.ONE), request.manualEdits)
    }

    @Test fun bothEntryPointsPreserveCandidateEvidenceAndReportedConditions() {
        val fixture = Fixture()
        val settings = PreprocessSettings(version = 2, formatConstraint = fixture.format,
            trustKnownPayloadCount = true, assumeUtf8ByteSegments = true, lockManualBits = false,
            formatMismatchLimit = 0, dataEccMismatchLimit = 0, partialBitSearchLimit = 13,
            listDecodingAdditionalErrors = 2, knownBitErrorLimit = 2)
        val engine = RecoveryEngine()
        val typed = engine.recoverWithDiagnostics(fixture.matrix, RecoveryRequest.fromSettings(settings))
        val legacy = engine.recoverWithDiagnostics(fixture.matrix, formatConstraint = fixture.format,
            trustKnownPayloadCount = true, assumeUtf8ByteSegments = true, displayLockManualBits = false,
            maxFormatMismatches = 0, maxKnownDataEccMismatches = 0, partialBitSearchLimit = 13,
            listDecodingAdditionalErrors = 2, knownBitErrorLimit = 2)
        val candidate = typed.candidates.single()
        assertEquals(fixture.text, candidate.text)
        assertArrayEquals(legacy.candidates.single().correctedInterleaved, candidate.correctedInterleaved)
        assertEquals(legacy.candidates.single().repairInfo, candidate.repairInfo)
        assertEquals(legacy.searchDiagnostic, typed.searchDiagnostic)
        assertEquals(legacy.discoveredCandidates, typed.discoveredCandidates)
        assertEquals(RecoveryDisplayConditions(true, false, fixture.format, 0, 0, 13, 2, 2, true),
            candidate.displayConditions)
    }

    @Test fun aReusableRequestDoesNotCarryAnEarlierStopOrDiscoveryStateIntoTheNextRun() {
        val fixture = Fixture()
        val request = RecoveryRequest(formatConstraint = fixture.format, maxKnownDataEccMismatches = 0)
        val engine = RecoveryEngine()
        val stopped = engine.recoverWithDiagnostics(fixture.matrix, request,
            RecoveryExecutionControls(stopRequested = { true }, stopIsTimeLimit = { true }))
        assertTrue(stopped.candidates.isEmpty())
        assertTrue(SearchIncompleteReason.TIME_LIMIT in stopped.searchDiagnostic.incompleteReasons)
        val complete = engine.recoverWithDiagnostics(fixture.matrix, request)
        assertEquals(fixture.text, complete.candidates.single().text)
        assertFalse(SearchIncompleteReason.TIME_LIMIT in complete.searchDiagnostic.incompleteReasons)
        assertEquals(RecoveryDiscoveryCount.Exact(1), complete.discoveredCandidates)
    }

    private class Fixture {
        val text = "REQUEST CONTRACT"
        private val qr = Encoder.encode(text, ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 2))
        val format = QrFormatConstraint(qr.ecLevel, qr.maskPattern)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
    }
}
