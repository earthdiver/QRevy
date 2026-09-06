package io.github.earthdiver.qrrepair.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.earthdiver.qrrepair.core.*
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** On-device session store. All files stay under internal app storage. */
class RecoverySessionStore(context: Context) {
    // Sensitive QR material lives in noBackupFilesDir by construction; XML backup exclusions remain defense-in-depth.
    private val root = File(context.noBackupFilesDir, "qr_sessions").apply { mkdirs() }
    private val legacyRoot = File(context.filesDir, "qr_sessions")

    init {
        migrateLegacyRoot()
        restoreChecksumValidDecodeQuarantines()
        trimToSavedSessionLimit()
    }

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * Stores the decoded source bitmap losslessly. The content-addressed PNG is committed first and
     * a tiny `source.ref` pointer is atomically switched last, so process death cannot create a
     * half-updated image/hash pair.
     */
    fun saveSourceBitmap(sessionId: String, bitmap: Bitmap) {
        requireSafeId(sessionId)
        require(bitmap.width > 0 && bitmap.height > 0 && bitmap.width.toLong() * bitmap.height <= MAX_SOURCE_PIXELS)
        val dir = dir(sessionId).apply { mkdirs() }
        val tmp = File(dir, "source-${UUID.randomUUID()}.png.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG compression failed" }
                out.flush()
                out.fd.sync()
            }
            require(tmp.length() in 1..MAX_SOURCE_IMAGE_BYTES) { "source image exceeds size limit" }
            val hashHex = sha256File(tmp).toHex()
            val canonical = File(dir, "source-$hashHex.png")
            if (canonical.exists()) {
                require(canonical.length() in 1..MAX_SOURCE_IMAGE_BYTES && MessageDigest.isEqual(sha256File(canonical), hexToBytes(hashHex))) {
                    "existing source image failed integrity check"
                }
                tmp.delete()
            } else {
                atomicReplace(tmp, canonical)
            }
            // The pointer is the commit record and is written only after the PNG is durable.
            atomicWrite(File(dir, "source.ref"), hashHex.toByteArray(Charsets.US_ASCII))
            cleanupObsoleteSources(dir, canonical)
        } finally {
            tmp.delete()
        }
    }

    fun save(sessionId: String, uiMode: UiMode, history: RecoverySessionHistory) =
        save(sessionId, uiMode, history.entries.toList(), history.cursor)

    fun save(sessionId: String, uiMode: UiMode, entries: List<RecoveryHistoryEntry>, cursor: Int) {
        requireSafeId(sessionId)
        val payload = RecoverySessionCodec.Payload(
            id = sessionId,
            updatedAtMs = System.currentTimeMillis(),
            uiMode = uiMode,
            historyEntries = entries,
            historyCursor = cursor,
        )
        val bytes = RecoverySessionCodec.encode(payload)
        val dir = dir(sessionId).apply { mkdirs() }
        atomicWrite(File(dir, "session.qrs"), bytes)
        trimToSavedSessionLimit(protectedSessionId = sessionId)
    }

    fun load(sessionId: String): StoredSession? {
        requireSafeId(sessionId)
        val primary = File(dir(sessionId), "session.qrs")
        if (primary.exists()) safeRead(primary)?.let { return it }
        val legacy = File(File(legacyRoot, sessionId), "session.qrs")
        return legacy.takeIf { it.exists() }?.let(::safeRead)
    }

    fun loadMostRecent(): StoredSession? {
        for (file in allSessionFiles().sortedByDescending { it.lastModified() }) safeRead(file)?.let { return it }
        return null
    }

    fun listRecent(limit: Int = MAX_SAVED_SESSIONS): List<SessionSummary> = sessionSummaries()
        .take(limit.coerceIn(0, MAX_SAVED_SESSIONS))

    private fun sessionSummaries(): List<SessionSummary> = allSessionFiles()
        .mapNotNull { file ->
            val loaded = safeRead(file, loadBitmap = false) ?: return@mapNotNull null
            SessionSummary(
                loaded.id,
                loaded.updatedAtMs,
                loaded.uiMode,
                loaded.historyEntries.size,
                loaded.historyEntries.getOrNull(loaded.historyCursor)?.label.orEmpty(),
            )
        }
        .groupBy { it.id }
        .mapNotNull { (_, sameId) -> sameId.maxByOrNull { it.updatedAtMs } }
        .sortedByDescending { it.updatedAtMs }

    fun delete(sessionId: String) {
        requireSafeId(sessionId)
        dir(sessionId).deleteRecursively()
        File(legacyRoot, sessionId).deleteRecursively()
        if (dir(sessionId).exists() || File(legacyRoot, sessionId).exists()) {
            throw IOException("session deletion failed")
        }
    }

    fun deleteAll() {
        listOf(root, legacyRoot)
            .distinctBy { it.absolutePath }
            .flatMap { base ->
                base.listFiles()
                    ?.filter { it.isDirectory && RecoverySessionCodec.isSafeSessionId(it.name) }
                    .orEmpty()
            }
            .forEach { it.deleteRecursively() }
        val remaining = listOf(root, legacyRoot).any { base ->
            base.listFiles()?.any { it.isDirectory && RecoverySessionCodec.isSafeSessionId(it.name) } == true
        }
        if (remaining) throw IOException("session deletion failed")
    }

    private fun trimToSavedSessionLimit(protectedSessionId: String? = null) {
        val summaries = sessionSummaries()
        val retainedIds = mutableSetOf<String>()
        protectedSessionId?.let(retainedIds::add)
        summaries.asSequence()
            .map { it.id }
            .filterNot(retainedIds::contains)
            .take(MAX_SAVED_SESSIONS - retainedIds.size)
            .forEach(retainedIds::add)
        summaries.asSequence()
            .map { it.id }
            .filterNot(retainedIds::contains)
            .forEach(::delete)
    }


    private fun allSessionFiles(): List<File> = listOf(root, legacyRoot)
        .distinctBy { it.absolutePath }
        .flatMap { base ->
            base.listFiles()
                ?.filter { it.isDirectory && RecoverySessionCodec.isSafeSessionId(it.name) }
                ?.map { File(it, "session.qrs") }
                ?.filter { it.exists() }
                .orEmpty()
        }

    /**
     * Older builds could misclassify a source-image I/O failure as a QRR decode failure and move
     * the valid timeline aside. Restore only current-format files that pass the codec checksum and
     * full bounded validation; genuinely corrupt files remain quarantined.
     */
    private fun restoreChecksumValidDecodeQuarantines() {
        listOf(root, legacyRoot).distinctBy { it.absolutePath }.forEach { base ->
            base.listFiles()?.filter { it.isDirectory && RecoverySessionCodec.isSafeSessionId(it.name) }?.forEach { folder ->
                val primary = File(folder, "session.qrs")
                if (primary.exists()) return@forEach
                val recoverable = folder.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("session.qrs.corrupt-") && it.name.endsWith("-decode") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.firstOrNull { candidate ->
                        candidate.length() in 1..RecoverySessionCodec.MAX_SESSION_FILE_BYTES.toLong() &&
                            runCatching { RecoverySessionCodec.decode(candidate.readBytes()) }.isSuccess
                    }
                    ?: return@forEach
                runCatching { atomicReplace(recoverable, primary) }
            }
        }
    }

    /** Best-effort one-time move of v0.5 filesDir sessions into noBackupFilesDir. */
    private fun migrateLegacyRoot() {
        if (!legacyRoot.exists() || legacyRoot.absolutePath == root.absolutePath) return
        legacyRoot.listFiles()?.filter { it.isDirectory && RecoverySessionCodec.isSafeSessionId(it.name) }?.forEach { oldFolder ->
            val target = File(root, oldFolder.name)
            if (target.exists()) return@forEach
            runCatching {
                try {
                    Files.move(oldFolder.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    if (!oldFolder.copyRecursively(target, overwrite = false)) throw IOException("legacy session copy failed")
                    val copiedSession = File(target, "session.qrs")
                    if (copiedSession.exists() && safeRead(copiedSession, loadBitmap = false) == null) {
                        target.deleteRecursively()
                        throw IOException("legacy session verification failed")
                    }
                    oldFolder.deleteRecursively()
                }
            }
        }
        runCatching { if (legacyRoot.listFiles().isNullOrEmpty()) legacyRoot.delete() }
    }

    private fun safeRead(file: File, loadBitmap: Boolean = true): StoredSession? = try {
        if (file.length() !in 1..RecoverySessionCodec.MAX_SESSION_FILE_BYTES.toLong()) {
            quarantine(file, "size")
            null
        } else {
            val payload = RecoverySessionCodec.decode(file.readBytes())
            require(payload.id == file.parentFile?.name) { "session id does not match storage directory" }
            // Source PNG integrity is independent from the checksummed QRR timeline. A read,
            // hash, or bitmap-decode failure here must not quarantine the valid session file.
            val source = if (loadBitmap) {
                runCatching { loadVerifiedSource(requireNotNull(file.parentFile)) }.getOrNull()
            } else {
                null
            }
            // The checksummed QRR history remains useful even when its separately stored source
            // image is missing or fails integrity verification. Never use an unverified bitmap,
            // but do not make the timeline and recovered matrices inaccessible with it.
            StoredSession(
                payload.id,
                payload.updatedAtMs,
                payload.uiMode,
                payload.historyEntries,
                payload.historyCursor,
                source,
            )
        }
    } catch (_: Exception) {
        quarantine(file, "decode")
        null
    }

    private fun loadVerifiedSource(folder: File): Bitmap? {
        val ref = File(folder, "source.ref")
        if (ref.exists()) {
            if (ref.length() !in 64L..66L) return null
            val expected = runCatching { ref.readText(Charsets.US_ASCII).trim().lowercase() }.getOrNull() ?: return null
            if (!isSha256Hex(expected)) return null
            val png = File(folder, "source-$expected.png")
            if (!png.exists() || png.length() !in 1..MAX_SOURCE_IMAGE_BYTES) return null
            if (!MessageDigest.isEqual(sha256File(png), hexToBytes(expected))) return null
            return decodeBitmapBounded(png)
        }

        // Compatibility with the early v0.6 source.png + source.sha256 pair.
        val png = File(folder, "source.png")
        if (png.exists()) {
            if (png.length() !in 1..MAX_SOURCE_IMAGE_BYTES) return null
            val hashFile = File(folder, "source.sha256")
            if (!hashFile.exists()) return null
            val expected = runCatching { hashFile.readText(Charsets.US_ASCII).trim().lowercase() }.getOrNull() ?: return null
            if (!isSha256Hex(expected) || !MessageDigest.isEqual(sha256File(png), hexToBytes(expected))) return null
            return decodeBitmapBounded(png)
        }
        // v0.5 migration compatibility only. It is not written by v0.6.
        val legacy = File(folder, "source.jpg")
        return legacy.takeIf { it.exists() && it.length() in 1..MAX_SOURCE_IMAGE_BYTES }?.let(::decodeBitmapBounded)
    }


    private fun cleanupObsoleteSources(folder: File, keep: File) {
        runCatching {
            folder.listFiles()?.forEach { file ->
                val obsoleteContentAddressed = file.name.startsWith("source-") && file.name.endsWith(".png") && file != keep
                val legacy = file.name == "source.png" || file.name == "source.sha256" || file.name == "source.jpg"
                if (obsoleteContentAddressed || legacy) file.delete()
            }
        }
    }

    private fun isSha256Hex(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun hexToBytes(value: String): ByteArray {
        require(isSha256Hex(value))
        return ByteArray(32) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun decodeBitmapBounded(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().buffered().use { input -> BitmapFactory.decodeStream(input, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth > MAX_SOURCE_DIMENSION || bounds.outHeight > MAX_SOURCE_DIMENSION) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_SOURCE_PIXELS) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun quarantine(file: File, reason: String) {
        runCatching {
            val q = File(file.parentFile, "session.qrs.corrupt-${System.currentTimeMillis()}-$reason")
            if (!file.renameTo(q)) file.copyTo(q, overwrite = false).also { file.delete() }
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
            atomicReplace(tmp, target)
        } finally {
            tmp.delete()
        }
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        check(target.exists()) { "atomic replace failed for ${target.name}" }
    }

    private fun sha256File(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun requireSafeId(id: String) = require(RecoverySessionCodec.isSafeSessionId(id)) { "invalid session id" }
    private fun dir(sessionId: String) = File(root, sessionId)

    companion object {
        const val MAX_SAVED_SESSIONS = 20
        // MainActivity downsamples imported images to <=2600px per side; keep restore limits aligned.
        private const val MAX_SOURCE_IMAGE_BYTES = 32L * 1024 * 1024
        private const val MAX_SOURCE_DIMENSION = 2_600
        private const val MAX_SOURCE_PIXELS = 6_760_000L
    }
}

data class StoredSession(
    val id: String,
    val updatedAtMs: Long,
    val uiMode: UiMode,
    val historyEntries: List<RecoveryHistoryEntry>,
    val historyCursor: Int,
    val sourceBitmap: Bitmap?,
)

data class SessionSummary(
    val id: String,
    val updatedAtMs: Long,
    val uiMode: UiMode,
    val historyCount: Int,
    val lastLabel: String,
)
