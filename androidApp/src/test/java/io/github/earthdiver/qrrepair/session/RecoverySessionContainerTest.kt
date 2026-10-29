package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import io.github.earthdiver.qrrepair.core.UiMode
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class RecoverySessionContainerTest {
    private val payload = RecoverySessionCodec.Payload("binary-layout", 123L, UiMode.ANALYSIS,
        listOf(RecoveryHistoryEntry("entry-1", 42L, HistoryEventKind.USER_EDIT, "Edit\u0000\ud83d\udd0d", "Detail",
            RecoverySnapshot(null, null, null, -1, samplingInverted = false, oneTimeSingleSegment = true))), 0)

    /** Explicit schema-27 bytes, independent of the production writer and reader. */
    private fun rawFixture(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF("binary-layout"); out.writeLong(123L)
            out.writeInt(1) // Analysis UI mode.
            out.writeInt(0); out.writeInt(1) // Cursor and entry count.
            out.writeUTF("entry-1"); out.writeLong(42L)
            out.writeInt(2) // User edit.
            out.writeUTF("Edit\u0000\ud83d\udd0d"); out.writeUTF("Detail")
            out.writeBoolean(false); out.writeBoolean(false); out.writeBoolean(false) // No geometry or matrices.
            out.writeInt(-1)
            out.writeBoolean(false); out.writeBoolean(false) // No settings or QR selection.
            out.writeBoolean(true); out.writeBoolean(false) // Recorded normal sampling polarity.
            out.writeBoolean(true) // Runtime single-segment override.
        }
    }.toByteArray()

    @Test fun currentWriterPreservesSettingsFreePayloadAndReadsSchema27() {
        val encoded = RecoverySessionCodec.encode(payload)
        val header = ByteBuffer.wrap(encoded)
        assertEquals(0x5152523A, header.getInt(0))
        assertEquals(28, header.getInt(4))
        val raw = GZIPInputStream(ByteArrayInputStream(encoded, 48, encoded.size - 48)).use { it.readBytes() }
        assertArrayEquals(rawFixture(), raw)
        assertEquals(raw.size, header.getInt(8))
        assertEquals(encoded.size - 48, header.getInt(12))
        assertArrayEquals(hash(raw), encoded.copyOfRange(16, 48))
        assertEquals(payload, RecoverySessionCodec.decode(container(rawFixture())))
    }

    @Test fun declaredSizesAreCheckedBeforeAllocatingOrParsingThePayload() {
        val encoded = RecoverySessionCodec.encode(payload)
        for (invalid in listOf(-1, 0, RecoverySessionCodec.MAX_SESSION_PAYLOAD_BYTES + 1, Int.MAX_VALUE)) {
            assertRejected(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(8, invalid) }, "invalid raw payload size")
        }
        for (invalid in listOf(-1, 0, RecoverySessionCodec.MAX_SESSION_FILE_BYTES + 1, Int.MAX_VALUE)) {
            assertRejected(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(12, invalid) }, "invalid compressed payload size")
        }
        assertRejected(encoded + byteArrayOf(0), "session length mismatch")
        assertRejected(encoded.copyOf(encoded.size - 1), "session length mismatch")
    }

    @Test fun fileSizeLimitAndShortHeadersAreRejected() {
        assertRejected(ByteArray(RecoverySessionCodec.MAX_SESSION_FILE_BYTES + 1), "session file exceeds size limit")
        for (length in 0 until 48) assertRejected(ByteArray(length), "session file is truncated")
    }

    @Test fun decompressionMustMatchTheBoundedDeclaredSizeExactly() {
        val encoded = RecoverySessionCodec.encode(payload)
        val rawSize = ByteBuffer.wrap(encoded).getInt(8)
        assertRejected(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(8, rawSize - 1) },
            "uncompressed session exceeds size limit")
        assertRejected(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(8, rawSize + 1) },
            "uncompressed session size mismatch")
    }

    @Test fun checksumIsVerifiedBeforeMalformedPayloadFieldsAreRead() {
        val encoded = container(byteArrayOf(0)) // Too short even for the first UTF field.
        val incorrectHash = encoded.copyOf().also { it[16] = (it[16].toInt() xor 1).toByte() }
        assertRejected(incorrectHash, "session SHA-256 mismatch")
        assertRejected(encoded, "cannot decode session")
    }

    @Test fun validChecksumsDoNotPermitTrailingPayloadBytes() {
        assertRejected(container(rawFixture() + byteArrayOf(42)), "trailing bytes in session payload")
    }

    @Test fun corruptGzipRemainsATypedSessionError() {
        val encoded = container(rawFixture())
        encoded[48] = 0 // Invalid GZIP magic; preserve the QRR header and declared lengths.
        val error = assertRejected(encoded, "GZIP payload is corrupt")
        assertNotNull(error.cause)
    }

    private fun assertRejected(bytes: ByteArray, message: String): RecoverySessionCodec.CorruptSessionException =
        assertThrows(RecoverySessionCodec.CorruptSessionException::class.java) {
            RecoverySessionCodec.decode(bytes)
        }.also { assertEquals(message, it.message) }

    private fun hash(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw)

    private fun container(raw: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { it.write(raw) }
        }.toByteArray()
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(0x5152523A); out.writeInt(27)
                out.writeInt(raw.size); out.writeInt(compressed.size)
                out.write(hash(raw)); out.write(compressed)
            }
        }.toByteArray()
    }
}
