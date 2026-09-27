package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ImageAdjustmentSessionCodecTest {
    @Test fun priorityLimitAndOverLimitMarksRoundTripWithoutDeletion() {
        val matrix = QrMatrix(21)
        val coords = CodewordExtractor.extract(matrix, 1, 0).codewords.take(14).map { it.coordinates.first() }.toSet()
        for (limit in listOf(4, 8, 12, 13, 14)) {
            val settings = PreprocessSettings(prioritySearchLimit = limit,
                preRecoveryEdits = coords.associateWith { ModuleValue.ERASED }, preRecoveryPrioritySearch = coords)
            assertEquals(settings, RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload(settings)))
                .historyEntries.single().snapshot.preprocessSettings)
        }
    }

    @Test fun fourteenMatrixMarksRoundTrip() {
        val matrix = QrMatrix(21)
        CodewordExtractor.extract(matrix, 1, 0).codewords.take(14).forEach {
            val c = it.coordinates.first()
            matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.USER_SOFT)
            matrix.setPrioritySearch(c.x, c.y, true)
        }
        val original = payload(PreprocessSettings()).let { it.copy(historyEntries = listOf(
            it.historyEntries.single().copy(snapshot = RecoverySnapshot(null, matrix, matrix.copy(), -1, null)))) }
        val encoded = RecoverySessionCodec.encode(original)
        assertEquals(14, RecoverySessionCodec.decode(encoded).historyEntries.single().snapshot.workingMatrix!!.prioritySearchCoordinates().size)
    }

    @Test fun invalidPriorityLimitsAreRejected() {
        val encoded = RecoverySessionCodec.encode(payload(PreprocessSettings(prioritySearchLimit = 14)))
        for (limit in listOf(-1, 3, 15, Int.MAX_VALUE)) {
            val changed = rewrite(encoded) { raw -> ByteBuffer.wrap(raw).putInt(raw.size - 6, limit); raw }
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(changed) }
        }
    }

    private fun payload(settings: PreprocessSettings) = RecoverySessionCodec.Payload("image-tones", 1L, UiMode.ANALYSIS,
        listOf(RecoveryHistoryEntry(kind = HistoryEventKind.SETTINGS, label = "Image adjustment",
            snapshot = RecoverySnapshot(null, null, null, -1, settings))), 0)

    @Test fun historyRoundTripPreservesTonesManualEditsAndIndependentDecoderPolarity() {
        val values = listOf(ImageAdjustment(), ImageAdjustment(25, -30, true), ImageAdjustment(-100, 100))
        val original = payload(PreprocessSettings()).copy(historyEntries = values.map { adjustment ->
            val settings = PreprocessSettings(imageAdjustment = adjustment, polarity = PolarityMode.NORMAL,
                preRecoveryEdits = mapOf(Coord(12, 12) to ModuleValue.ONE), lockManualBits = true)
            RecoveryHistoryEntry(kind = HistoryEventKind.SETTINGS, label = "Tones",
                snapshot = RecoverySnapshot(null, null, null, -1, settings))
        }, historyCursor = 1)
        val restored = RecoverySessionCodec.decode(RecoverySessionCodec.encode(original))
        assertEquals(original, restored)
        assertEquals(ModuleValue.ONE, restored.historyEntries[1].snapshot.preprocessSettings!!.fixedPreRecoveryEdits[Coord(12, 12)])
    }

    @Test fun checksummedInvalidTonesAndBooleanAreRejected() {
        val encoded = RecoverySessionCodec.encode(payload(PreprocessSettings()))
        for (offset in listOf(15, 11)) for (invalid in listOf(-101, 101, Int.MAX_VALUE)) {
            val changed = rewrite(encoded) { raw -> ByteBuffer.wrap(raw).putInt(raw.size - offset, invalid); raw }
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(changed) }
        }
        val invalidBoolean = rewrite(encoded) { it[it.size - 7] = 2; it }
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(invalidBoolean) }
        val missing = rewrite(encoded) { it.copyOfRange(0, it.size - 15) + it.copyOfRange(it.size - 2, it.size) }
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(missing) }
    }

    private fun rewrite(encoded: ByteArray, transform: (ByteArray) -> ByteArray): ByteArray {
        val raw = transform(GZIPInputStream(ByteArrayInputStream(encoded, 48, encoded.size - 48)).use { it.readBytes() })
        val compressed = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(raw) } }.toByteArray()
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(0x5152523A); it.writeInt(RecoverySessionCodec.CURRENT_VERSION); it.writeInt(raw.size); it.writeInt(compressed.size)
            it.write(MessageDigest.getInstance("SHA-256").digest(raw)); it.write(compressed)
        } }.toByteArray()
    }
}
