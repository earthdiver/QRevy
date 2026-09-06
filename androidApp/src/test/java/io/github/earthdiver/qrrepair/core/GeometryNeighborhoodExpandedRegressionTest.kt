package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/** A broader deterministic study than the compact displacement regression. */
class GeometryNeighborhoodExpandedRegressionTest {
    private val widths = (3..8).map { it / 10.0 }
    private val pixelsPerModule = 10
    private val quietModules = 4
    private val fixtures = listOf(
        Fixture(1, ErrorCorrectionLevel.H, "WIDTH-V1-H"),
        Fixture(7, ErrorCorrectionLevel.Q, "WIDTH-V7-Q"),
    )

    @Test
    fun comparesTransformFamiliesWidthPairsAndOneFinderRotation() {
        val casesByWidth = widths.associateWith { linkedSetOf<String>() }
        val familyWins = widths.associateWith { width ->
            Family.entries.associateWith { linkedSetOf<String>() }.toMutableMap()
        }
        val rotationCasesByWidth = widths.associateWith { linkedSetOf<String>() }
        val rotationCasesWithoutRotation = widths.associateWith { linkedSetOf<String>() }
        var ordinaryCaseCount = 0
        var rotationCaseCount = 0

        fixtures.forEach { fixture ->
            val prepared = prepare(fixture)
            widths.forEach { injectedWidth ->
                injectedPerturbations(prepared.truth, fixture.version, injectedWidth).forEach { injected ->
                    ordinaryCaseCount++
                    val caseId = "${fixture.version}/$injectedWidth/${injected.name}"
                    widths.forEach { searchWidth ->
                        val variants = manualQuadNeighborhood(injected.quad, fixture.version, searchWidth)
                        var any = false
                        familyRanges.forEach { (family, range) ->
                            if (range.any { index -> recovers(prepared, variants[index]) }) {
                                familyWins.getValue(searchWidth).getValue(family) += caseId
                                any = true
                            }
                        }
                        if (any) casesByWidth.getValue(searchWidth) += caseId
                    }
                }

                FinderRole.entries.forEach { role ->
                    for (sign in listOf(-1.0, 1.0)) {
                        rotationCaseCount++
                        val input = rotateAboutLogicalCorner(
                            prepared.truth,
                            fixture.version,
                            role,
                            injectedWidth * sign,
                        )
                        val caseId = "${fixture.version}/$injectedWidth/$role/${if (sign < 0) "CCW" else "CW"}"
                        widths.forEach { searchWidth ->
                            val ordinary = manualQuadNeighborhood(input, fixture.version, searchWidth)
                            if (ordinary.any { recovers(prepared, it) }) {
                                rotationCasesWithoutRotation.getValue(searchWidth) += caseId
                            }
                            val rotations = listOf(-searchWidth, searchWidth).map {
                                rotateAboutLogicalCorner(input, fixture.version, role, it)
                            }
                            if ((ordinary + rotations).any { recovers(prepared, it) }) {
                                rotationCasesByWidth.getValue(searchWidth) += caseId
                            }
                        }
                    }
                }
            }
        }

        val pairScores = buildMap {
            widths.forEachIndexed { firstIndex, first ->
                widths.drop(firstIndex + 1).forEach { second ->
                    put(first to second, (casesByWidth.getValue(first) + casesByWidth.getValue(second)).size)
                }
            }
        }
        val baselineCases = familyWins.values.flatMap { it.getValue(Family.BASELINE) }.toSet()
        val familyAllWidths = Family.entries.associateWith { family ->
            familyWins.values.flatMap { it.getValue(family) }.toSet()
        }
        val familyBeyondBaseline = familyAllWidths.mapValues { (_, cases) -> cases - baselineCases }
        val report = buildString {
            append("ordinary denominator=").append(ordinaryCaseCount)
            append(" widthScores=").append(casesByWidth.mapValues { it.value.size })
            append(" familyWins=").append(familyWins.mapValues { entry -> entry.value.mapValues { it.value.size } })
            append(" familyAllWidths=").append(familyAllWidths.mapValues { it.value.size })
            append(" familyBeyondBaseline=").append(familyBeyondBaseline.mapValues { it.value.size })
            append(" pairScores=").append(pairScores)
            append(" rotation denominator=").append(rotationCaseCount)
            append(" withoutRotation=").append(rotationCasesWithoutRotation.mapValues { it.value.size })
            append(" withRotation=").append(rotationCasesByWidth.mapValues { it.value.size })
        }
        assertEquals(report, 216, ordinaryCaseCount)
        assertEquals(report, casesByWidth.values.maxOf { it.size }, casesByWidth.getValue(0.6).size)
        assertEquals(report, ordinaryCaseCount, pairScores.getValue(0.3 to 0.6))
        assertEquals(report, pairScores.values.maxOrNull(), pairScores[0.3 to 0.6])
        assertTrue(report, familyBeyondBaseline.getValue(Family.DIAGONAL).size >=
            familyBeyondBaseline.getValue(Family.AXIS).size)
        assertEquals(report, 72, rotationCaseCount)
        // Partial-bit RS now also recovers all 72 ordinary 0.6-width rotation cases. Additional
        // rotation hypotheses must never regress that result and must still help at some width.
        assertTrue(report, rotationCasesByWidth.getValue(0.6).size >=
            rotationCasesWithoutRotation.getValue(0.6).size)
        assertTrue(report, widths.any { rotationCasesByWidth.getValue(it).size > rotationCasesWithoutRotation.getValue(it).size })
    }

