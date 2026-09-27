package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version
import java.util.UUID

enum class UiMode { SIMPLE, ANALYSIS }

enum class HistoryEventKind {
    IMPORT,
    AUTO_RECOVERY,
    USER_EDIT,
    APPLY_RECOVERY,
    GEOMETRY,
    CANDIDATE,
    SYSTEM,
    SETTINGS,
}

/** Stable QR identity for restoring a selection after candidate ranking changes. */
class RecoverySelectionIdentity(
    val version: Int,
    val ecLevel: Int,
    val mask: Int,
    correctedCodewords: ByteArray,
) {
    private val codewords = correctedCodewords.copyOf()

    init {
        require(version in 1..40 && ecLevel in 0..3 && mask in 0..7)
        require(codewords.size == Version.getVersionForNumber(version).totalCodewords)
    }

    fun codewordBytes(): ByteArray = codewords.copyOf()

    fun matches(candidate: RecoveryCandidate): Boolean = version == candidate.version &&
        ecLevel == candidate.ecLevel.ordinal && mask == candidate.dataMask &&
        codewords.size == candidate.correctedInterleaved.size && codewords.indices.all {
            (codewords[it].toInt() and 0xff) == candidate.correctedInterleaved[it]
        }

    override fun equals(other: Any?): Boolean = other is RecoverySelectionIdentity &&
        version == other.version && ecLevel == other.ecLevel && mask == other.mask &&
        codewords.contentEquals(other.codewords)

    override fun hashCode(): Int = 31 * (31 * (31 * version + ecLevel) + mask) + codewords.contentHashCode()

    companion object {
        fun from(candidate: RecoveryCandidate): RecoverySelectionIdentity {
            require(candidate.correctedInterleaved.all { it in 0..255 })
            return RecoverySelectionIdentity(candidate.version, candidate.ecLevel.ordinal, candidate.dataMask,
                ByteArray(candidate.correctedInterleaved.size) { candidate.correctedInterleaved[it].toByte() })
        }
    }
}

data class RecoverySnapshot(
    val geometry: GridGeometry?,
    val sourceMatrix: QrMatrix?,
    val workingMatrix: QrMatrix?,
    val recoveryIndex: Int,
    /** Preprocessing settings for checkpoints that include image sampling. */
    val preprocessSettings: PreprocessSettings? = null,
    /** Identity of the selected candidate, if a candidate was selected at this checkpoint. */
    val selectedQr: RecoverySelectionIdentity? = null,
    /** Actual sampling polarity, independent of the saved automatic-search preference. */
    val samplingInverted: Boolean? = null,
)

data class RecoveryHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val kind: HistoryEventKind,
    val label: String,
    val detail: String = "",
    val snapshot: RecoverySnapshot,
)

/**
 * Full-session history. The cursor points at the active snapshot, so Undo/Redo and
 * arbitrary history jumps share one source of truth.
 */
class RecoverySessionHistory(private val maxEntries: Int = 40) {
    private val items = ArrayList<RecoveryHistoryEntry>()
    var cursor: Int = -1
        private set

    val entries: List<RecoveryHistoryEntry> get() = items
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor >= 0 && cursor < items.lastIndex
    val current: RecoveryHistoryEntry? get() = items.getOrNull(cursor)

    fun clear() {
        items.clear()
        cursor = -1
    }

    fun replace(entries: List<RecoveryHistoryEntry>, cursor: Int) {
        items.clear()
        val dropped = (entries.size - maxEntries).coerceAtLeast(0)
        items.addAll(entries.drop(dropped))
        this.cursor = if (items.isEmpty()) {
            -1
        } else {
            (cursor - dropped).coerceIn(0, items.lastIndex)
        }
    }

    fun record(entry: RecoveryHistoryEntry) {
        while (items.lastIndex > cursor) items.removeAt(items.lastIndex)
        items += entry
        if (items.size > maxEntries) {
            val removeCount = items.size - maxEntries
            repeat(removeCount) { items.removeAt(0) }
        }
        cursor = items.lastIndex
    }

    /** Drops oldest removable entries, keeping the active and newest snapshots and their order. */
    fun trimToByteLimit(maxBytes: Long, entryBytes: (RecoveryHistoryEntry) -> Long): Int {
        require(maxBytes >= 0)
        if (items.isEmpty()) return 0
        val sizes = items.map(entryBytes)
        require(sizes.all { it >= 0 })
        var total = sizes.fold(0L, Math::addExact)
        if (total <= maxBytes) return 0
        val retained = items.indices.toMutableList()
        while (total > maxBytes) {
            val removable = retained.indexOfFirst { it != cursor && it != items.lastIndex }
            require(removable >= 0) { "Active and latest history snapshots exceed the byte limit" }
            total -= sizes[retained.removeAt(removable)]
        }
        val nextCursor = retained.indexOf(cursor)
        val next = retained.map(items::get)
        val removed = items.size - next.size
        items.clear()
        items.addAll(next)
        cursor = nextCursor
        return removed
    }

    fun undo(): RecoveryHistoryEntry? {
        if (!canUndo) return null
        cursor--
        return items[cursor]
    }

    fun redo(): RecoveryHistoryEntry? {
        if (!canRedo) return null
        cursor++
        return items[cursor]
    }

    fun jump(index: Int): RecoveryHistoryEntry? {
        if (index !in items.indices) return null
        cursor = index
        return items[index]
    }

    fun updateCurrentSnapshot(snapshot: RecoverySnapshot) {
        if (cursor !in items.indices) return
        items[cursor] = items[cursor].copy(snapshot = snapshot)
    }
}
