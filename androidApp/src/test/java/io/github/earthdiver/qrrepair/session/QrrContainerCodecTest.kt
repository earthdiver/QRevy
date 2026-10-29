package io.github.earthdiver.qrrepair.session

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class QrrContainerCodecTest {
    private val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val chunkCount = RecoverySessionCodec.MAX_SESSION_PAYLOAD_BYTES / chunk.size

    @Test fun streamingPayloadCanReachTheExactRawLimit() {
        val encoded = QrrContainerCodec.encode { out -> repeat(chunkCount) { out.write(chunk) } }
        assertEquals(RecoverySessionCodec.MAX_SESSION_PAYLOAD_BYTES, ByteBuffer.wrap(encoded).getInt(8))
        assertTrue(encoded.size <= RecoverySessionCodec.MAX_SESSION_FILE_BYTES)
        val decoded = QrrContainerCodec.decode(encoded)
        assertEquals(RecoverySessionCodec.CURRENT_VERSION, decoded.formatVersion)
        assertEquals(RecoverySessionCodec.MAX_SESSION_PAYLOAD_BYTES, decoded.bytes.size)
        assertTrue(decoded.bytes.indices.all { decoded.bytes[it] == chunk[it % chunk.size] })
    }

    @Test fun streamingPayloadCannotExceedTheRawLimitEvenWhenItCompressesWell() {
        for (singleByte in listOf(false, true)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                QrrContainerCodec.encode { out ->
                    repeat(chunkCount) { out.write(chunk) }
                    if (singleByte) out.writeByte(0) else out.write(byteArrayOf(0))
                }
            }
            assertEquals("session payload too large", error.message)
        }
    }
}
