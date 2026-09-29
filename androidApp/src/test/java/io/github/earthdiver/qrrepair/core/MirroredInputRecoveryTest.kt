package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class MirroredInputRecoveryTest {
    @Test fun mirroredSyntheticImageRecoversUnderSelectedGeometryAndFixedConstraints() {
        val text = "MIRROR INPUT 123"
        val qr = Encoder.encode(text, ErrorCorrectionLevel.L)
        val dimension = qr.matrix.width
        val version = (dimension - 17) / 4
        val scale = 8
        val padding = 32
        val width = dimension * scale + padding * 2
        val pixels = IntArray(width * width) { 255 }
        // Independent synthetic fixture, transposed around the top-left finder.
        for (y in 0 until dimension) for (x in 0 until dimension) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[(padding + y * scale + dy) * width + padding + x * scale + dx] =
                    if (qr.matrix.get(y, x).toInt() == 1) 0 else 255
            }
        }
        val image = LumaImage(width, width, pixels)
        val first = padding + (scale - 1) / 2.0
        val last = first + (dimension - 1) * scale
        val physical = ImageQuad(PointD(first, first), PointD(last, first), PointD(last, last), PointD(first, last))
        val cell = Coord(12, 9)
        val value = if (qr.matrix.get(cell.x, cell.y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO
        val settings = PreprocessSettings(version = version, mirroredInput = true,
            manualQuad = physical.transposed(), polarity = PolarityMode.NORMAL,
            formatConstraint = QrFormatConstraint(qr.ecLevel, qr.maskPattern),
            formatMismatchLimit = 0, dataEccMismatchLimit = 0,
            preRecoveryEdits = mapOf(cell to value), lockManualBits = true)
        val result = AutoRecoveryEngine().run(image, profile = SamplingProfile(settings, false))
        val candidates = result.attempts.flatMap { it.recoveries }
        assertTrue(candidates.any { it.payloadParsed && it.text == text })
        assertTrue(candidates.all { it.knownBitMismatchesAfterCorrection == 0 })
        assertEquals(ModuleSource.USER, result.attempts.first().matrix[cell.x, cell.y].source)
        val wrong = settings.copy(preRecoveryEdits = mapOf(cell to if (value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE))
        assertTrue(AutoRecoveryEngine().run(image, profile = SamplingProfile(wrong, false))
            .attempts.flatMap { it.recoveries }.isEmpty())
        assertTrue(AutoRecoveryEngine().run(image, profile = SamplingProfile(settings.copy(mirroredInput = false,
            manualQuad = physical, preRecoveryEdits = emptyMap()), false)).attempts.flatMap { it.recoveries }.isEmpty())
    }
}
