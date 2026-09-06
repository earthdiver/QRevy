package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

/** Release gate: coverage across every Model 2 version/ECC combination plus deterministic damage fuzzing. */
class QrReleaseRegressionTest {
    private val levels = listOf(ErrorCorrectionLevel.L, ErrorCorrectionLevel.M, ErrorCorrectionLevel.Q, ErrorCorrectionLevel.H)

    @Test
    fun completeQrRecoversForEveryVersionAndEcLevel() {
        for (version in 1..40) for (ec in levels) {
            val text = "V$version-${ec.name}"
            val encoded = Encoder.encode(text, ec, mapOf(EncodeHintType.QR_VERSION to version))
            val matrix = toTriState(encoded.matrix)
            val ranked = FormatSolver.ranked(matrix)
            assertTrue("No format candidate for V$version-$ec", ranked.isNotEmpty())
            assertEquals("Wrong ECC ranking for V$version-$ec", ec, ranked.first().ecLevel)
            assertEquals("Wrong mask ranking for V$version-$ec", encoded.maskPattern, ranked.first().dataMask)
            val recovered = RecoveryEngine().recover(matrix, maxFormatCandidates = 1)
            assertTrue(
                "Complete QR failed V$version-$ec mask=${encoded.maskPattern}",
                recovered.any { it.text == text && it.version == version && it.ecLevel == ec && it.dataMask == encoded.maskPattern },
            )
        }
    }

    @Test
    fun deterministicErrorsAndErasuresStayInsideRsBudget() {
        val iterations = System.getProperty("qr.fuzz.iterations")?.toIntOrNull()?.coerceIn(1, 500) ?: 24
        val random = Random(0x51525236)
        repeat(iterations) { iteration ->
            val version = 1 + random.nextInt(40)
            val ec = levels[random.nextInt(levels.size)]
            val text = "F${iteration.toString(36)}"
            val encoded = Encoder.encode(text, ec, mapOf(EncodeHintType.QR_VERSION to version))
            val matrix = toTriState(encoded.matrix)
            val extraction = CodewordExtractor.extract(matrix, version, encoded.maskPattern)
            val de = QrBlockInterleaver.deinterleave(extraction, version, ec)
            val blockIndex = random.nextInt(de.blocks.size)
            val rawForBlock = de.rawLocations.indices.filter { de.rawLocations[it]?.blockIndex == blockIndex }.shuffled(random)
            val ecc = de.ecSymbolsPerBlock

            // Keep a safety margin and ensure 2*errors + erasures <= ECC symbols.
            val maxErasures = minOf(rawForBlock.size / 3, maxOf(1, ecc / 3))
            val erasures = random.nextInt(0, maxErasures + 1)
            val maxErrors = minOf((ecc - erasures) / 2, maxOf(0, rawForBlock.size - erasures))
            val errors = if (maxErrors == 0) 0 else random.nextInt(0, minOf(3, maxErrors) + 1)
            assertTrue(2 * errors + erasures <= ecc)

            for (raw in rawForBlock.take(erasures)) {
                extraction.codewords[raw].coordinates.forEach { c ->
                    matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
                }
            }
            for (raw in rawForBlock.drop(erasures).take(errors)) {
                val c = extraction.codewords[raw].coordinates[random.nextInt(8)]
                val old = matrix[c.x, c.y].value
                matrix.set(c.x, c.y, if (old == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE, 0.99f, ModuleSource.IMAGE)
            }

            val recovered = RecoveryEngine().recover(matrix, maxFormatCandidates = 1)
            assertTrue(
                "Damage recovery failed iter=$iteration V$version-$ec erasures=$erasures errors=$errors ecc=$ecc block=$blockIndex",
                recovered.any { it.text == text },
            )
        }
    }

    @Test
    fun runtimeStateRejectsNonFiniteOrOutOfDomainValues() {
        val matrix = QrMatrix(21)
        assertThrows(IllegalArgumentException::class.java) {
            matrix.set(0, 0, ModuleValue.ONE, Float.NaN, ModuleSource.IMAGE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            matrix.set(0, 0, ModuleValue.ONE, 1.1f, ModuleSource.IMAGE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GridGeometry(41, FinderRole.TOP_LEFT, 0.0, 0.0, 1.0, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GridGeometry(1, FinderRole.TOP_LEFT, 0.0, 0.0, 0.0, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GridGeometry(1, FinderRole.TOP_LEFT, Double.NaN, 0.0, 1.0, 0.0)
        }
    }

    @Test
    fun model2DimensionDomainIsExactlyVersionsOneThroughForty() {
        val valid = (1..40).map { 17 + 4 * it }.toSet()
        for (d in 1..220) {
            assertEquals("dimension $d", d in valid, runCatching { QrMatrix(d) }.isSuccess)
        }
    }

    private fun toTriState(source: com.google.zxing.qrcode.encoder.ByteMatrix): QrMatrix {
        val out = QrMatrix(source.width)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            out.set(x, y, if (source.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        return out
    }
}
