package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

class RecoverySessionCodecTest {
    private fun entry(label: String = "state"): RecoveryHistoryEntry {
        val matrix = QrMatrix(21)
        matrix.set(10, 10, ModuleValue.ONE, 0.75f, ModuleSource.USER)
        val snapshot = RecoverySnapshot(
            GridGeometry(1, FinderRole.TOP_LEFT, 20.0, 20.0, 3.0, 0.0),
            matrix.copy(),
            matrix,
            0,
        )
        return RecoveryHistoryEntry(kind = HistoryEventKind.USER_EDIT, label = label, snapshot = snapshot)
    }

    @Test fun roundTripPreservesHistory() {
        val original = RecoverySessionCodec.Payload(
            id = "123e4567-e89b-12d3-a456-426614174000",
            updatedAtMs = 123456789L,
            uiMode = UiMode.ANALYSIS,
            historyEntries = listOf(entry("one"), entry("two")),
            historyCursor = 1,
        )
        val decoded = RecoverySessionCodec.decode(RecoverySessionCodec.encode(original))
        assertEquals(original.id, decoded.id)
        assertEquals(UiMode.ANALYSIS, decoded.uiMode)
        assertEquals(2, decoded.historyEntries.size)
        assertEquals(1, decoded.historyCursor)
        assertEquals(ModuleValue.ONE, decoded.historyEntries[1].snapshot.workingMatrix?.get(10, 10)?.value)
    }

    @Test fun roundTripPreservesUnknownColorClusters() {
        val settings = PreprocessSettings(
            unknownColorClusters = listOf(UnknownColorCluster(5000, 6200, 4100, 900)),
            useUnknownColorClusters = false,
            manualQuad = ImageQuad(PointD(2.0, 3.0), PointD(90.0, 4.0), PointD(89.0, 91.0), PointD(1.0, 90.0)),
            logicalGridAssigned = false,
        )
        val history = RecoveryHistoryEntry(
            kind = HistoryEventKind.IMPORT,
            label = "markers",
            snapshot = RecoverySnapshot(null, null, null, -1, settings),
        )
        val bytes = RecoverySessionCodec.encode(RecoverySessionCodec.Payload("marker-colors", 2L, UiMode.ANALYSIS, listOf(history), 0))
        assertEquals(settings, RecoverySessionCodec.decode(bytes).historyEntries.single().snapshot.preprocessSettings)
    }

    @Test fun roundTripPreservesReproduciblePreprocessSettings() {
        val settings = PreprocessSettings(
            rotationDegrees = -1.5f,
            polarity = PolarityMode.INVERTED,
            automaticThresholds = false,
            blackMax = 72,
            whiteMin = 181,
            quietZoneCorrection = false,
            version = 4,
            lockCorners = true,
            lockVersion = true,
            searchQuadNeighborhood = true,
            trustKnownPayloadCount = true,
            formatConstraint = QrFormatConstraint(dataMask = 5),
            formatMismatchLimit = 2,
            dataEccMismatchLimit = 7,
            lockManualBits = false,
            perspectiveCorrectionQuad = ImageQuad(PointD(5.0, 6.0), PointD(95.0, 4.0), PointD(92.0, 96.0), PointD(3.0, 93.0)),
            manualQuad = ImageQuad(PointD(1.0, 2.0), PointD(90.0, 3.0), PointD(88.0, 92.0), PointD(2.0, 91.0)),
            manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(12.0, 13.0)),
            detectedFinders = listOf(FinderCandidate(12.0, 13.0, 4.5, .1, .8, 7, polarity = FinderPolarity.INVERTED)),
            confirmedFinderPolarity = FinderPolarity.INVERTED,
            preRecoveryEdits = mapOf(Coord(8, 9) to ModuleValue.ERASED),
            preRecoveryPrioritySearch = setOf(Coord(8, 9)),
        )
        val history = RecoveryHistoryEntry(
            kind = HistoryEventKind.SETTINGS,
            label = "settings",
            snapshot = RecoverySnapshot(null, null, null, -1, settings),
        )
        val payload = RecoverySessionCodec.Payload("settings-roundtrip", 2L, UiMode.SIMPLE, listOf(history), 0)

