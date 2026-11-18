package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PerspectiveSessionCodecTest {
    private val quad = ImageQuad(PointD(20.0, 30.0), PointD(220.0, 20.0), PointD(210.0, 220.0), PointD(30.0, 210.0))
    private fun payload(settings: PreprocessSettings) = RecoverySessionCodec.Payload("perspective-test", 1L, UiMode.ANALYSIS,
        listOf(RecoveryHistoryEntry(id = "perspective-entry", timestampMs = 1L, kind = HistoryEventKind.SETTINGS, label = "Perspective",
            snapshot = RecoverySnapshot(null, null, null, -1, settings))), 0)

    @Test fun sourceAndLegacyQuadsRoundTripWithIndependentRotationAndFixedEdits() {
        for (reference in listOf(null, 30f, -15f)) {
            val settings = PreprocessSettings(rotationDegrees = 30f, perspectiveCorrectionQuad = quad,
                legacyImageRotationDegrees = reference, lmCorrectionEnabled = true, distortionCorrection = true,
                preRecoveryEdits = mapOf(Coord(10, 10) to ModuleValue.ONE), lockManualBits = true)
            val original = payload(settings)
            assertEquals(original, RecoverySessionCodec.decode(RecoverySessionCodec.encode(original)))
        }
    }

    @Test fun schema41RetainsTheRotatedCoordinateSystemWhenSavedAgain() {
        val settings = PreprocessSettings(rotationDegrees = 30f, perspectiveCorrectionQuad = quad,
            legacyImageRotationDegrees = 30f)
        val bytes = RecoverySessionCodec.encode(payload(settings)).also { ByteBuffer.wrap(it).putInt(4, 41) }
        val decoded = RecoverySessionCodec.decode(bytes)
        assertEquals(settings, decoded.historyEntries.single().snapshot.preprocessSettings)
        assertEquals(decoded, RecoverySessionCodec.decode(RecoverySessionCodec.encode(decoded)))
    }

    @Test fun schema41RotationWithoutPerspectiveKeepsItsRasterCompatibilityAfterResaving() {
        val settings = PreprocessSettings(rotationDegrees = 17.3f)
        val bytes = RecoverySessionCodec.encode(payload(settings)).also { ByteBuffer.wrap(it).putInt(4, 41) }
        val decoded = RecoverySessionCodec.decode(bytes)
        assertEquals(settings.copy(legacyImageRotationDegrees = 17.3f), decoded.historyEntries.single().snapshot.preprocessSettings)
        assertEquals(decoded, RecoverySessionCodec.decode(RecoverySessionCodec.encode(decoded)))
    }

    @Test fun coordinateSystemTagsAndReferenceAnglesAreStrictlyValidated() {
        val settings = PreprocessSettings(rotationDegrees = 30f, perspectiveCorrectionQuad = quad)
        val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload(settings))).bytes
        val legacy = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload(settings.copy(legacyImageRotationDegrees = 30f)))).bytes
        val tag = raw.indices.single { raw[it] != legacy[it] }
        for (invalid in listOf(-1, 5, 127)) {
            val changed = raw.copyOf().also { it[tag] = invalid.toByte() }
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
                RecoverySessionCodec.decode(QrrContainerCodec.encode { it.write(changed) })
            }
        }
        val referenceRaw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload(
            settings.copy(legacyImageRotationDegrees = -15f)))).bytes
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, 361f)) {
            val changed = referenceRaw.copyOf().also { ByteBuffer.wrap(it).putFloat(tag + 1 + 8 * 8, invalid) }
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
                RecoverySessionCodec.decode(QrrContainerCodec.encode { it.write(changed) })
            }
        }
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload(settings)).also { ByteBuffer.wrap(it).putInt(4, 41) })
        }
    }
}
