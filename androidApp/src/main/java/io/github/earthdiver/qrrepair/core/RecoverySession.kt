package io.github.earthdiver.qrrepair.core

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

data class RecoverySnapshot(
    val geometry: GridGeometry?,
    val sourceMatrix: QrMatrix?,
    val workingMatrix: QrMatrix?,
    val recoveryIndex: Int,
    /** Present for reproducible repair-setting checkpoints; null in migrated older sessions. */
    val preprocessSettings: PreprocessSettings? = null,
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