        val decoded = RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload))

        assertEquals(settings, decoded.historyEntries.single().snapshot.preprocessSettings)
    }

    @Test fun roundTripPreservesAutomaticModeAndIndependentFallback() {
        val settings = PreprocessSettings(automaticThresholds = true, blackMax = 117, whiteMin = 118,
            provisionalBlackMax = 96, provisionalWhiteMin = 160)
        val payload = settingsPayload(settings)
        assertEquals(settings, RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload))
            .historyEntries.single().snapshot.preprocessSettings)
    }

    private fun settingsPayload(settings: PreprocessSettings) = RecoverySessionCodec.Payload(
        "threshold-settings", 2L, UiMode.ANALYSIS, listOf(RecoveryHistoryEntry(kind = HistoryEventKind.SETTINGS,
            label = "thresholds", snapshot = RecoverySnapshot(null, null, null, -1, settings))), 0)

    @Test fun onlyCurrentSchemaVersionIsAccepted() {
        val original = RecoverySessionCodec.encode(settingsPayload(PreprocessSettings()))
        assertEquals(RecoverySessionCodec.CURRENT_VERSION, ByteBuffer.wrap(original).getInt(4))
        for (version in (0 until RecoverySessionCodec.CURRENT_VERSION) + listOf(-1, RecoverySessionCodec.CURRENT_VERSION + 1, Int.MAX_VALUE)) {
            val altered = original.copyOf().also { ByteBuffer.wrap(it).putInt(4, version) }
            val error = assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
                RecoverySessionCodec.decode(altered)
            }
            assertTrue(error.message.orEmpty().contains("version $version"))
        }
    }

    @Test fun otherContainerSignaturesAreRejected() {
        val original = RecoverySessionCodec.encode(settingsPayload(PreprocessSettings()))
        for (magic in listOf(0, 0x51525235, 0x51525239, Int.MAX_VALUE)) {
            val altered = original.copyOf().also { ByteBuffer.wrap(it).putInt(0, magic) }
            val error = assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
                RecoverySessionCodec.decode(altered)
            }
            assertEquals("invalid QRR magic", error.message)
        }
    }

    @Test fun thresholdPairFollowsPolarityWithoutAReservedField() {
        val settings = PreprocessSettings(rotationDegrees = 1.5f, polarity = PolarityMode.INVERTED,
            blackMax = 72, whiteMin = 181)
        val encoded = RecoverySessionCodec.encode(settingsPayload(settings))
        DataInputStream(GZIPInputStream(ByteArrayInputStream(encoded, 48, encoded.size - 48))).use { input ->
            input.readUTF(); input.readLong(); repeat(3) { input.readInt() }
            input.readUTF(); input.readLong(); input.readInt(); input.readUTF(); input.readUTF()
            repeat(3) { assertEquals(false, input.readBoolean()) }
            input.readInt(); assertEquals(true, input.readBoolean())
            assertEquals(settings.rotationDegrees, input.readFloat(), 0f)
            assertEquals(settings.polarity.ordinal, input.readInt())
            assertEquals(settings.blackMax, input.readInt())
            assertEquals(settings.whiteMin, input.readInt())
        }
    }

    @Test fun tamperingIsRejectedByChecksum() {
        val payload = RecoverySessionCodec.Payload("session-1", 1L, UiMode.SIMPLE, listOf(entry()), 0)
        val bytes = RecoverySessionCodec.encode(payload)
        bytes[bytes.lastIndex - 7] = (bytes[bytes.lastIndex - 7].toInt() xor 0x20).toByte()
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(bytes) }
    }

    @Test fun truncationIsRejected() {
        val payload = RecoverySessionCodec.Payload("session-2", 1L, UiMode.SIMPLE, listOf(entry()), 0)
        val bytes = RecoverySessionCodec.encode(payload)
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(bytes.copyOf(bytes.size / 2))
        }
    }

    @Test fun malformedByteFuzzNeverEscapesAsUnexpectedException() {
        val random = java.util.Random(0x51525236L)
        repeat(400) {
            val bytes = ByteArray(random.nextInt(4096))
            random.nextBytes(bytes)
            try {
                RecoverySessionCodec.decode(bytes)
            } catch (_: RecoverySessionCodec.CorruptSessionException) {
                // Expected: arbitrary bytes must be rejected in a bounded, typed way.
            }
        }
    }

    @Test fun checksumFieldMutationFuzzIsAlwaysRejected() {
        val encoded = RecoverySessionCodec.encode(
            RecoverySessionCodec.Payload("mutation-fuzz", 1L, UiMode.SIMPLE, listOf(entry()), 0),
        )
        val random = java.util.Random(0x06000001L)
        repeat(128) {
            val bytes = encoded.copyOf()
            // Header bytes 16..47 are SHA-256(raw payload). Mutating any hash bit must be rejected.
            val index = 16 + random.nextInt(32)
            bytes[index] = (bytes[index].toInt() xor (1 shl random.nextInt(8))).toByte()
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(bytes) }
        }
    }
    @Test fun qrDimensionRejectsValuesOutsideModel2Versions() {
        assertTrue(runCatching { QrMatrix(177) }.isSuccess)
        assertTrue(runCatching { QrMatrix(181) }.isFailure)
        assertTrue(runCatching { QrMatrix(23) }.isFailure)
    }

}
