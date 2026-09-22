package io.github.earthdiver.qrrepair.session

import com.google.zxing.qrcode.decoder.Version
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class RecoverySessionSizeTest {
    private fun largeHistory(cursor: Int = 39): RecoverySessionCodec.Payload {
        val matrix = QrMatrix(177).apply {
            set(176, 176, ModuleValue.ONE, .375f, ModuleSource.USER_SOFT)
        }
        val snapshot = RecoverySnapshot(GridGeometry(40, FinderRole.TOP_LEFT, 20.0, 20.0, 3.0, 0.0),
            matrix, matrix, 0)
        return RecoverySessionCodec.Payload("large-history", 1L, UiMode.SIMPLE,
            List(40) { RecoveryHistoryEntry(id = "entry-$it", timestampMs = 1L,
                kind = HistoryEventKind.CANDIDATE, label = "Candidate $it", snapshot = snapshot) }, cursor)
    }

    @Test
    fun version40HistorySavesWithinTheExistingRawLimitAndKeepsActiveAndLatest() {
        val original = largeHistory(cursor = 0)
        val encoded = RecoverySessionCodec.encode(original)
        assertTrue(ByteBuffer.wrap(encoded).getInt(8) <= RecoverySessionCodec.MAX_SESSION_PAYLOAD_BYTES)
        val decoded = RecoverySessionCodec.decode(encoded)

        assertEquals(38, decoded.historyEntries.size)
        assertEquals("entry-0", decoded.historyEntries[decoded.historyCursor].id)
        assertEquals("entry-39", decoded.historyEntries.last().id)
        assertEquals(40, original.historyEntries.size)
        val restored = decoded.historyEntries.last().snapshot.workingMatrix!![176, 176]
        assertEquals(ModuleValue.ONE, restored.value)
        assertEquals(ModuleSource.USER_SOFT, restored.source)
        assertEquals(.375f, restored.confidence, 0f)
    }

    @Test
    fun liveHistoryHonorsItsMemoryBudgetBeforeCapturingASave() {
        val original = largeHistory(cursor = 0)
        val history = RecoverySessionHistory().apply { replace(original.historyEntries, original.historyCursor) }
        val removed = RecoverySessionCodec.trimHistoryToPayloadLimit(history, original.id)

        assertTrue(removed > 0)
        assertTrue(history.entries.sumOf(RecoverySessionCodec::retainedHistoryEntryBytes) <= 32L * 1024 * 1024)
        assertEquals("entry-0", history.current!!.id)
        assertEquals("entry-39", history.entries.last().id)
        assertEquals(removed, 40 - history.entries.size)
    }

    @Test
    fun invalidOldSnapshotsAreRejectedBeforeTheyCouldBeTrimmed() {
        val original = largeHistory()
        val invalid = QrMatrix(177).apply { get(176, 176).confidence = Float.NaN }
        val first = original.historyEntries.first().let { it.copy(snapshot = it.snapshot.copy(sourceMatrix = invalid)) }
        assertThrows(IllegalArgumentException::class.java) {
            RecoverySessionCodec.encode(original.copy(historyEntries = listOf(first) + original.historyEntries.drop(1)))
        }
    }

    private fun selectedPayload(): RecoverySessionCodec.Payload {
        val words = ByteArray(Version.getVersionForNumber(1).totalCodewords) { (it * 11).toByte() }
        val matrix = QrMatrix(21)
        val selection = RecoverySelectionIdentity(1, 1, 4, words)
        val snapshot = RecoverySnapshot(GridGeometry(1, FinderRole.TOP_LEFT, 20.0, 20.0, 3.0, 0.0),
            matrix, matrix.copy(), 7, PreprocessSettings(polarity = PolarityMode.AUTO), selection, true)
        return RecoverySessionCodec.Payload("selected-history", 1L, UiMode.SIMPLE,
            listOf(RecoveryHistoryEntry(id = "selected-entry", timestampMs = 1L, kind = HistoryEventKind.CANDIDATE,
                label = "Selected candidate", snapshot = snapshot)), 0)
    }

    @Test
    fun selectionAndActualPolarityRoundTripWithoutChangingAutomaticPreference() {
        val original = selectedPayload()
        val restored = RecoverySessionCodec.decode(RecoverySessionCodec.encode(original))
            .historyEntries.single().snapshot
        assertEquals(original.historyEntries.single().snapshot.selectedQr, restored.selectedQr)
        assertEquals(true, restored.samplingInverted)
        assertEquals(PolarityMode.AUTO, restored.preprocessSettings!!.polarity)
        assertEquals(7, restored.recoveryIndex)
    }

    @Test
    fun exactSizeAccountingIncludesSelectionAndModifiedUtfText() {
        val original = selectedPayload().let { payload ->
            payload.copy(historyEntries = payload.historyEntries.map { it.copy(label = "\u0000 \ud83d\udd0d \u65e5\u672c\u8a9e") })
        }
        val encoded = RecoverySessionCodec.encode(original)
        val header = RecoverySessionCodec.encode(original.copy(historyEntries = emptyList(), historyCursor = -1))
        val expected = ByteBuffer.wrap(header).getInt(8).toLong() +
            original.historyEntries.sumOf(RecoverySessionCodec::encodedHistoryEntryBytes)
        assertEquals(expected, ByteBuffer.wrap(encoded).getInt(8).toLong())
    }

    @Test
    fun schema18StillLoadsWithoutSelectionOrSamplingMetadata() {
        val original = selectedPayload().let { payload -> payload.copy(historyEntries = payload.historyEntries.map {
            it.copy(snapshot = it.snapshot.copy(selectedQr = null, samplingInverted = null))
        }) }
        val encoded = RecoverySessionCodec.encode(original)
        val legacy = rewrite(encoded, 18) { it.copyOf(it.size - 19) }
        val restored = RecoverySessionCodec.decode(legacy).historyEntries.single().snapshot
        assertNull(restored.selectedQr)
        assertNull(restored.samplingInverted)
        assertEquals(original.historyEntries.single().snapshot.preprocessSettings, restored.preprocessSettings)
        assertEquals(7, restored.recoveryIndex)
    }

    @Test
    fun checksummedInvalidSelectionFieldsAreRejectedBeforeArrayAllocation() {
        val encoded = RecoverySessionCodec.encode(selectedPayload())
        val invalidFields = listOf(1 to 0, 5 to 4, 9 to 8, 13 to Int.MAX_VALUE, 13 to 25)
        for ((field, invalidValue) in invalidFields) {
            val altered = rewrite(encoded, RecoverySessionCodec.CURRENT_VERSION) { raw ->
                val selectedOffset = raw.size - (1 + 16 + 26 + 2)
                ByteBuffer.wrap(raw).putInt(selectedOffset + field, invalidValue)
                raw
            }
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(altered) }
        }
    }

    @Test
    fun selectionVersionMustMatchTheSavedInput() {
        val original = selectedPayload()
        val selected = RecoverySelectionIdentity(2, 1, 4, ByteArray(Version.getVersionForNumber(2).totalCodewords))
        assertThrows(IllegalArgumentException::class.java) {
            RecoverySessionCodec.encode(original.copy(historyEntries = original.historyEntries.map {
                it.copy(snapshot = it.snapshot.copy(selectedQr = selected))
            }))
        }
    }

    private fun rewrite(encoded: ByteArray, version: Int, transform: (ByteArray) -> ByteArray): ByteArray {
        val raw = transform(GZIPInputStream(ByteArrayInputStream(encoded, 48, encoded.size - 48)).readBytes())
        val compressed = ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(raw) } }.toByteArray()
        return ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x5152523A); out.writeInt(version); out.writeInt(raw.size); out.writeInt(compressed.size)
            out.write(MessageDigest.getInstance("SHA-256").digest(raw)); out.write(compressed)
        } }.toByteArray()
    }
}