    @Test
    fun comparesCorrectAndIncorrectManualBitsWithLockOnAndOff() {
        data class Counts(
            var correctLocked: Int = 0,
            var correctUnlocked: Int = 0,
            var wrongLocked: Int = 0,
            var wrongUnlocked: Int = 0,
        )
        val counts = Counts()
        var cases = 0
        fixtures.forEach { fixture ->
            val encoded = Encoder.encode(
                fixture.text,
                fixture.ec,
                mapOf(EncodeHintType.QR_VERSION to fixture.version),
            )
            val original = toMatrix(encoded.matrix)
            val extraction = CodewordExtractor.extract(original, fixture.version, encoded.maskPattern)
            extraction.codewords.take(12).forEachIndexed { selectedIndex, codeword ->
                val coord = codeword.coordinates.first()
                val correct = original[coord.x, coord.y].value
                val wrong = if (correct == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
                for (erasedCodewords in listOf(0, 2, 4)) {
                    cases++
                    fun succeeds(value: ModuleValue, locked: Boolean): Boolean {
                        val settings = PreprocessSettings(lockManualBits = locked)
                        val matrix = original.copy()
                        extraction.codewords.withIndex()
                            .filter { it.index != selectedIndex }
                            .take(erasedCodewords)
                            .forEach { (_, erased) ->
                                erased.coordinates.forEach { erasedCoord ->
                                    matrix.set(
                                        erasedCoord.x,
                                        erasedCoord.y,
                                        ModuleValue.ERASED,
                                        0f,
                                        ModuleSource.IMAGE,
                                    )
                                }
                            }
                        matrix.set(coord.x, coord.y, value, 1f, settings.sourceForPreRecoveryEdit(value))
                        return RecoveryEngine().recover(matrix, maxFormatCandidates = 1).any { it.text == fixture.text }
                    }
                    if (succeeds(correct, true)) counts.correctLocked++
                    if (succeeds(correct, false)) counts.correctUnlocked++
                    if (succeeds(wrong, true)) counts.wrongLocked++
                    if (succeeds(wrong, false)) counts.wrongUnlocked++
                }
            }
        }
        assertEquals(72, cases)
        assertEquals(cases, counts.correctLocked)
        assertEquals(cases, counts.correctUnlocked)
        assertEquals(0, counts.wrongLocked)
        assertEquals(cases, counts.wrongUnlocked)
    }

    private fun injectedPerturbations(quad: ImageQuad, version: Int, modules: Double): List<NamedQuad> {
        val points = quad.asList()
        val pitch = inferredPitch(quad, version)
        val offset = modules * pitch
        val diagonal = offset / sqrt(2.0)
        val center = center(points)
        fun translated(name: String, dx: Double, dy: Double) =
            NamedQuad(name, from(points.map { PointD(it.x + dx, it.y + dy) }))
        fun scaled(name: String, displacement: Double): NamedQuad {
            val meanRadius = points.map { it.distanceTo(center) }.average()
            return NamedQuad(name, from(points.map { center + (it - center) * (1.0 + displacement / meanRadius) }))
        }
        val result = mutableListOf(
            translated("AXIS_LEFT", -offset, 0.0),
            translated("AXIS_RIGHT", offset, 0.0),
            translated("AXIS_UP", 0.0, -offset),
            translated("AXIS_DOWN", 0.0, offset),
            translated("DIAGONAL_TL", -diagonal, -diagonal),
            translated("DIAGONAL_TR", diagonal, -diagonal),
            translated("DIAGONAL_BR", diagonal, diagonal),
            translated("DIAGONAL_BL", -diagonal, diagonal),
            scaled("SCALE_IN", -offset),
            scaled("SCALE_OUT", offset),
        )
        points.indices.forEach { index ->
            val vector = points[index] - center
            val length = vector.distanceTo(PointD(0.0, 0.0)).coerceAtLeast(.001)
            val unit = PointD(vector.x / length, vector.y / length)
            for (sign in listOf(-1.0, 1.0)) {
                val changed = points.toMutableList()
                changed[index] = changed[index] + unit * (offset * sign)
                result += NamedQuad("CORNER_${index}_${if (sign < 0) "IN" else "OUT"}", from(changed))
            }
        }
        return result
    }

    private fun prepare(fixture: Fixture): Prepared {
        val encoded = Encoder.encode(
            fixture.text,
            fixture.ec,
            mapOf(EncodeHintType.QR_VERSION to fixture.version),
        )
        val truth = trueQuad(encoded.matrix.width)
        return Prepared(
            fixture,
            renderCenterEvidence(encoded.matrix),
            truth,
            SamplingProfile(
                PreprocessSettings(
                    version = fixture.version,
                    manualQuad = truth,
                    blackMax = 80,
                    whiteMin = 175,
                    thresholdMode = ThresholdMode.GLOBAL,
                ),
                inverted = false,
            ),
        )
    }

    private fun recovers(prepared: Prepared, quad: ImageQuad): Boolean {
        val pitch = inferredPitch(quad, prepared.fixture.version)
        val geometry = GridGeometry(
            prepared.fixture.version,
            FinderRole.TOP_LEFT,
            quad.topLeft.x,
            quad.topLeft.y,
            pitch,
            0.0,
            quad,
            GeometryModel.MANUAL_PROJECTIVE,
        ).reanchored()
        val matrix = MatrixSampler(prepared.image, prepared.profile).sample(geometry).first
        return RecoveryEngine().recover(matrix, maxFormatCandidates = 1).any { it.text == prepared.fixture.text }
    }

    private fun renderCenterEvidence(matrix: ByteMatrix): LumaImage {
        val modules = matrix.width + quietModules * 2
        val size = modules * pixelsPerModule
        val pixels = IntArray(size * size) { 128 }
        val halfPatch = 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            val value = if (matrix.get(x, y).toInt() == 1) 0 else 255
            val centerX = (x + quietModules) * pixelsPerModule + pixelsPerModule / 2
            val centerY = (y + quietModules) * pixelsPerModule + pixelsPerModule / 2
            for (py in centerY - halfPatch..centerY + halfPatch) {
                for (px in centerX - halfPatch..centerX + halfPatch) pixels[py * size + px] = value
            }
        }
        return LumaImage(size, size, pixels)
    }

