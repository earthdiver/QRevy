package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class QuickAutoTextMatrixTest {
    private fun matrix(): QrMatrix {
        val encoded = Encoder.encode("TXT AUTO 42", ErrorCorrectionLevel.H,
            mapOf(EncodeHintType.QR_VERSION to 3, EncodeHintType.QR_MASK_PATTERN to 2)).matrix
        return QrMatrix(encoded.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (x == 8 || y == 8) ModuleValue.ERASED else if (encoded[x, y].toInt() == 1)
                    ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            set(28, 28, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            setPrioritySearch(28, 28, true)
        }
    }

    private fun settings() = PreprocessSettings(version = 3, polarity = PolarityMode.NORMAL,
        automaticThresholds = false, quietZoneCorrection = false, blackMax = 64, whiteMin = 192,
        manualQuad = ImageQuad(PointD(35.5, 35.5), PointD(259.5, 35.5), PointD(259.5, 259.5), PointD(35.5, 259.5)),
        logicalGridAssigned = false, preRecoveryEdits = mapOf(Coord(28, 28) to ModuleValue.ERASED),
        preRecoveryPrioritySearch = setOf(Coord(28, 28)), knownBitErrorLimit = 2)

    private fun render(matrix: QrMatrix): LumaImage {
        val side = (matrix.dimension + 8) * 8
        return LumaImage(side, side, IntArray(side * side) { i ->
            val x = i % side / 8 - 4; val y = i / side / 8 - 4
            if (x !in 0 until matrix.dimension || y !in 0 until matrix.dimension) 255 else when (matrix[x, y].value) {
                ModuleValue.ZERO -> 255
                ModuleValue.ONE -> 0
                ModuleValue.ERASED -> 128
            }
        })
    }

    @Test fun textRecoveryPreservesSamplesAndExplicitMarksAcrossRotationsAndMirrors() {
        val original = matrix()
        val saved = settings()
        for (turn in 0..3) for (mirror in listOf(false, true)) {
            val n = original.dimension
            val transformed = QrMatrix(n)
            for (y in 0 until n) for (x in 0 until n) {
                var a = if (mirror) y else x; var b = if (mirror) x else y
                repeat(turn) { val previous = a; a = n - 1 - b; b = previous }
                transformed.set(a, b, original[x, y].value, original[x, y].confidence, ModuleSource.IMAGE)
                if (original[x, y].prioritySearch) transformed.setPrioritySearch(a, b, true)
            }
            val marks = transformed.prioritySearchCoordinates().mapTo(linkedSetOf()) { Coord(it.first, it.second) }
            val trace = AutomaticScanTrace()
            val result = QuickAutoRecovery().runTextMatrix(render(transformed), saved.copy(
                preRecoveryEdits = marks.associateWith { ModuleValue.ERASED }, preRecoveryPrioritySearch = marks),
                AutomaticScanBudget(clockMillis = { 0L }, trace = trace))
            assertEquals(result.diagnosticReport, "TXT AUTO 42", result.selected?.text)
            assertEquals(0, result.selected!!.partialGuessedBits)
            assertFalse(result.recovery.searchDiagnostic!!.knownBitSearchAttempted)
            val entry = result.recovery.entries.first { it.candidate.identity() == result.selected.identity() }
            for (y in 0 until n) for (x in 0 until n)
                assertEquals("rotation=$turn mirror=$mirror cell=$x,$y", original[x, y].value, entry.attempt.matrix[x, y].value)
            assertEquals(original.prioritySearchCoordinates(), entry.attempt.matrix.prioritySearchCoordinates())
            assertFalse(entry.profile!!.settings.lmCorrectionEnabled)
            assertFalse(entry.profile.settings.distortionCorrection)
            assertFalse(trace.report().contains("LM: START"))
            assertFalse(trace.report().contains("CURVE_GENERATION: START"))
        }
        assertEquals(setOf(Coord(28, 28)), saved.preRecoveryPrioritySearch)
        assertEquals(ModuleValue.ERASED, saved.preRecoveryEdits[Coord(28, 28)])
        assertTrue((28 to 28) in original.prioritySearchCoordinates())
    }

    @Test fun fixedEditsCannotUseTheImportedTextException() {
        val saved = settings()
        for (value in listOf(ModuleValue.ZERO, ModuleValue.ONE)) {
            val fixed = saved.copy(preRecoveryEdits = mapOf(Coord(28, 28) to value), preRecoveryPrioritySearch = emptySet())
            assertThrows(IllegalArgumentException::class.java) { QuickAutoRecovery().runTextMatrix(render(matrix()), fixed) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickAutoRecovery().runTextMatrix(render(matrix()), saved.copy(photographicSource = true))
        }
    }

    @Test fun cancellationPreservesPriorityMarksAndStartsNoDecode() {
        val trace = AutomaticScanTrace()
        val saved = settings()
        val result = QuickAutoRecovery().runTextMatrix(render(matrix()), saved,
            AutomaticScanBudget(stopRequested = { true }, trace = trace))
        assertNull(result.selected)
        assertFalse(trace.report().contains("DECODE: START"))
        assertTrue(saved.preRecoveryPrioritySearch.isNotEmpty())
    }
}
