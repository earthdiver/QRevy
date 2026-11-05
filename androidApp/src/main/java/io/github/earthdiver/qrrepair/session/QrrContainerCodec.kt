package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.localizedText
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec.CURRENT_VERSION
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec.CorruptSessionException
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec.MAX_SESSION_FILE_BYTES
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec.MAX_SESSION_PAYLOAD_BYTES
import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** QRR framing, streaming compression, size bounds, and checksum verification before schema parsing. */
internal object QrrContainerCodec {
    private const val MAGIC = 0x5152523A // QRR container signature; the schema version follows.
    private const val HASH_BYTES = 32

    data class DecodedPayload(val bytes: ByteArray, val formatVersion: Int)

    fun encode(writePayload: (DataOutputStream) -> Unit): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val compressedBytes = ByteArrayOutputStream()
        val filePayload = LimitedOutputStream(compressedBytes, MAX_SESSION_FILE_BYTES.toLong() - 16 - HASH_BYTES)
        val raw = LimitedOutputStream(DigestOutputStream(GZIPOutputStream(filePayload), digest),
            MAX_SESSION_PAYLOAD_BYTES.toLong())
        DataOutputStream(BufferedOutputStream(raw)).use(writePayload)
        val compressed = compressedBytes.toByteArray()
        val hash = digest.digest()
        return ByteArrayOutputStream(16 + HASH_BYTES + compressed.size).use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(CURRENT_VERSION)
                out.writeInt(raw.written.toInt())
                out.writeInt(compressed.size)
                out.write(hash)
                out.write(compressed)
            }
            bytes.toByteArray()
        }
    }

    fun decode(fileBytes: ByteArray): DecodedPayload {
        if (fileBytes.size > MAX_SESSION_FILE_BYTES) throw CorruptSessionException("session file exceeds size limit")
        if (fileBytes.size < 16 + HASH_BYTES) throw CorruptSessionException("session file is truncated")
        DataInputStream(BufferedInputStream(ByteArrayInputStream(fileBytes))).use { input ->
            val magic = input.readInt()
            requireCorrupt(magic == MAGIC, "invalid QRR magic")
            val version = input.readInt()
            requireCorrupt(version in 23..CURRENT_VERSION, localizedText("対応していないQRRセッションの形式です（version $version、対応: 23〜$CURRENT_VERSION）", "Unsupported QRR session version $version; supported: 23..$CURRENT_VERSION"))
            val rawSize = input.readInt()
            val compressedSize = input.readInt()
            requireCorrupt(rawSize in 1..MAX_SESSION_PAYLOAD_BYTES, "invalid raw payload size")
            requireCorrupt(compressedSize in 1..MAX_SESSION_FILE_BYTES, "invalid compressed payload size")
            requireCorrupt(fileBytes.size == 16 + HASH_BYTES + compressedSize, "session length mismatch")
            val expectedHash = ByteArray(HASH_BYTES)
            input.readFully(expectedHash)
            val compressed = ByteArray(compressedSize)
            input.readFully(compressed)
            val raw = gunzipBounded(compressed, rawSize)
            requireCorrupt(MessageDigest.isEqual(expectedHash, sha256(raw)), "session SHA-256 mismatch")
            return DecodedPayload(raw, version)
        }
    }

    private class LimitedOutputStream(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
        var written: Long = 0
            private set
        override fun write(value: Int) {
            require(written < limit) { "session payload too large" }
            out.write(value)
            written++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length.toLong() <= limit - written) { "session payload too large" }
            out.write(bytes, offset, length)
            written += length
        }
    }

    private fun gunzipBounded(compressed: ByteArray, expectedSize: Int): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val raw = readBounded(input, expectedSize)
            requireCorrupt(raw.size == expectedSize, "uncompressed session size mismatch")
            raw
        }
    } catch (e: CorruptSessionException) {
        throw e
    } catch (e: Exception) {
        throw CorruptSessionException("GZIP payload is corrupt", e)
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw CorruptSessionException("uncompressed session exceeds size limit")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun requireCorrupt(condition: Boolean, message: String) {
        if (!condition) throw CorruptSessionException(message)
    }
}
