package io.github.earthdiver.qrrepair.session

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

class ErrorLimitSessionCodecTest {
    private fun payload(settings: PreprocessSettings) = RecoverySessionCodec.Payload(
        "error-limit-settings", 1L, UiMode.ANALYSIS,
        listOf(RecoveryHistoryEntry(kind = HistoryEventKind.SETTINGS, label = "Error limits",
            snapshot = RecoverySnapshot(null, null, null, -1, settings))), 0)

    @Test fun allIndependentModesAndStoredManualValuesRoundTripAcrossHistory() {
        val settings = ErrorLimitMode.entries.flatMap { format ->
            ErrorLimitMode.entries.map { data ->
                PreprocessSettings(formatMismatchLimit = 7, dataEccMismatchLimit = 19,
                    formatMismatchLimitMode = format, dataEccMismatchLimitMode = data)
            }
        }
        val original = payload(settings.first()).copy(historyEntries = settings.map {
            RecoveryHistoryEntry(kind = HistoryEventKind.SETTINGS, label = "Limits",
                snapshot = RecoverySnapshot(null, null, null, -1, it))
        }, historyCursor = settings.lastIndex)
        val encoded = RecoverySessionCodec.encode(original)
        assertEquals(RecoverySessionCodec.CURRENT_VERSION, ByteBuffer.wrap(encoded).getInt(4))
        val decoded = RecoverySessionCodec.decode(encoded)
        assertEquals(settings, decoded.historyEntries.map { it.snapshot.preprocessSettings })
        assertEquals(settings.lastIndex, decoded.historyCursor)
    }

    @Test fun manualUnlimitedRoundTripsWithoutBecomingAutomatic() {
        val settings = PreprocessSettings(formatMismatchLimit = null, dataEccMismatchLimit = null,
            formatMismatchLimitMode = ErrorLimitMode.MANUAL, dataEccMismatchLimitMode = ErrorLimitMode.MANUAL)
        val decoded = RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload(settings)))
            .historyEntries.single().snapshot.preprocessSettings
        assertEquals(settings, decoded)
    }

    @Test fun checksummedInvalidModeOrdinalsAreRejectedForEitherLimit() {
        val encoded = RecoverySessionCodec.encode(payload(PreprocessSettings()))
        for (offsetFromEnd in listOf(27, 23)) {
            for (invalid in listOf(-1, ErrorLimitMode.entries.size, Int.MAX_VALUE)) {
                val altered = rewrite(encoded) { raw ->
                    ByteBuffer.wrap(raw).putInt(raw.size - offsetFromEnd, invalid)
                    raw
                }
                assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
                    RecoverySessionCodec.decode(altered)
                }
            }
        }
    }

    @Test fun currentSchemaRejectsMissingModeFieldsEvenWithAValidChecksum() {
        val encoded = RecoverySessionCodec.encode(payload(PreprocessSettings()))
        val missing = rewrite(encoded) { raw ->
            raw.copyOfRange(0, raw.size - 27) + raw.copyOfRange(raw.size - 19, raw.size)
        }
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(missing)
        }
    }

    private fun rewrite(encoded: ByteArray, transform: (ByteArray) -> ByteArray): ByteArray {
        val raw = transform(GZIPInputStream(ByteArrayInputStream(encoded, 48, encoded.size - 48)).use { it.readBytes() })
        val compressed = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { it.write(raw) }
        }.toByteArray()
        return ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x5152523A); out.writeInt(RecoverySessionCodec.CURRENT_VERSION); out.writeInt(raw.size); out.writeInt(compressed.size)
            out.write(MessageDigest.getInstance("SHA-256").digest(raw)); out.write(compressed)
        } }.toByteArray()
    }
}
