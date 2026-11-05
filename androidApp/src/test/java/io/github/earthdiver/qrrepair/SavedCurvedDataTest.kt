package io.github.earthdiver.qrrepair

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test

class SavedCurvedDataTest {
    private val image = LumaImage(400, 400, IntArray(400 * 400) { if (it % 400 >= 208) 0 else 255 })
    private val quad = ImageQuad(PointD(40.0, 40.0), PointD(360.0, 40.0),
        PointD(360.0, 360.0), PointD(40.0, 360.0))
    private val base = GridGeometry(4, FinderRole.TOP_LEFT, 70.0, 70.0, 10.0, 0.0,
        quad, GeometryModel.MANUAL_PROJECTIVE).reanchored()
    private val curved = base.copy(model = GeometryModel.THIN_PLATE_SPLINE, warp = ThinPlateWarp(listOf(
        WarpLandmark(PointD(0.0, 0.0), PointD(0.0, 0.0)),
        WarpLandmark(PointD(1.0, 0.0), PointD(0.0, 0.0)),
        WarpLandmark(PointD(0.0, 1.0), PointD(0.0, 0.0)),
        WarpLandmark(PointD(1.0, 1.0), PointD(0.0, 0.0)),
        WarpLandmark(PointD(.5, .5), PointD(15.0, 0.0)),
    )))
    private val settings = PreprocessSettings(version = 4, manualQuad = quad, previewGeometry = curved,
        distortionCorrection = true, polarity = PolarityMode.NORMAL, blackMax = 80, whiteMin = 175,
        formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, 0),
        dataEccMismatchLimit = 0, listDecodingAdditionalErrors = 0)

    @Test fun savedCurvedDataMatchesRepairInputIncludingMirroringPolarityAndEdits() {
        assertTrue(curved.hasUsableWarp())
        assertEquals(ModuleValue.ZERO, sampleSavedPreRecoveryData(image,
            settings.copy(distortionCorrection = false))!!.matrix[16, 16].value)
        assertEquals(ModuleValue.ONE, sampleSavedPreRecoveryData(image, settings)!!.matrix[16, 16].value)
        val fixed = Coord(12, 12)
        val soft = Coord(13, 12)
        val erased = Coord(14, 12)
        for (mirrored in listOf(false, true)) for (inverted in listOf(false, true)) {
            val geometry = if (mirrored) curved.copy(imageQuad = quad.transposed()).reanchored() else curved
            val configured = settings.copy(manualQuad = geometry.imageQuad, previewGeometry = geometry,
                mirroredInput = mirrored, polarity = if (inverted) PolarityMode.INVERTED else PolarityMode.NORMAL,
                preRecoveryEdits = mapOf(fixed to ModuleValue.ONE, soft to ModuleValue.ZERO, erased to ModuleValue.ERASED),
                preRecoverySoftEdits = setOf(soft), preRecoveryPrioritySearch = setOf(erased))
            val data = requireNotNull(sampleSavedPreRecoveryData(image, configured))
            val repair = AutoRecoveryEngine().run(image, profile = configured.variants().first(),
                listDecodingBudget = ListDecodingBudget(0))
            assertEquals(listOf(geometry), repair.testedHypotheses.map { it.geometry })
            val expected = repair.attempts.single().matrix
            assertMatrixEquals(expected, data.matrix)
            assertEquals(ModuleSource.USER, data.matrix[fixed.x, fixed.y].source)
            assertEquals(ModuleSource.USER_SOFT, data.matrix[soft.x, soft.y].source)
            assertTrue(data.matrix[erased.x, erased.y].prioritySearch)
        }
    }

    @Test fun disabledUnassignedOrWrongVersionCorrectionUsesTheOrdinaryGrid() {
        val expected = requireNotNull(sampleSavedPreRecoveryData(image, settings.copy(previewGeometry = null))).matrix
        for (configured in listOf(settings.copy(distortionCorrection = false),
            settings.copy(logicalGridAssigned = false), settings.copy(previewGeometry = curved.copy(version = 5)))) {
            assertMatrixEquals(expected, requireNotNull(sampleSavedPreRecoveryData(image, configured)).matrix)
        }
    }

    @Test fun cancelledSavedDataStopsDuringCurvedObservation() {
        var checks = 0
        assertThrows(RecoveryCancelledException::class.java) {
            sampleSavedPreRecoveryData(image, settings) { ++checks > 5 }
        }
        assertEquals(6, checks)
        assertNotNull(sampleSavedPreRecoveryData(image, settings))
    }

    private fun assertMatrixEquals(expected: QrMatrix, actual: QrMatrix) {
        assertEquals(expected.dimension, actual.dimension)
        for (y in 0 until expected.dimension) for (x in 0 until expected.dimension) {
            assertEquals("Cell ($x, $y)", expected[x, y], actual[x, y])
        }
    }
}
