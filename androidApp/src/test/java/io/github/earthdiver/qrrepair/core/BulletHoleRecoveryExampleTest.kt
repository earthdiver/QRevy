package io.github.earthdiver.qrrepair.core

import android.graphics.Bitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BulletHoleRecoveryExampleTest {
    private val example = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .map { File(it, "docs/examples/bullet-holes") }.first { it.isDirectory }
    private val text = "QRevy app: recovered QR."
    private val priority = Coord(28, 28)

    private fun image(): LumaImage {
        val bitmap = ImageIO.read(File(example, "before.png"))
        val rgb = bitmap.getRGB(0, 0, bitmap.width, bitmap.height, null, 0, bitmap.width)
        return LumaImage(bitmap.width, bitmap.height, IntArray(rgb.size) {
            val p = rgb[it]
            (299 * ((p ushr 16) and 255) + 587 * ((p ushr 8) and 255) + 114 * (p and 255) + 500) / 1000
        })
    }

    private fun edits(): Map<Coord, ModuleValue> = Regex("""\[\s*(\d+),\s*(\d+),\s*"([01])",\s*true\s*]""")
        .findAll(File(example, "soft-edits.json").readText()).associate {
            Coord(it.groupValues[1].toInt(), it.groupValues[2].toInt()) to
                if (it.groupValues[3] == "1") ModuleValue.ONE else ModuleValue.ZERO
        }

    private fun settings(single: Boolean, usePriority: Boolean = true): PreprocessSettings {
        val bitmap = ImageIO.read(File(example, "before.png"))
        val rgb = bitmap.getRGB(0, 0, bitmap.width, bitmap.height, null, 0, bitmap.width)
        val initial = requireNotNull(InitialThresholdEstimator.estimate(
            Bitmap.createBitmap(rgb, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)))
        val edits = edits()
        return PreprocessSettings(version = 3, automaticThresholds = true,
            blackMax = initial.blackMax, whiteMin = initial.whiteMin,
            manualQuad = ImageQuad(PointD(108.0, 108.0), PointD(780.0, 108.0),
                PointD(780.0, 780.0), PointD(108.0, 780.0)),
            trustKnownPayloadCount = single, preRecoveryEdits = edits +
                if (usePriority) mapOf(priority to ModuleValue.ERASED) else emptyMap(),
            preRecoverySoftEdits = edits.keys,
            preRecoveryPrioritySearch = if (usePriority) setOf(priority) else emptySet()).withInitialErrorLimits()
    }

    private fun recover(single: Boolean, usePriority: Boolean = true): AutoRecoveryResult =
        AutoRecoveryEngine().run(image(), profile = SamplingProfile(settings(single, usePriority), false))

    private fun completed(result: AutoRecoveryResult) {
        val diagnostic = requireNotNull(result.searchDiagnostic)
        assertTrue(diagnostic.coverageComplete)
        assertFalse(diagnostic.unsearched)
        assertFalse(diagnostic.candidatesOmitted)
        assertFalse(diagnostic.derivationsOmitted)
        assertTrue(diagnostic.incompleteReasons.isEmpty())
    }

    @Test fun imageEvidenceDefinesErasuresAndVisibleColorSoftEdits() {
        val image = ImageIO.read(File(example, "before.png"))
        val matrix = QrTextMatrixParser.parse(File(example, "before.txt").readText()).matrix
        assertEquals(503, matrix.erasureCount())
        assertEquals(listOf(28 to 28), matrix.prioritySearchCoordinates())
        for (y in 0 until 29) for (x in 0 until 29) {
            val allUnknown = listOf(4, 12, 20).all { dy -> listOf(4, 12, 20).all { dx ->
                image.getRGB((x + 4) * 24 + dx, (y + 4) * 24 + dy) and 0xFFFFFF == 0x808080
            } }
            assertEquals("Cell $x,$y", allUnknown, matrix[x, y].value == ModuleValue.ERASED)
        }
        val settings = settings(single = true)
        val geometry = GridGeometry(3, FinderRole.TOP_LEFT, 0.0, 0.0, 24.0, 0.0,
            settings.manualQuad, GeometryModel.MANUAL_PROJECTIVE).reanchored()
        val (sampled, stats) = MatrixSampler(image(), SamplingProfile(settings, false)).sample(geometry)
        assertEquals(ThresholdPair(4, 229), stats.thresholds)
        assertEquals(62, settings.preRecoverySoftEdits.size)
        for ((coord, value) in settings.preRecoveryEdits.filterValues { it != ModuleValue.ERASED }) {
            assertEquals(ModuleValue.ERASED, sampled[coord.x, coord.y].value)
            var black = 0
            var white = 0
            for (dy in 0 until 24) for (dx in 0 until 24) {
                when (image.getRGB((coord.x + 4) * 24 + dx, (coord.y + 4) * 24 + dy) and 0xFFFFFF) {
                    0 -> black++
                    0xFFFFFF -> white++
                }
            }
            assertTrue(black != white)
            assertEquals(if (black > white) ModuleValue.ONE else ModuleValue.ZERO, value)
        }
    }

    @Test fun defaultSettingsRecoverOneExactCandidateThroughPartialBitConstraints() {
        val defaults = settings(single = true)
        assertNull(defaults.formatConstraint)
        assertEquals(5, defaults.effectiveDataEccMismatchLimit)
        assertEquals(6, defaults.effectiveFormatMismatchLimit)
        assertEquals(4, defaults.prioritySearchLimit)
        assertEquals(12, defaults.partialBitSearchLimit)
        assertEquals(1, defaults.listDecodingAdditionalErrors)
        val result = recover(single = true)
        completed(result)
        val candidate = result.entries.single().candidate
        val encoded = Encoder.encode(text, ErrorCorrectionLevel.M,
            mapOf(EncodeHintType.QR_VERSION to 3, EncodeHintType.QR_MASK_PATTERN to 3))
        val original = QrMatrix(29)
        for (y in 0 until 29) for (x in 0 until 29) {
            original.set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                1f, ModuleSource.IMAGE)
        }
        assertEquals(text, candidate.text)
        assertArrayEquals(CodewordExtractor.extract(original, 3, 3).codewords.map { it.value }.toIntArray(),
            candidate.correctedInterleaved)
        assertEquals(setOf(priority), candidate.priorityAssignments.keys)
        assertEquals(SingleSegmentStatus.APPLIED_WITH_PRIORITY_HEADER, candidate.singleSegmentStatus)
        assertTrue(candidate.constraintForcedByUserSetting)
        assertTrue(candidate.actualBlockMethods.single().bitConstraintSolved)
        assertNull(candidate.actualBlockMethods.single().listDecoding)
        assertEquals(1, candidate.knownBitMismatchesAfterCorrection)
        assertEquals(0, candidate.partialGuessedBits)
    }

    @Test fun disablingTheSingleSegmentAssumptionReturnsNoCandidates() {
        val result = recover(single = false)
        completed(result)
        assertTrue(result.entries.isEmpty())
    }

    @Test fun removingThePriorityMarkReturnsNoCandidates() {
        val result = recover(single = true, usePriority = false)
        completed(result)
        assertTrue(result.entries.isEmpty())
    }

    @Test fun conventionalRsRemainsUnavailableAfterPriorityAndSuffixAssignments() {
        for (value in listOf(ModuleValue.ZERO, ModuleValue.ONE)) {
            val matrix = QrTextMatrixParser.parse(File(example, "before.txt").readText()).matrix
            matrix.set(priority.x, priority.y, value, 1f, ModuleSource.USER_SOFT)
            val original = QrBlockInterleaver.deinterleave(CodewordExtractor.extract(matrix, 3, 3),
                3, ErrorCorrectionLevel.M)
            for (single in listOf(false, true)) {
                val blocks = original.copyDeep()
                if (single) PayloadConstraintSolver.apply(blocks, 3, trustKnownCount = true)
                val block = blocks.blocks.single()
                val erased = block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }
                val minimumBits = erased.map { block.unknownMasks[it].countOneBits() }.sorted()
                    .take(erased.size - blocks.ecSymbolsPerBlock).sum()
                assertTrue(minimumBits > PartialBitSearchLimit.DEFAULT)
                assertFalse(ConventionalRsDiagnostic.analyze(blocks).succeeded)
                try {
                    val candidates = PartialCodewordSolver(ReedSolomonErasures()).decodeCandidates(block,
                        blocks.ecSymbolsPerBlock, maxEnumeratedBits = PartialBitSearchLimit.DEFAULT)
                    assertTrue(candidates.isEmpty())
                } catch (error: ReedSolomonErasures.DecodeException) {
                    assertEquals(RsFailureReason.BOUNDED_BIT_SEARCH_LIMIT, error.reason)
                }
            }
        }
    }
}
