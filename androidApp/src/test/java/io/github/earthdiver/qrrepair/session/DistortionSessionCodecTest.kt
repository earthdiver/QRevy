package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class DistortionSessionCodecTest {
    private fun geometry(): GridGeometry {
        val points = listOf(PointD(0.0, 0.0), PointD(1.0, 0.0), PointD(1.0, 1.0), PointD(0.0, 1.0), PointD(.5, .5))
        return GridGeometry(4, FinderRole.TOP_LEFT, 40.0, 40.0, 8.0, 0.0,
            ImageQuad(PointD(16.0, 16.0), PointD(272.0, 16.0), PointD(272.0, 272.0), PointD(16.0, 272.0)),
            GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp(points.map {
                WarpLandmark(it, if (it.x == .5) PointD(6.0, -3.0) else PointD(0.0, 0.0))
            })).reanchored()
    }
    private fun payload(g: GridGeometry? = geometry()) = RecoverySessionCodec.Payload("warp-test", 1L, UiMode.ANALYSIS,
        listOf(RecoveryHistoryEntry(kind = HistoryEventKind.SETTINGS, label = "Curvature",
            snapshot = RecoverySnapshot(g, null, null, -1, PreprocessSettings(version = 4,
                distortionCorrection = true, preRecoveryEdits = mapOf(Coord(12, 12) to ModuleValue.ONE), lockManualBits = true)))), 0)

    @Test fun sessionRoundTripPreservesTheMappingSettingAndFixedEdits() {
        val original = payload()
        val restored = RecoverySessionCodec.decode(RecoverySessionCodec.encode(original))
        assertEquals(original, restored)
        val snapshot = restored.historyEntries.single().snapshot
        assertTrue(snapshot.preprocessSettings!!.distortionCorrection)
        assertEquals(ModuleValue.ONE, snapshot.preprocessSettings!!.fixedPreRecoveryEdits[Coord(12, 12)])
        for (y in 0..32 step 4) for (x in 0..32 step 4) {
            val expected = geometry().moduleToImage(x.toDouble(), y.toDouble())
            assertEquals(expected, snapshot.geometry!!.moduleToImage(x.toDouble(), y.toDouble()))
        }
    }

    @Test fun legacySchemaDefaultsCorrectionOffWithoutDiscardingFixedEdits() {
        val original = payload(null)
        val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(original)).bytes
        val legacy = raw.copyOfRange(0, raw.size - 6) + raw.copyOfRange(raw.size - 3, raw.size)
        val bytes = QrrContainerCodec.encode { it.write(legacy) }.also { ByteBuffer.wrap(it).putInt(4, 29) }
        val restored = RecoverySessionCodec.decode(bytes).historyEntries.single().snapshot.preprocessSettings!!
        assertFalse(restored.distortionCorrection)
        assertEquals(original.historyEntries.single().snapshot.preprocessSettings!!.copy(distortionCorrection = false), restored)
    }

    @Test fun displayedSamplingGeometryRoundTripsAndLegacySettingsHaveNoSelection() {
        val original = payload().let { p ->
            val entry = p.historyEntries.single()
            val settings = entry.snapshot.preprocessSettings!!.copy(manualQuad = geometry().imageQuad, previewGeometry = geometry())
            p.copy(historyEntries = listOf(entry.copy(snapshot = entry.snapshot.copy(preprocessSettings = settings))))
        }
        assertEquals(original, RecoverySessionCodec.decode(RecoverySessionCodec.encode(original)))
        val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload(null))).bytes
        val legacy = raw.copyOfRange(0, raw.size - 5) + raw.copyOfRange(raw.size - 3, raw.size)
        val bytes = QrrContainerCodec.encode { it.write(legacy) }.also { ByteBuffer.wrap(it).putInt(4, 30) }
        val restored = RecoverySessionCodec.decode(bytes).historyEntries.single().snapshot.preprocessSettings!!
        assertTrue(restored.distortionCorrection)
        assertNull(restored.previewGeometry)
    }

    @Test fun previewGeometryMustMatchTheSelectedVersionAndLogicalGrid() {
        for (settings in listOf(
            PreprocessSettings(version = 1, distortionCorrection = true, manualQuad = geometry().imageQuad, previewGeometry = geometry()),
            PreprocessSettings(version = 4, distortionCorrection = true, previewGeometry = geometry()),
            PreprocessSettings(version = 4, manualQuad = geometry().imageQuad, previewGeometry = geometry()),
        )) {
            val entry = payload(null).historyEntries.single()
            val invalid = payload(null).copy(historyEntries = listOf(entry.copy(snapshot = entry.snapshot.copy(preprocessSettings = settings))))
            assertThrows(IllegalArgumentException::class.java) { RecoverySessionCodec.encode(invalid) }
        }
        val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload(null))).bytes
        raw[raw.size - 5] = 2
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(QrrContainerCodec.encode { it.write(raw) })
        }
    }

    private fun warpOffset(raw: ByteArray): Int {
        val bytes = ByteArrayInputStream(raw); val input = DataInputStream(bytes)
        input.readUTF(); input.readLong(); repeat(3) { input.readInt() }
        input.readUTF(); input.readLong(); input.readInt(); input.readUTF(); input.readUTF()
        assertTrue(input.readBoolean())
        input.readInt(); input.readInt(); repeat(4) { input.readDouble() }; input.readInt()
        assertTrue(input.readBoolean()); repeat(8) { input.readDouble() }
        return raw.size - bytes.available()
    }

    @Test fun checksummedInvalidWarpCountsCoordinatesAndFoldsAreRejected() {
        val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload())).bytes
        val offset = warpOffset(raw)
        val mutations: List<(ByteArray) -> Unit> = listOf(
            { ByteBuffer.wrap(it).putInt(offset, 65) },
            { ByteBuffer.wrap(it).putInt(offset, -1) },
            { ByteBuffer.wrap(it).putDouble(offset + 4, Double.NaN) },
            { ByteBuffer.wrap(it).putDouble(offset + 4 + 32, 0.0) },
            { ByteBuffer.wrap(it).putDouble(offset + 4 + 4 * 40 + 16, 900.0) },
        )
        for (mutate in mutations) {
            val altered = raw.copyOf().also(mutate)
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
                RecoverySessionCodec.decode(QrrContainerCodec.encode { it.write(altered) })
            }
        }
    }

    @Test fun legacyGeometryIsReadWithoutExpectingWarpLandmarks() {
        val plain = geometry().copy(warp = null, model = GeometryModel.PROJECTIVE)
        val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload(plain))).bytes
        val offset = warpOffset(raw)
        val noWarp = raw.copyOfRange(0, offset) + raw.copyOfRange(offset + 4, raw.size)
        val legacy = noWarp.copyOfRange(0, noWarp.size - 6) + noWarp.copyOfRange(noWarp.size - 3, noWarp.size)
        val bytes = QrrContainerCodec.encode { it.write(legacy) }.also { ByteBuffer.wrap(it).putInt(4, 29) }
        val restored = RecoverySessionCodec.decode(bytes).historyEntries.single().snapshot
        assertEquals(plain, restored.geometry)
        assertFalse(restored.preprocessSettings!!.distortionCorrection)
    }

    @Test fun invalidCorrectionFlagAndFoldedMappingAreRejected() {
        val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload(null))).bytes
        raw[raw.size - 6] = 2
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(QrrContainerCodec.encode { it.write(raw) })
        }
        val g = geometry()
        val folded = g.copy(warp = ThinPlateWarp(g.warp!!.landmarks.map {
            if (it.position.x == .5) it.copy(displacement = PointD(900.0, 0.0)) else it
        }))
        assertThrows(IllegalArgumentException::class.java) { RecoverySessionCodec.encode(payload(folded)) }
    }
}
