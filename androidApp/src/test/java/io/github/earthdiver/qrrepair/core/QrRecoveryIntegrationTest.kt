package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrRecoveryIntegrationTest {
    private val text = "MMA QR REPAIR TEST 42"

    @Test
    fun recoversCompleteQrAndIdentifiesFormat() {
        val encoded = Encoder.encode(
            text,
            ErrorCorrectionLevel.Q,
            mapOf(EncodeHintType.QR_VERSION to 2),
        )
        val matrix = toTriState(encoded.matrix)
        val candidates = RecoveryEngine().recover(matrix)
        val best = candidates.first { it.text == text }
        assertEquals(2, best.version)
        assertEquals(ErrorCorrectionLevel.Q, best.ecLevel)
        assertEquals(encoded.maskPattern, best.dataMask)
    }

    @Test
    fun fixedFormatRestrictsRecoveryToTheSelectedEccAndMask() {
        val encoded = Encoder.encode(
            text,
            ErrorCorrectionLevel.Q,
            mapOf(EncodeHintType.QR_VERSION to 2),
        )
        val matrix = toTriState(encoded.matrix)
        formatCoordinates(matrix.dimension).flatten().forEach { (x, y) ->
            matrix.set(x, y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        }
        val engine = RecoveryEngine()
        val selected = QrFormatConstraint(ErrorCorrectionLevel.Q, encoded.maskPattern)

        val recovered = engine.recover(matrix, formatConstraint = selected)

        assertTrue(recovered.any { it.text == text })
        assertTrue(recovered.all { it.ecLevel == selected.ecLevel && it.dataMask == selected.dataMask })
        assertEquals(1, engine.lastFormatAttempts)
    }

    @Test
    fun formatMismatchLimitFiltersAutomaticFormatsBeforeRsRepair() {
        val encoded = Encoder.encode(text, ErrorCorrectionLevel.Q, mapOf(EncodeHintType.QR_VERSION to 2))
        val matrix = toTriState(encoded.matrix)
        val damaged = formatCoordinates(matrix.dimension).first().first()
        val old = matrix[damaged.first, damaged.second].value
        matrix.set(
            damaged.first,
            damaged.second,
            if (old == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
            1f,
            ModuleSource.IMAGE,
        )

        val exactOnly = RecoveryEngine().recover(matrix, maxFormatMismatches = 0)
        val allowsOneError = RecoveryEngine().recover(matrix, maxFormatMismatches = 1)

        assertTrue(exactOnly.none { it.text == text })
        assertTrue(allowsOneError.any { it.text == text })
    }

    @Test
    fun dataEccMismatchLimitFiltersCandidatesAfterRsRepair() {
        val encoded = Encoder.encode(text, ErrorCorrectionLevel.Q, mapOf(EncodeHintType.QR_VERSION to 2))
        val matrix = toTriState(encoded.matrix)
        val extraction = CodewordExtractor.extract(matrix, 2, encoded.maskPattern)
        val damaged = extraction.codewords[0].coordinates[0]
        val old = matrix[damaged.x, damaged.y].value
        matrix.set(
            damaged.x,
            damaged.y,
            if (old == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
            1f,
            ModuleSource.IMAGE,
        )

        val exactEngine = RecoveryEngine()
        val exactOnly = exactEngine.recover(matrix, maxKnownDataEccMismatches = 0)
        val allowsOneError = RecoveryEngine().recover(matrix, maxKnownDataEccMismatches = 1)

        assertTrue(exactOnly.none { it.text == text })
        assertEquals(RecoveryFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT, exactEngine.lastFailure?.reason)
        assertEquals(null, exactEngine.lastFailure?.rsBitDiagnostic?.dimension)
        assertTrue(allowsOneError.any { it.text == text && it.knownBitMismatchesAfterCorrection == 1 })
    }

    @Test
    fun fullySpecifiedFormatIgnoresTheAutomaticFormatMismatchLimit() {
        val encoded = Encoder.encode(text, ErrorCorrectionLevel.Q, mapOf(EncodeHintType.QR_VERSION to 2))
        val matrix = toTriState(encoded.matrix)
        val damaged = formatCoordinates(matrix.dimension).first().first()
        val old = matrix[damaged.first, damaged.second].value
        matrix.set(
            damaged.first,
            damaged.second,
            if (old == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
            1f,
            ModuleSource.IMAGE,
        )

        val recovered = RecoveryEngine().recover(
            matrix,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.Q, encoded.maskPattern),
            maxFormatMismatches = 0,
        )

        assertTrue(recovered.any { it.text == text })
    }

    @Test
    fun eccAndMaskCanBeConstrainedIndependently() {
        val encoded = Encoder.encode(text, ErrorCorrectionLevel.Q, mapOf(EncodeHintType.QR_VERSION to 2))
        val matrix = toTriState(encoded.matrix)
        formatCoordinates(matrix.dimension).flatten().forEach { (x, y) ->
            matrix.set(x, y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        }

        val eccOnly = RecoveryEngine().recover(
            matrix,
            formatConstraint = QrFormatConstraint(ecLevel = ErrorCorrectionLevel.Q),
        )
        val maskOnly = RecoveryEngine().recover(
            matrix,
            formatConstraint = QrFormatConstraint(dataMask = encoded.maskPattern),
        )

        assertTrue(eccOnly.any { it.text == text })
        assertTrue(eccOnly.all { it.ecLevel == ErrorCorrectionLevel.Q })
        assertTrue(maskOnly.any { it.text == text })
        assertTrue(maskOnly.all { it.dataMask == encoded.maskPattern })
    }

    @Test
    fun provisionalWrongFormatFallsBackToTheRemainingCandidates() {
        val encoded = Encoder.encode(text, ErrorCorrectionLevel.Q, mapOf(EncodeHintType.QR_VERSION to 2))
        val matrix = toTriState(encoded.matrix)
        val actualFiveBits = (3 shl 3) or encoded.maskPattern // Q maps to format ECC bits 11.
        val wrongFiveBits = actualFiveBits xor 1
        val wrongCodeword = FormatSolver.formatCodeword(wrongFiveBits)
        formatCoordinates(matrix.dimension).forEach { copy ->
            copy.take(5).forEachIndexed { i, (x, y) ->
                val bit = (wrongCodeword shr (14 - i)) and 1
                matrix.set(x, y, if (bit == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            }
            val erased = copy[5]
            matrix.set(erased.first, erased.second, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }

        assertEquals(wrongFiveBits and 7, FormatSolver.matchingInformationCandidate(matrix)?.dataMask)
        val recovered = RecoveryEngine().recover(matrix)
        assertTrue(recovered.any { it.text == text && it.ecLevel == ErrorCorrectionLevel.Q && it.dataMask == encoded.maskPattern })
    }

    @Test
    fun bchTrustedFormatDoesNotFallBackAfterRsFailure() {
        val encoded = Encoder.encode(text, ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to 2))
        val matrix = toTriState(encoded.matrix)
        val extraction = CodewordExtractor.extract(matrix, 2, encoded.maskPattern)
        extraction.codewords.forEach { codeword ->
            codeword.coordinates.forEach { c ->
                matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }
        val engine = RecoveryEngine()

        val recovered = engine.recover(matrix)

        assertTrue(recovered.isEmpty())
        assertEquals(1, engine.lastFormatAttempts)
        assertEquals(ErrorCorrectionLevel.L, engine.lastFailure?.ecLevel)
        assertEquals(encoded.maskPattern, engine.lastFailure?.dataMask)
    }

    @Test
    fun recoversByteErasuresAndUnknownErrors() {
        val encoded = Encoder.encode(
            text,
            ErrorCorrectionLevel.Q,
            mapOf(EncodeHintType.QR_VERSION to 2),
        )
        val matrix = toTriState(encoded.matrix)
        val extraction = CodewordExtractor.extract(matrix, 2, encoded.maskPattern)

        // Version 2-Q has 22 ECC symbols in one block. 8 erasures + 5 unknown errors costs 18 symbols.
        for (cw in 0 until 8) {
            val c = extraction.codewords[cw].coordinates[0]
            matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        }
        for (cw in 8 until 13) {
            val c = extraction.codewords[cw].coordinates[1]
            val old = matrix[c.x, c.y].value
            matrix.set(
                c.x,
                c.y,
                if (old == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
                1f,
                ModuleSource.IMAGE,
            )
        }

        val recovered = RecoveryEngine().recover(matrix)
        assertTrue(recovered.any { it.text == text && it.ecLevel == ErrorCorrectionLevel.Q && it.dataMask == encoded.maskPattern })
    }

    @Test
    fun preservesTiedPartialBitSolutionsAsDistinctPayloadCandidates() {
        val encodedA = Encoder.encode("A", ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to 1))
        val encodedB = Encoder.encode("B", ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = toTriState(encodedA.matrix)
        val extractionA = CodewordExtractor.extract(matrix, 1, encodedA.maskPattern)
        val matrixB = toTriState(encodedB.matrix)
        val extractionB = CodewordExtractor.extract(matrixB, 1, encodedB.maskPattern)

        // Version 1-L has one RS block. A and B differ in one data bit plus seven ECC bytes.
        // Erasing exactly those differing bits makes both valid payloads equally consistent with
        // the observation; the bounded solver must not silently keep only its first solution.
        for (raw in extractionA.codewords.indices) {
            val a = extractionA.codewords[raw].value
            val b = extractionB.codewords[raw].value
            val difference = a xor b
            for (bit in 0 until 8) {
                if ((difference and (1 shl (7 - bit))) == 0) continue
                val coord = extractionA.codewords[raw].coordinates[bit]
                matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }

        val payloads = RecoveryEngine().recover(matrix).mapNotNull { it.text }.toSet()

        assertTrue(payloads.contains("A"))
        assertTrue(payloads.contains("B"))
    }

    @Test
    fun constrainedRsCoincidenceFallsBackToUnconstrainedPayloadRecovery() {
        val encoded = Encoder.encode("A", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = toTriState(encoded.matrix)
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)

        // Keep a complete but damaged byte-count header. Trusting that header forces a wrong
        // single-segment suffix which can still land on an RS codeword, but not a valid payload.
        val countBit = extraction.codewords[0].coordinates[7]
        val countValue = matrix[countBit.x, countBit.y].value
        matrix.set(
            countBit.x,
            countBit.y,
            if (countValue == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
            1f,
            ModuleSource.IMAGE,
        )
        extraction.codewords.slice(3..8).forEach { codeword ->
            codeword.coordinates.forEach { coordinate ->
                matrix.set(coordinate.x, coordinate.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }

        val recovered = RecoveryEngine().recover(
            matrix,
            maxFormatCandidates = 1,
            trustKnownPayloadCount = true,
        )

        assertTrue(recovered.any { it.text == "A" && it.constraintFilledBits == 0 })
    }

    @Test
    fun successfulAfterMatrixContainsNoUnknownDataOrPaddingModules() {
        val encoded = Encoder.encode("A", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = toTriState(encoded.matrix)
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)
        extraction.codewords.take(6).forEachIndexed { index, codeword ->
            codeword.coordinates.forEach { c ->
                matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, if (index == 0) ModuleSource.USER else ModuleSource.IMAGE)
            }
        }
        val pinned = extraction.codewords[6].coordinates.first()
        val pinnedValue = matrix[pinned.x, pinned.y].value
        matrix.set(pinned.x, pinned.y, pinnedValue, 1f, ModuleSource.USER)

        val candidate = RecoveryEngine().recover(matrix, trustKnownPayloadCount = true).first { it.text == "A" }
        val after = RepairTraceBuilder.candidateMatrix(matrix, candidate, RecoveryEngine())

        assertTrue(extraction.codewords.flatMap { it.coordinates }.all { after[it.x, it.y].value != ModuleValue.ERASED })
        assertEquals(ModuleSource.USER, after[pinned.x, pinned.y].source)
        assertEquals(pinnedValue, after[pinned.x, pinned.y].value)
    }

    @Test
    fun manualBitLockCanAllowOrRejectRsCorrection() {
        val encoded = Encoder.encode("LOCK", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 1))
        val original = toTriState(encoded.matrix)
        val extraction = CodewordExtractor.extract(original, 1, encoded.maskPattern)
        val coord = extraction.codewords[2].coordinates.first()
        val wrong = if (original[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE

        fun damaged(settings: PreprocessSettings): QrMatrix = original.copy().apply {
            set(coord.x, coord.y, wrong, 1f, settings.sourceForPreRecoveryEdit(wrong))
        }

        val unlocked = RecoveryEngine().recover(damaged(PreprocessSettings(lockManualBits = false)))
        val locked = RecoveryEngine().recover(damaged(PreprocessSettings(lockManualBits = true)))

        assertTrue(unlocked.any { it.text == "LOCK" })
        assertTrue(locked.none { it.text == "LOCK" })
    }

    private fun toTriState(source: com.google.zxing.qrcode.encoder.ByteMatrix): QrMatrix {
        val out = QrMatrix(source.width)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            out.set(
                x,
                y,
                if (source.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f,
                ModuleSource.IMAGE,
            )
        }
        return out
    }

    private fun renderLuma(matrix: QrMatrix, scale: Int, quiet: Int): LumaImage {
        val modules = matrix.dimension + quiet * 2
        val size = modules * scale
        val pixels = IntArray(size * size) { 255 }
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            val lum = if (matrix[x, y].value == ModuleValue.ONE) 0 else 255
            val sx = (x + quiet) * scale
            val sy = (y + quiet) * scale
            for (py in sy until sy + scale) for (px in sx until sx + scale) pixels[py * size + px] = lum
        }
        return LumaImage(size, size, pixels)
    }

    private fun formatCoordinates(d: Int): List<List<Pair<Int, Int>>> = listOf(
        listOf(
            0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8, 7 to 8, 8 to 8,
            8 to 7, 8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0,
        ),
        buildList {
            for (y in d - 1 downTo d - 7) add(8 to y)
            for (x in d - 8 until d) add(x to 8)
        },
    )
}
