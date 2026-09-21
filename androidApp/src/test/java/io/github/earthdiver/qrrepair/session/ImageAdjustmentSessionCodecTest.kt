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

    @Test fun schema20DefaultsToUnadjustedPixels() {
        val settings = PreprocessSettings(blackMax = 50, whiteMin = 200, polarity = PolarityMode.INVERTED)
        val encoded = RecoverySessionCodec.encode(payload(settings))
        val old = rewrite(encoded, 20) { it.copyOfRange(0, it.size - 11) + it.copyOfRange(it.size - 2, it.size) }
        assertEquals(settings, RecoverySessionCodec.decode(old).historyEntries.single().snapshot.preprocessSettings)
    }

    @Test fun checksummedInvalidTonesAndBooleanAreRejected() {
        val encoded = RecoverySessionCodec.encode(payload(PreprocessSettings()))
        for (offset in listOf(11, 7)) for (invalid in listOf(-101, 101, Int.MAX_VALUE)) {
            val changed = rewrite(encoded) { raw -> ByteBuffer.wrap(raw).putInt(raw.size - offset, invalid); raw }
            assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(changed) }
        }
        val invalidBoolean = rewrite(encoded) { it[it.size - 3] = 2; it }
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(invalidBoolean) }
        val missing = rewrite(encoded) { it.copyOfRange(0, it.size - 11) + it.copyOfRange(it.size - 2, it.size) }
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) { RecoverySessionCodec.decode(missing) }
    }

    private fun rewrite(encoded: ByteArray, version: Int = RecoverySessionCodec.CURRENT_VERSION, transform: (ByteArray) -> ByteArray): ByteArray {
        val raw = transform(GZIPInputStream(ByteArrayInputStream(encoded, 48, encoded.size - 48)).use { it.readBytes() })
        val compressed = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(raw) } }.toByteArray()
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(0x5152523A); it.writeInt(version); it.writeInt(raw.size); it.writeInt(compressed.size)
            it.write(MessageDigest.getInstance("SHA-256").digest(raw)); it.write(compressed)
        } }.toByteArray()
    }
}