    private fun toMatrix(source: ByteMatrix): QrMatrix {
        val matrix = QrMatrix(source.width)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            matrix.set(
                x,
                y,
                if (source.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f,
                ModuleSource.IMAGE,
            )
        }
        return matrix
    }

    private fun trueQuad(dimension: Int): ImageQuad {
        val first = (quietModules + .5) * pixelsPerModule
        val last = (quietModules + dimension - .5) * pixelsPerModule
        return ImageQuad(PointD(first, first), PointD(last, first), PointD(last, last), PointD(first, last))
    }

    private fun inferredPitch(quad: ImageQuad, version: Int): Double {
        val dimension = 17 + 4 * version
        return (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (dimension - 1))
    }

    private fun center(points: List<PointD>) = PointD(points.map { it.x }.average(), points.map { it.y }.average())
    private fun from(points: List<PointD>) = ImageQuad(points[0], points[1], points[2], points[3])

    private data class Fixture(val version: Int, val ec: ErrorCorrectionLevel, val text: String)
    private data class Prepared(
        val fixture: Fixture,
        val image: LumaImage,
        val truth: ImageQuad,
        val profile: SamplingProfile,
    )
    private data class NamedQuad(val name: String, val quad: ImageQuad)
    private enum class Family { BASELINE, AXIS, DIAGONAL, SCALE, CORNER }

    private val familyRanges = mapOf(
        Family.BASELINE to 0..0,
        Family.AXIS to 1..4,
        Family.DIAGONAL to 5..8,
        Family.SCALE to 9..10,
        Family.CORNER to 11..18,
    )
}
