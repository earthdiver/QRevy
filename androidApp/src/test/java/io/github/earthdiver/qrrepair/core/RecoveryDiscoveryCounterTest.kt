package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryDiscoveryCounterTest {
    private fun fixture(): Pair<QrMatrix, QrFormatConstraint> {
        val qr = Encoder.encode("DISCOVERY", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        return matrix to QrFormatConstraint(ErrorCorrectionLevel.L, qr.maskPattern)
    }
    private fun candidate(): RecoveryCandidate {
        val (matrix, format) = fixture()
        return RecoveryEngine().recover(matrix, formatConstraint = format).first { it.text == "DISCOVERY" }
    }

    @Test fun countsQrIdentitiesRatherThanTextOrDerivations() {
        val c = candidate()
        val events = mutableListOf<RecoveryDiscoveryCount>()
        val counter = RecoveryDiscoveryCounter(onChanged = events::add)
        listOf(c, c.copy(), c.copy(dataMask = (c.dataMask + 1) % 8),
            c.copy(ecLevel = ErrorCorrectionLevel.H), c.copy(version = c.version + 1),
            c.copy(correctedInterleaved = c.correctedInterleaved.copyOf().also { it[0] = it[0] xor 1 }))
            .forEach(counter::record)
        assertEquals(RecoveryDiscoveryCount.Exact(5), counter.snapshot)
        assertEquals(5, events.size)
    }

    @Test fun capacityBoundaryRemainsExactUntilAnotherDistinctQrCannotBeRecorded() {
        val c = candidate()
        val counter = RecoveryDiscoveryCounter(256L + 4L * c.correctedInterleaved.size)
        counter.record(c)
        counter.record(c.copy())
        assertEquals(RecoveryDiscoveryCount.Exact(1), counter.snapshot)
        counter.record(c.copy(dataMask = (c.dataMask + 1) % 8))
        assertEquals(RecoveryDiscoveryCount.AtLeast(2), counter.snapshot)
        repeat(3) { counter.record(c.copy(dataMask = (c.dataMask + 2) % 8)) }
        assertEquals(RecoveryDiscoveryCount.AtLeast(2), counter.snapshot)
    }

    @Test fun unparsedButStructurallyValidCandidatesCountAndRsOnlyDoesNot() {
        val c = candidate()
        val counter = RecoveryDiscoveryCounter()
        counter.record(c.copy(text = null, dataBytes = byteArrayOf(0xF0.toByte())))
        assertEquals(RecoveryDiscoveryCount.Exact(0), counter.snapshot)
        counter.record(c.copy(text = null))
        assertEquals(RecoveryDiscoveryCount.Exact(1), counter.snapshot)
    }

    @Test fun notificationsArriveInsideTheSearchBeforeRetentionAndSurviveRediscovery() {
        val (matrix, format) = fixture()
        var returned = false
        val events = mutableListOf<RecoveryDiscoveryCount>()
        val counter = RecoveryDiscoveryCounter(onChanged = {
            assertFalse("Discovery must not wait for search completion", returned)
            events += it
        })
        val engine = RecoveryEngine()
        repeat(2) {
            val outcome = engine.recoverWithDiagnostics(matrix.copy(), formatConstraint = format,
                retentionBudget = RecoveryRetentionBudget(0), discoveryCounter = counter)
            assertTrue(outcome.candidates.isEmpty())
            assertTrue(outcome.searchDiagnostic.candidatesOmitted)
            assertEquals(RecoveryDiscoveryCount.Exact(1), outcome.discoveredCandidates)
        }
        returned = true
        assertEquals(listOf(RecoveryDiscoveryCount.Exact(1)), events)
    }

    @Test fun countingLimitDoesNotStopRecoveryOrMarkItIncomplete() {
        val (matrix, format) = fixture()
        val outcome = RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format,
            discoveryCounter = RecoveryDiscoveryCounter(0))
        assertEquals(RecoveryDiscoveryCount.AtLeast(1), outcome.discoveredCandidates)
        assertTrue(outcome.candidates.any { it.text == "DISCOVERY" })
        assertEquals(SearchCompleteness.COMPLETE, outcome.searchDiagnostic.completeness)
    }

    @Test fun cooperativeStopRetainsTheLastDiscoveryAndANewRequestStartsAtZero() {
        val (matrix, format) = fixture()
        var stopped = false
        val counter = RecoveryDiscoveryCounter(onChanged = { stopped = true })
        val outcome = RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format,
            stopRequested = { stopped }, discoveryCounter = counter)
        assertTrue(stopped)
        assertEquals(RecoveryDiscoveryCount.Exact(1), outcome.discoveredCandidates)
        assertTrue(outcome.candidates.any { it.text == "DISCOVERY" })
        assertEquals(RecoveryDiscoveryCount.Exact(0), RecoveryDiscoveryCounter().snapshot)
    }
}
