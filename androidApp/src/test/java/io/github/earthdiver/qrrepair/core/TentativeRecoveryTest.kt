package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class TentativeRecoveryTest {
    @Test fun wrongTentativeDataFunctionAndFormatBitsCanBeCorrectedButFixedBitsCannot() {
        val qr = Encoder.encode("TENTATIVE", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 2))
        val original = QrMatrix(qr.matrix.width)
        for (y in 0 until original.dimension) for (x in 0 until original.dimension) {
            original.set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val data = CodewordExtractor.extract(original, 2, qr.maskPattern).codewords.last().coordinates.first()
        val coords = listOf(data, Coord(0, 0), FormatEditPolicy.coordinates(2).first())
        val constraint = QrFormatConstraint(qr.ecLevel, qr.maskPattern)
        for (coord in coords) for (tentative in listOf(false, true)) {
            val wrong = if (original[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
            val settings = PreprocessSettings(version = 2, preRecoveryEdits = mapOf(coord to wrong),
                preRecoverySoftEdits = if (tentative) setOf(coord) else emptySet())
            val matrix = original.copy().apply { set(coord.x, coord.y, wrong, 1f, settings.sourceForPreRecoveryEdit(wrong, coord)) }
            val engine = RecoveryEngine()
            val results = engine.recover(matrix, formatConstraint = constraint, manualEdits = settings.fixedPreRecoveryEdits)
            assertEquals("coord=$coord tentative=$tentative", tentative, results.any { it.text == "TENTATIVE" })
            if (tentative) {
                val result = results.first { it.text == "TENTATIVE" }
                assertEquals(original[coord.x, coord.y].value,
                    RepairTraceBuilder.candidateMatrix(matrix, result, engine)[coord.x, coord.y].value)
                assertTrue(results.all { it.dataMask == qr.maskPattern && it.ecLevel == qr.ecLevel })
                assertEquals(ModuleSource.USER_SOFT, matrix.copy()[coord.x, coord.y].source)
                assertEquals(wrong, matrix[coord.x, coord.y].value)
                if (coord == data) assertTrue(RecoveryEngine().recover(matrix, formatConstraint = constraint,
                    manualEdits = settings.fixedPreRecoveryEdits, maxKnownDataEccMismatches = 0).isEmpty())
            }
        }
    }

    @Test fun globalCorrectionOptionRetainsFixedDesignationAndStillEnforcesZeroErrorLimit() {
        val qr = Encoder.encode("FIXED OPTION", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 2))
        val original = QrMatrix(qr.matrix.width)
        for (y in 0 until original.dimension) for (x in 0 until original.dimension) {
            original.set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val data = CodewordExtractor.extract(original, 2, qr.maskPattern).codewords.last().coordinates.first()
        val constraint = QrFormatConstraint(qr.ecLevel, qr.maskPattern)
        for (coord in listOf(data, Coord(0, 0))) {
            val wrong = if (original[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
            val settings = PreprocessSettings(version = 2, lockManualBits = false, preRecoveryEdits = mapOf(coord to wrong))
            assertEquals(mapOf(coord to wrong), settings.fixedPreRecoveryEdits)
            assertTrue(settings.effectiveSoftEdits.isEmpty())
            val matrix = original.copy().apply { set(coord.x, coord.y, wrong, 1f, settings.sourceForPreRecoveryEdit(wrong, coord)) }
            assertEquals(ModuleSource.USER_UNLOCKED, matrix[coord.x, coord.y].source)
            val engine = RecoveryEngine()
            val results = engine.recover(matrix, formatConstraint = constraint, manualEdits = settings.fixedPreRecoveryEdits)
            val result = results.first { it.text == "FIXED OPTION" }
            assertEquals(original[coord.x, coord.y].value, RepairTraceBuilder.candidateMatrix(matrix, result, engine)[coord.x, coord.y].value)
            assertEquals(wrong, matrix[coord.x, coord.y].value)
            assertTrue(results.all { it.dataMask == qr.maskPattern && it.ecLevel == qr.ecLevel })
            if (coord == data) assertTrue(RecoveryEngine().recover(matrix, formatConstraint = constraint,
                manualEdits = settings.fixedPreRecoveryEdits, maxKnownDataEccMismatches = 0).isEmpty())
            val protected = settings.copy(lockManualBits = true)
            matrix.set(coord.x, coord.y, wrong, 1f, protected.sourceForPreRecoveryEdit(wrong, coord))
            assertTrue(RecoveryEngine().recover(matrix, formatConstraint = constraint,
                manualEdits = protected.fixedPreRecoveryEdits).none { it.text == "FIXED OPTION" })
        }
    }

    @Test fun manualComparisonIncludesTentativeAndUnlockedValuesButExcludesUnknownAndFunctionCells() {
        val qr = Encoder.encode("TENTATIVE", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 2))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val coords = CodewordExtractor.extract(matrix, 2, qr.maskPattern).codewords[4].coordinates
        val sources = listOf(ModuleSource.USER, ModuleSource.USER_SOFT, ModuleSource.USER_UNLOCKED,
            ModuleSource.USER_SOFT, ModuleSource.USER_SOFT, ModuleSource.IMAGE)
        sources.forEachIndexed { index, source ->
            val coord = coords[index]
            val value = matrix[coord.x, coord.y].value
            matrix.set(coord.x, coord.y, when (index) {
                1, 2, 5 -> if (value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
                3 -> ModuleValue.ERASED
                else -> value
            }, 1f, source)
        }
        matrix.set(0, 0, matrix[0, 0].value, 1f, ModuleSource.USER)
        val candidate = RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(qr.ecLevel, qr.maskPattern))
            .first { it.text == "TENTATIVE" }

        assertEquals(4, candidate.manualDataEccCompared)
        assertEquals(2, candidate.manualDataEccMismatches)
        assertEquals(3, candidate.knownBitMismatchesAfterCorrection)
        assertTrue(candidate.inputComparisonText().contains("手動指定4bitとの不一致 2bit"))
        assertTrue(candidate.resultExplanationText().contains("手動指定と異なる値: 2bit"))
    }

    @Test fun settingsSnapshotDetachesTentativeCoordinatesAndKeepsFixedNeighbors() {
        val a = Coord(10, 10)
        val b = Coord(11, 10)
        val soft = mutableSetOf(a)
        val settings = PreprocessSettings(preRecoveryEdits = mapOf(a to ModuleValue.ZERO, b to ModuleValue.ONE), preRecoverySoftEdits = soft)
        val snapshot = settings.recoverySnapshot()
        soft.clear()
        assertEquals(setOf(a), snapshot.preRecoverySoftEdits)
        assertEquals(ModuleSource.USER_SOFT, snapshot.sourceForPreRecoveryEdit(ModuleValue.ZERO, a))
        assertEquals(ModuleSource.USER, snapshot.sourceForPreRecoveryEdit(ModuleValue.ONE, b))
        assertEquals(mapOf(b to ModuleValue.ONE), snapshot.fixedPreRecoveryEdits)
    }
}
