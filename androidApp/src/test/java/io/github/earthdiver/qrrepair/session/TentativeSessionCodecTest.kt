package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class TentativeSessionCodecTest {
    private val a = Coord(10, 12)
    private val b = Coord(11, 12)
    private fun payload(settings: PreprocessSettings, matrix: QrMatrix? = null) = RecoverySessionCodec.Payload(
        "tentative-test", 1L, UiMode.ANALYSIS, listOf(RecoveryHistoryEntry(
            kind = HistoryEventKind.USER_EDIT, label = "Tentative", snapshot = RecoverySnapshot(null, matrix, matrix?.copy(), -1, settings))), 0)

    @Test fun roundTripPreservesValueModeAndMatrixProvenance() {
        val settings = PreprocessSettings(preRecoveryEdits = mapOf(a to ModuleValue.ZERO, b to ModuleValue.ONE), preRecoverySoftEdits = setOf(a))
        val matrix = QrMatrix(21).apply {
            set(a.x, a.y, ModuleValue.ZERO, 1f, ModuleSource.USER_SOFT)
            set(b.x, b.y, ModuleValue.ONE, 1f, ModuleSource.USER)
        }
        val decoded = RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload(settings, matrix))).historyEntries.single().snapshot
        assertEquals(settings, decoded.preprocessSettings)
        assertEquals(ModuleSource.USER_SOFT, decoded.workingMatrix!![a.x, a.y].source)
        assertEquals(ModuleValue.ZERO, decoded.workingMatrix[a.x, a.y].value)
        assertEquals(ModuleSource.USER, decoded.sourceMatrix!![b.x, b.y].source)
    }

    @Test fun version15RetainsValuesAndGlobalCorrectionOption() {
        for (locked in listOf(false, true)) {
            val settings = PreprocessSettings(lockManualBits = locked, preRecoveryEdits = mapOf(a to ModuleValue.ZERO))
            val encoded = RecoverySessionCodec.encode(payload(settings))
            val decoded = RecoverySessionCodec.decode(rewrite(encoded, 15) { it.copyOf(it.size - 4) })
                .historyEntries.single().snapshot.preprocessSettings!!
            assertEquals(settings, decoded)
            assertEquals(emptySet<Coord>(), decoded.effectiveSoftEdits)
        }
    }

    @Test fun checksummedMalformedTentativeRecordsAreRejected() {
        val settings = PreprocessSettings(preRecoveryEdits = mapOf(a to ModuleValue.ZERO, b to ModuleValue.ONE), preRecoverySoftEdits = setOf(a, b))
        val bytes = RecoverySessionCodec.encode(payload(settings))
        val mutations = listOf<(ByteArray) -> ByteArray>(
            { raw -> raw.apply { putInt(this, size - 20, -1) } },
            { raw -> raw.apply { putInt(this, size - 20, 3) } },
            { raw -> raw.apply { putInt(this, size - 8, a.x); putInt(this, size - 4, a.y) } },
            { raw -> raw.apply { putInt(this, size - 8, 999) } },
            { raw -> raw.copyOf(raw.size - 1) },
        )
        for (mutate in mutations) assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(rewrite(bytes, RecoverySessionCodec.CURRENT_VERSION, mutate))
        }
    }

    @Test fun cannotConstructTentativeCoordinatesWithoutEditsOrKnownPriorityValues() {
        assertThrows(IllegalArgumentException::class.java) { PreprocessSettings(preRecoverySoftEdits = setOf(a)) }
        assertThrows(IllegalArgumentException::class.java) {
            PreprocessSettings(preRecoveryEdits = mapOf(a to ModuleValue.ONE), preRecoverySoftEdits = setOf(a), preRecoveryPrioritySearch = setOf(a))
        }
    }

    @Test fun roundTripRetainsFixedCorrectionOptionAndTentativePriority() {
        val settings = PreprocessSettings(lockManualBits = false,
            preRecoveryEdits = mapOf(a to ModuleValue.ZERO, b to ModuleValue.ERASED),
            preRecoverySoftEdits = setOf(b), preRecoveryPrioritySearch = setOf(b))
        val matrix = QrMatrix(21).apply {
            set(a.x, a.y, ModuleValue.ZERO, 1f, ModuleSource.USER_UNLOCKED)
            set(b.x, b.y, ModuleValue.ERASED, 0f, ModuleSource.USER_SOFT)
            setPrioritySearch(b.x, b.y, true)
        }
        val decoded = RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload(settings, matrix)))
            .historyEntries.single().snapshot
        assertEquals(settings, decoded.preprocessSettings)
        assertEquals(ModuleSource.USER_UNLOCKED, decoded.workingMatrix!![a.x, a.y].source)
        assertEquals(ModuleSource.USER_SOFT, decoded.workingMatrix[b.x, b.y].source)
        assertTrue(decoded.workingMatrix[b.x, b.y].prioritySearch)
        assertEquals(mapOf(a to ModuleValue.ZERO), decoded.preprocessSettings!!.fixedPreRecoveryEdits)
    }

    @Test fun version16StillRejectsTentativePriorityRecords() {
        val settings = PreprocessSettings(preRecoveryEdits = mapOf(b to ModuleValue.ERASED),
            preRecoverySoftEdits = setOf(b), preRecoveryPrioritySearch = setOf(b))
        val bytes = RecoverySessionCodec.encode(payload(settings))
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(rewrite(bytes, 16) { it })
        }
    }

    private fun putInt(raw: ByteArray, offset: Int, value: Int) {
        java.nio.ByteBuffer.wrap(raw).putInt(offset, value)
    }

    /** Recompute integrity fields so tests exercise validation inside the payload. */
    private fun rewrite(encoded: ByteArray, version: Int, transform: (ByteArray) -> ByteArray): ByteArray {
        val raw = transform(GZIPInputStream(ByteArrayInputStream(encoded, 48, encoded.size - 48)).readBytes())
        val compressed = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(raw) } }.toByteArray()
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x5152523A); out.writeInt(version); out.writeInt(raw.size); out.writeInt(compressed.size)
            out.write(MessageDigest.getInstance("SHA-256").digest(raw)); out.write(compressed)
        } }.toByteArray()
    }
}
