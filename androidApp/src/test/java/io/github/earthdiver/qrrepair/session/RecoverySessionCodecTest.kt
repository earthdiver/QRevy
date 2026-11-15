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
    @Test fun automaticTextMarksAboveFourteenSurviveSavingAndLegacySchemasStillRejectThem() {
        val marks = QrCellRegions(1).codewordCoordinates.take(32).toSet()
        val settings = PreprocessSettings(preRecoveryEdits = marks.associateWith { ModuleValue.ERASED },
            preRecoveryPrioritySearch = marks)
        val matrix = QrMatrix(21).apply { marks.forEach { setPrioritySearch(it.x, it.y, true) } }
        val history = entry().let { it.copy(snapshot = it.snapshot.copy(preprocessSettings = settings,
            sourceMatrix = matrix.copy(), workingMatrix = matrix, automaticScanAssumption = AutomaticScanAssumption.NONE)) }
        val bytes = RecoverySessionCodec.encode(RecoverySessionCodec.Payload("many-text-marks", 1L, UiMode.ANALYSIS, listOf(history), 0))
        val restored = RecoverySessionCodec.decode(bytes).historyEntries.single().snapshot
        assertEquals(settings, restored.preprocessSettings)
        assertEquals(matrix.prioritySearchCoordinates(), restored.workingMatrix!!.prioritySearchCoordinates())
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 39) })
        }
    }

    @Test fun importedTextPriorityMarksRoundTripSeparatelyFromAutomaticExecution() {
        val settings = PreprocessSettings(preRecoveryEdits = mapOf(Coord(20, 20) to ModuleValue.ERASED),
            preRecoveryPrioritySearch = setOf(Coord(20, 20)))
        val history = entry().let { it.copy(snapshot = it.snapshot.copy(preprocessSettings = settings,
            automaticScanAssumption = AutomaticScanAssumption.UTF8_SINGLE_SEGMENT)) }
        val payload = RecoverySessionCodec.Payload("text-automatic", 1L, UiMode.ANALYSIS, listOf(history), 0)
        val bytes = RecoverySessionCodec.encode(payload)
        val restored = RecoverySessionCodec.decode(bytes).historyEntries.single().snapshot
        assertEquals(settings, restored.preprocessSettings)
        val actual = QuickAutoRecovery.executionSettings(restored.preprocessSettings!!, restored.automaticScanAssumption!!)
        assertTrue(actual.preRecoveryEdits.isEmpty())
        assertTrue(actual.preRecoveryPrioritySearch.isEmpty())
        assertEquals(0, actual.knownBitErrorLimit)
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 39) })
        }
    }

    @Test fun automaticTextExceptionRejectsFixedAndPhotographicEditsOnReadAndWrite() {
        val p = Coord(20, 20)
        val invalidSettings = listOf(
            PreprocessSettings(preRecoveryEdits = mapOf(p to ModuleValue.ZERO)),
            PreprocessSettings(preRecoveryEdits = mapOf(p to ModuleValue.ONE)),
            PreprocessSettings(photographicSource = true, preRecoveryEdits = mapOf(p to ModuleValue.ERASED)),
        )
        for (settings in invalidSettings) {
            val history = entry().let { it.copy(snapshot = it.snapshot.copy(preprocessSettings = settings)) }
            val payload = RecoverySessionCodec.Payload("invalid-text-auto", 1L, UiMode.ANALYSIS, listOf(history), 0)
            val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload)).bytes
            // Give a valid detailed snapshot an automatic-condition tag and extension byte.
            raw[raw.lastIndex] = 1
            val bytes = QrrContainerCodec.encode { it.write(raw); it.writeByte(1) }
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(bytes) }
            assertThrows(IllegalArgumentException::class.java) {
                RecoverySessionCodec.encode(payload.copy(historyEntries = listOf(history.copy(
                    snapshot = history.snapshot.copy(automaticScanAssumption = AutomaticScanAssumption.NONE)))))
            }
        }
    }

    @Test fun automaticConditionsRoundTripSeparatelyFromSavedLmAndErrorLimits() {
        val lm = GridGeometry(1, FinderRole.TOP_LEFT, 20.0, 20.0, 3.0, 0.0).asProjective()
        val settings = PreprocessSettings(lmCorrectionEnabled = true, knownBitErrorLimit = 2,
            manualQuad = lm.imageQuad, lmGeometry = lm).withInitialErrorLimits()
        for (assumption in AutomaticScanAssumption.entries) for (extension in 1..2) {
            if (assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS && extension == 2) continue
            val history = entry().let { it.copy(snapshot = it.snapshot.copy(
                preprocessSettings = settings, automaticScanAssumption = assumption, automaticScanListExtension = extension)) }
            val payload = RecoverySessionCodec.Payload("automatic", 1L, UiMode.ANALYSIS, listOf(history), 0)
            val restored = RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload)).historyEntries.single().snapshot
            assertEquals(assumption, restored.automaticScanAssumption)
            assertEquals(extension, restored.automaticScanListExtension)
            assertEquals(settings, restored.preprocessSettings)
            assertEquals(11, restored.preprocessSettings!!.effectiveDataEccMismatchLimit)
            val actual = QuickAutoRecovery.executionSettings(restored.preprocessSettings, assumption,
                restored.automaticScanListExtension)
            assertEquals(if (assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS) 0 else 11,
                actual.effectiveDataEccMismatchLimit)
            assertEquals(if (assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS) 0 else extension,
                actual.listDecodingAdditionalErrors)
        }
    }

    @Test fun olderSchemasRetainTheirSupportedAssumptionsAndRejectUnsupportedTags() {
        for (version in listOf(36, 37, 38)) for (assumption in AutomaticScanAssumption.entries) {
            val history = entry().let { it.copy(snapshot = it.snapshot.copy(
                preprocessSettings = PreprocessSettings(), automaticScanAssumption = assumption)) }
            val payload = RecoverySessionCodec.Payload("legacy-automatic", 1L, UiMode.ANALYSIS, listOf(history), 0)
            val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload)).bytes
            // The single snapshot ends with the extension byte introduced in schema 39.
            val bytes = QrrContainerCodec.encode { it.write(raw, 0, raw.size - 1) }
                .also { ByteBuffer.wrap(it).putInt(4, version) }
            if (assumption.ordinal >= when (version) { 36 -> 3; 37 -> 4; else -> 5 }) {
                assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(bytes) }
            } else {
                val restored = RecoverySessionCodec.decode(bytes).historyEntries.single().snapshot
                assertEquals(assumption, restored.automaticScanAssumption)
                assertEquals(1, restored.automaticScanListExtension)
            }
        }
    }

    @Test fun invalidAutomaticListExtensionsAreRejectedOnReadAndWrite() {
        for (assumption in listOf(AutomaticScanAssumption.NONE, AutomaticScanAssumption.EXACT_KNOWN_BITS)) {
            val history = entry().let { it.copy(snapshot = it.snapshot.copy(
                preprocessSettings = PreprocessSettings(), automaticScanAssumption = assumption)) }
            val payload = RecoverySessionCodec.Payload("invalid-extension", 1L, UiMode.ANALYSIS, listOf(history), 0)
            val raw = QrrContainerCodec.decode(RecoverySessionCodec.encode(payload)).bytes
            val invalid = if (assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS) listOf(0, 2, 3, 255) else listOf(0, 3, 255)
            for (extension in invalid) {
                val altered = raw.copyOf().apply { this[lastIndex] = extension.toByte() }
                assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
                    RecoverySessionCodec.decode(QrrContainerCodec.encode { it.write(altered) })
                }
                assertThrows(IllegalArgumentException::class.java) {
                    RecoverySessionCodec.encode(payload.copy(historyEntries = listOf(history.copy(
                        snapshot = history.snapshot.copy(automaticScanListExtension = extension)))))
                }
            }
        }
        val history = entry().let { it.copy(snapshot = it.snapshot.copy(automaticScanListExtension = 2)) }
        assertThrows(IllegalArgumentException::class.java) {
            RecoverySessionCodec.encode(RecoverySessionCodec.Payload("missing-assumption", 1L, UiMode.ANALYSIS, listOf(history), 0))
        }
    }

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

    @Test fun oneTimeForcingSurvivesHistoryRoundTripWithoutChangingThePreference() {
        val settings = PreprocessSettings(trustKnownPayloadCount = false)
        val history = entry().let { it.copy(snapshot = it.snapshot.copy(
            preprocessSettings = settings, oneTimeSingleSegment = true)) }
        val original = RecoverySessionCodec.Payload("one-time", 1L, UiMode.ANALYSIS, listOf(history), 0)
        val restored = RecoverySessionCodec.decode(RecoverySessionCodec.encode(original)).historyEntries.single().snapshot
        assertTrue(restored.oneTimeSingleSegment)
        assertEquals(settings, restored.preprocessSettings)
        assertEquals(false, restored.preprocessSettings!!.trustKnownPayloadCount)
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

    @Test fun visibleFinderRoleRoundTripsWithoutCreatingCenterMeasurements() {
        val quad = ImageQuad(PointD(0.0, 0.0), PointD(80.0, 0.0), PointD(80.0, 80.0), PointD(0.0, 80.0))
        for (role in FinderRole.entries) {
            val settings = PreprocessSettings(manualQuad = quad, visibleFinderRole = role)
            val bytes = RecoverySessionCodec.encode(settingsPayload(settings))
            assertEquals(settings, RecoverySessionCodec.decode(bytes).historyEntries.single().snapshot.preprocessSettings)
            val legacy = schema28Settings(bytes)
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(legacy) }
        }
        val old = schema28Settings(RecoverySessionCodec.encode(settingsPayload(PreprocessSettings(manualQuad = quad))))
        assertEquals(null, RecoverySessionCodec.decode(old).historyEntries.single().snapshot.preprocessSettings!!.visibleFinderRole)
    }

    @Test fun knownBitSearchSettingsRoundTripAndValidateRange() {
        for (limit in 0..2) {
            val settings=PreprocessSettings(knownBitErrorLimit=limit)
            assertEquals(settings, RecoverySessionCodec.decode(RecoverySessionCodec.encode(settingsPayload(settings)))
                .historyEntries.single().snapshot.preprocessSettings)
        }
        assertThrows(IllegalArgumentException::class.java) { PreprocessSettings(knownBitErrorLimit=3) }
    }

    @Test fun olderSettingsDefaultOffAndMalformedNewLimitIsRejected() {
        fun raw(limit:Int):ByteArray {
            val encoded=RecoverySessionCodec.encode(settingsPayload(PreprocessSettings(knownBitErrorLimit=limit)))
            val current = GZIPInputStream(ByteArrayInputStream(encoded,48,encoded.size-48)).use { it.readBytes() }.dropLast(1).toByteArray()
            // Schema 28 ends after the known-bit search limit.
            return current.copyOfRange(0, current.size - 8) + current.copyOfRange(current.size - 3, current.size)
        }
        fun container(raw:ByteArray,version:Int):ByteArray {
            val compressed=java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
            return java.io.ByteArrayOutputStream().also { out -> java.io.DataOutputStream(out).use {
                it.writeInt(0x5152523A);it.writeInt(version);it.writeInt(raw.size);it.writeInt(compressed.size)
                it.write(java.security.MessageDigest.getInstance("SHA-256").digest(raw));it.write(compressed)
            } }.toByteArray()
        }
        val zero=raw(0);val two=raw(2)
        val changed=zero.indices.filter { zero[it]!=two[it] }.single()
        val old=zero.copyOfRange(0,changed-3)+zero.copyOfRange(changed+1,zero.size)
        assertEquals(0,RecoverySessionCodec.decode(container(old,27)).historyEntries.single().snapshot.preprocessSettings!!.knownBitErrorLimit)
        two[changed]=3
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(container(two,28)) }
    }

    private fun schema28Settings(encoded: ByteArray): ByteArray {
        val raw = QrrContainerCodec.decode(encoded).bytes.dropLast(1).toByteArray()
        val legacy = raw.copyOfRange(0, raw.size - 8) + raw.copyOfRange(raw.size - 3, raw.size)
        return QrrContainerCodec.encode { it.write(legacy) }.also { ByteBuffer.wrap(it).putInt(4, 28) }
    }

    private fun settingsPayload(settings: PreprocessSettings) = RecoverySessionCodec.Payload(
        "threshold-settings", 2L, UiMode.ANALYSIS, listOf(RecoveryHistoryEntry(id = "settings-entry", timestampMs = 0L, kind = HistoryEventKind.SETTINGS,
            label = "thresholds", snapshot = RecoverySnapshot(null, null, null, -1, settings))), 0)

    @Test fun unsupportedSchemaVersionsAreRejected() {
        val original = RecoverySessionCodec.encode(settingsPayload(PreprocessSettings()))
        assertEquals(RecoverySessionCodec.CURRENT_VERSION, ByteBuffer.wrap(original).getInt(4))
        for (version in (0 until 23) + listOf(-1, RecoverySessionCodec.CURRENT_VERSION + 1, Int.MAX_VALUE)) {
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
