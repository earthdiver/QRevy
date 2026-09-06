package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.localizedText

internal data class PreRecoveryCellValue(
    val value: ModuleValue,
    val prioritySearch: Boolean,
)

internal enum class PriorityEditResult { CHANGED, UNCHANGED, LIMIT_EXCEEDED }

/** Owns pre-recovery cell edits and undo transactions independently of Android Views. */
internal class PreRecoveryCellEditState(
    initialEdits: Map<Coord, ModuleValue>,
    initialPrioritySearch: Set<Coord>,
    private val priorityLimit: Int = 4,
    private val validateEdits: (Map<Coord, ModuleValue>) -> Boolean = { true },
) {
    private val mutableEdits = linkedMapOf<Coord, ModuleValue>().apply { putAll(initialEdits) }
    private val mutablePrioritySearch = linkedSetOf<Coord>().apply { addAll(initialPrioritySearch) }
    private val undo = ArrayDeque<Map<Coord, PreRecoveryCellValue?>>()
    private val redo = ArrayDeque<Map<Coord, PreRecoveryCellValue?>>()

    val edits: Map<Coord, ModuleValue> get() = mutableEdits
    val prioritySearch: Set<Coord> get() = mutablePrioritySearch
    var selectedCells: Set<Coord> = emptySet()
        private set
    val undoCount: Int get() = undo.size
    val redoCount: Int get() = redo.size
    val compactCountLabel: String
        get() = localizedText(
            "変更操作: ${undoCount}回・変更セル: ${mutableEdits.size}セル　やり直し: ${redoCount}回",
            "Changes: $undoCount · Changed cells: ${mutableEdits.size} · Redo: $redoCount"
        )
    val countLabel: String
        get() = localizedText(
            "変更操作: ${undoCount}回・変更セル: ${mutableEdits.size}セル　やり直し: ${redoCount}回　優先探索: ${mutablePrioritySearch.size}/$priorityLimit",
            "Changes: $undoCount · changed cells: ${mutableEdits.size} · Redo: $redoCount · Priority search: ${mutablePrioritySearch.size}/$priorityLimit",
        )

    init {
        require(priorityLimit >= 0)
        require(mutablePrioritySearch.size <= priorityLimit)
        mutablePrioritySearch.forEach { coord ->
            require(mutableEdits[coord] == ModuleValue.ERASED) { "Priority search requires an erased edit" }
        }
        // Persisted edits predate this dialog's in-memory history. Keep them undoable as one
        // transaction instead of presenting an impossible zero-actions/nonzero-cells state.
        if (mutableEdits.isNotEmpty() || mutablePrioritySearch.isNotEmpty()) {
            undo.addLast((mutableEdits.keys + mutablePrioritySearch).associateWith { null })
        }
    }

    fun select(coords: Collection<Coord>): Set<Coord> {
        selectedCells = coords.toSet()
        return selectedCells
    }

    fun clearSelection() = select(emptySet())

    /** Independent draft/checkpoint; never retains a previous dialog's validation callback. */
    fun copyForEditing(
        validateEdits: (Map<Coord, ModuleValue>) -> Boolean = { true },
    ): PreRecoveryCellEditState = PreRecoveryCellEditState(
        mutableEdits, mutablePrioritySearch, priorityLimit, validateEdits,
    ).also { copy ->
        copy.undo.clear()
        copy.undo.addAll(undo.map { it.toMap() })
        copy.redo.addAll(redo.map { it.toMap() })
    }

    fun clearAll() {
        mutableEdits.clear()
        mutablePrioritySearch.clear()
        undo.clear()
        redo.clear()
        selectedCells = emptySet()
    }

    /** Keeps edits attached to the same physical modules when the provisional grid is rotated. */
    fun rotateCoordinates(dimension: Int, quarterTurns: Int) {
        require(dimension > 0)
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return
        fun rotate(coord: Coord): Coord = when (turns) {
            1 -> Coord(coord.y, dimension - 1 - coord.x)
            2 -> Coord(dimension - 1 - coord.x, dimension - 1 - coord.y)
            else -> Coord(dimension - 1 - coord.y, coord.x)
        }
        fun <T> Map<Coord, T>.rotated(): Map<Coord, T> = entries.associate { rotate(it.key) to it.value }

        val edits = mutableEdits.rotated()
        mutableEdits.clear()
        mutableEdits.putAll(edits)
        val priority = mutablePrioritySearch.mapTo(linkedSetOf(), ::rotate)
        mutablePrioritySearch.clear()
        mutablePrioritySearch.addAll(priority)
        selectedCells = selectedCells.mapTo(linkedSetOf(), ::rotate)

        val rotatedUndo = undo.map { it.rotated() }
        undo.clear()
        undo.addAll(rotatedUndo)
        val rotatedRedo = redo.map { it.rotated() }
        redo.clear()
        redo.addAll(rotatedRedo)
    }

    fun applyValues(
        coords: Collection<Coord>,
        value: ModuleValue,
        baselineValue: (Coord) -> ModuleValue?,
    ): Set<Coord> {
        val previous = linkedMapOf<Coord, PreRecoveryCellValue?>()
        val proposed = mutableEdits.toMutableMap()
        for (coord in coords.distinct()) {
            val baseline = baselineValue(coord) ?: continue
            val before = currentValue(coord)
            // An unchanged image value is not an explicit confirmation. Once edited, values
            // remain manual until the source button (or Undo) removes the override.
            if (before == null && value == baseline) continue
            val after = PreRecoveryCellValue(value, false)
            if (before == after) continue
            previous[coord] = before
            proposed[coord] = value
        }
        if (previous.isEmpty() || !accept(proposed)) return emptySet()
        previous.keys.forEach(mutablePrioritySearch::remove)
        mutableEdits.clear()
        mutableEdits.putAll(proposed)
        undo.addLast(previous)
        redo.clear()
        return select(previous.keys)
    }

    /** Mixed selections return to the image together, rather than inverting each cell. */
    val selectionHasManualEdits: Boolean get() = selectedCells.any { it in mutableEdits }

    fun makeManual(
        coords: Collection<Coord>,
        baselineValue: (Coord) -> ModuleValue?,
    ): Set<Coord> {
        val previous = linkedMapOf<Coord, PreRecoveryCellValue?>()
        val proposed = mutableEdits.toMutableMap()
        for (coord in coords.distinct()) {
            if (coord in mutableEdits) continue
            val baseline = baselineValue(coord) ?: continue
            previous[coord] = null
            proposed[coord] = baseline
        }
        if (previous.isEmpty() || !accept(proposed)) return emptySet()
        mutableEdits.clear()
        mutableEdits.putAll(proposed)
        undo.addLast(previous)
        redo.clear()
        return select(previous.keys)
    }

    /** Removing the override also removes *; the image will be sampled using current settings. */
    fun restoreImage(coords: Collection<Coord>): Set<Coord> {
        val previous = linkedMapOf<Coord, PreRecoveryCellValue?>()
        for (coord in coords.distinct()) {
            val before = currentValue(coord) ?: continue
            previous[coord] = before
        }
        if (previous.isEmpty()) return emptySet()
        previous.keys.forEach {
            mutableEdits.remove(it)
            mutablePrioritySearch.remove(it)
        }
        undo.addLast(previous)
        redo.clear()
        return select(previous.keys)
    }

    fun setPrioritySearch(coords: Collection<Coord>): PriorityEditResult {
        val distinct = coords.distinct()
        if (distinct.isEmpty()) return PriorityEditResult.UNCHANGED
        val additions = distinct.count { it !in mutablePrioritySearch }
        if (mutablePrioritySearch.size + additions > priorityLimit) return PriorityEditResult.LIMIT_EXCEEDED

        val previous = linkedMapOf<Coord, PreRecoveryCellValue?>()
        distinct.forEach { coord ->
            val before = currentValue(coord)
            if (before?.prioritySearch == true) return@forEach
            previous[coord] = before
            mutableEdits[coord] = ModuleValue.ERASED
            mutablePrioritySearch += coord
        }
        if (previous.isEmpty()) return PriorityEditResult.UNCHANGED
        undo.addLast(previous)
        redo.clear()
        select(previous.keys)
        return PriorityEditResult.CHANGED
    }

    fun undo(): Set<Coord> {
        val previous = undo.lastOrNull() ?: return emptySet()
        if (!accept(restoredEdits(previous))) return emptySet()
        undo.removeLast()
        redo.addLast(previous.keys.associateWith(::currentValue))
        restore(previous)
        return select(previous.keys)
    }

    fun redo(): Set<Coord> {
        val next = redo.lastOrNull() ?: return emptySet()
        if (!accept(restoredEdits(next))) return emptySet()
        redo.removeLast()
        undo.addLast(next.keys.associateWith(::currentValue))
        restore(next)
        return select(next.keys)
    }

    private fun restoredEdits(values: Map<Coord, PreRecoveryCellValue?>): Map<Coord, ModuleValue> =
        mutableEdits.toMutableMap().apply {
            values.forEach { (coord, value) -> if (value == null) remove(coord) else put(coord, value.value) }
        }

    private fun accept(proposed: Map<Coord, ModuleValue>): Boolean {
        // Allow clearing values even when a loaded legacy session has other conflicts to fix.
        val onlyClears = proposed.all { (coord, value) ->
            value == ModuleValue.ERASED || mutableEdits[coord] == value
        }
        return onlyClears || validateEdits(proposed)
    }

    private fun restore(values: Map<Coord, PreRecoveryCellValue?>) {
        values.forEach { (coord, restored) ->
            mutablePrioritySearch.remove(coord)
            if (restored == null) {
                mutableEdits.remove(coord)
            } else {
                mutableEdits[coord] = restored.value
                if (restored.prioritySearch) mutablePrioritySearch += coord
            }
        }
    }

    private fun currentValue(coord: Coord): PreRecoveryCellValue? =
        mutableEdits[coord]?.let { PreRecoveryCellValue(it, coord in mutablePrioritySearch) }
}
