package io.github.earthdiver.qrrepair.session

import com.google.zxing.qrcode.decoder.Version
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySessionHistory
import io.github.earthdiver.qrrepair.core.UiMode
import java.io.IOException

/** Android-independent, bounded and checksummed persistence codec for repair sessions. */
object RecoverySessionCodec {
    const val CURRENT_VERSION = 28
    const val MAX_HISTORY_ENTRIES = 40
    const val MAX_SESSION_PAYLOAD_BYTES = 16 * 1024 * 1024
    const val MAX_SESSION_FILE_BYTES = 20 * 1024 * 1024

    data class Payload(
        val id: String,
        val updatedAtMs: Long,
        val uiMode: UiMode,
        val historyEntries: List<RecoveryHistoryEntry>,
        val historyCursor: Int,
    )

    class CorruptSessionException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun encode(payload: Payload): ByteArray {
        val retained = fitHistoryToPayloadLimit(payload)
        return QrrContainerCodec.encode { RecoverySessionPayloadCodec.write(it, retained) }
    }

    /** Trims live history before a save snapshot is captured; matrix sizing never scans cells. */
    fun trimHistoryToPayloadLimit(
        history: RecoverySessionHistory,
        sessionId: String,
        maxRetainedBytes: Long? = 32L * 1024 * 1024,
    ): Int {
        require(isSafeSessionId(sessionId)) { "invalid session id" }
        var removed = if (maxRetainedBytes != null) history.trimToByteLimit(maxRetainedBytes, ::retainedHistoryEntryBytes) else 0
        val header = RecoverySessionPayloadCodec.encodedPayloadBytes(Payload(sessionId, 0, UiMode.SIMPLE, emptyList(), -1))
        removed += history.trimToByteLimit(MAX_SESSION_PAYLOAD_BYTES - header, ::encodedHistoryEntryBytes)
        return removed
    }

    /** Validates all supplied snapshots before trimming older history to the unchanged file limit. */
    fun fitHistoryToPayloadLimit(payload: Payload): Payload {
        RecoverySessionPayloadCodec.validate(payload)
        val history = RecoverySessionHistory(MAX_HISTORY_ENTRIES)
        history.replace(payload.historyEntries, payload.historyCursor)
        if (trimHistoryToPayloadLimit(history, payload.id, maxRetainedBytes = null) == 0) return payload
        return payload.copy(historyEntries = history.entries.toList(), historyCursor = history.cursor)
    }

    /** Conservative snapshot accounting, not an exact measurement of the Android heap. */
    fun retainedHistoryEntryBytes(entry: RecoveryHistoryEntry): Long {
        val snapshot = entry.snapshot
        val cells = listOfNotNull(snapshot.sourceMatrix, snapshot.workingMatrix)
            .sumOf { it.dimension.toLong() * it.dimension }
        val settings = snapshot.preprocessSettings
        return 4096L + cells * 48 + (entry.id.length + entry.label.length + entry.detail.length) * 2L +
            (settings?.preRecoveryEdits?.size ?: 0) * 128L +
            (settings?.preRecoverySoftEdits?.size ?: 0) * 64L +
            (settings?.detectedFinders?.size ?: 0) * 256L +
            (snapshot.selectedQr?.let { Version.getVersionForNumber(it.version).totalCodewords } ?: 0)
    }

    internal fun encodedHistoryEntryBytes(entry: RecoveryHistoryEntry): Long =
        RecoverySessionPayloadCodec.encodedHistoryEntryBytes(entry)

    /** Decodes the supported QRR schemas with strict validation and explicit defaults. */
    fun decode(fileBytes: ByteArray): Payload {
        return try {
            val decoded = QrrContainerCodec.decode(fileBytes)
            RecoverySessionPayloadCodec.read(decoded.bytes, decoded.formatVersion)
        } catch (e: CorruptSessionException) {
            throw e
        } catch (e: Exception) {
            throw CorruptSessionException("cannot decode session", e)
        }
    }

    fun isValidQrDimension(d: Int): Boolean = d in 21..177 && (d - 17) % 4 == 0

    fun isSafeSessionId(id: String): Boolean = id.length in 1..80 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
