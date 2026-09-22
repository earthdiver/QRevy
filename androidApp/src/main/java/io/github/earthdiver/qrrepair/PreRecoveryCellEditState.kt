package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.localizedText

internal data class PreRecoveryCellValue(
    val value: ModuleValue,
    val prioritySearch: Boolean,
    val tentative: Boolean = false,
)

internal enum class CellEditSource { IMAGE, TENTATIVE, FIXED }

internal enum class PriorityEditResult { CHANGED, UNCHANGED, LIMIT_EXCEEDED }

/** Owns pre-recovery cell edits and undo transactions independently of Android Views. */
internal class PreRecoveryCellEditState(
    initialEdits: Map<Coord, ModuleValue>,
    initialPrioritySearch: Set<Coord>,
    private val priorityLimit: Int = 4,
    private val validateEdits: (Map<Coord, ModuleValue>) -> Boolean = { true },
    initialSoftEdits: Set<Coord> = emptySet(),
) {
    private val mutableSoftEdits = linkedSetOf<Coord>().apply {
        addAll(initialSoftEdits)
        addAll(initialEdits.filterValues { it == ModuleValue.ERASED }.keys)
    }
    private val mutableEdits = linkedMapOf<Coord, ModuleValue>().apply { putAll(initialEdits) }
    private val mutablePrioritySearch = linkedSetOf<Coord>().apply { addAll(initialPrioritySearch) }
    private val undo = ArrayDeque<Map<Coord, PreRecoveryCellValue?>>()
    private val redo = ArrayDeque<Map<Coord, PreRecoveryCellValue?>>()

    val edits: Map<Coord, ModuleValue> get() = mutableEdits
    val prioritySearch: Set<Coord> get() = mutablePrioritySearch
    val softEdits: Set<Coord> get() = mutableSoftEdits
    val fixedEdits: Map<Coord, ModuleValue> get() = mutableEdits - mutableSoftEdits
    fun sourceAt(coord: Coord): CellEditSource = when (coord) {
        in mutableSoftEdits -> CellEditSource.TENTATIVE
        in mutableEdits -> CellEditSource.FIXED
        else -> CellEditSource.IMAGE
    }
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
        require(mutableSoftEdits.all { it in mutableEdits })
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
        mutableEdits, mutablePrioritySearch, priorityLimit, validateEdits, mutableSoftEdits,
    ).also { copy ->
        copy.undo.clear()
        copy.undo.addAll(undo.map { it.toMap() })
        copy.redo.addAll(redo.map { it.toMap() })
    }

    fun clearAll() {
        mutableSoftEdits.clear()
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

        val soft = mutableSoftEdits.mapTo(linkedSetOf(), ::rotate)
        mutableSoftEdits.clear()
        mutableSoftEdits.addAll(soft)
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
        val soft = mutableSoftEdits.toMutableSet()
        for (coord in coords.distinct()) {
            val baseline = baselineValue(coord) ?: continue
            val before = currentValue(coord)
            // An unchanged image value is not an explicit confirmation. Once edited, values
            // remain manual until the source button (or Undo) removes the override.
            if (before == null && value == baseline) continue
            val tentative = value == ModuleValue.ERASED ||
                (before?.value != ModuleValue.ERASED && coord in mutableSoftEdits)
            val after = PreRecoveryCellValue(value, false, tentative)
            if (before == after) continue
            previous[coord] = before
            proposed[coord] = value
            if (tentative) soft.add(coord) else soft.remove(coord)
        }
        if (previous.isEmpty() || !accept(proposed, soft)) return emptySet()
        previous.keys.forEach(mutablePrioritySearch::remove)
        mutableSoftEdits.clear()
        mutableSoftEdits.addAll(soft)
        mutableEdits.clear()
        mutableEdits.putAll(proposed)
        undo.addLast(previous)
        redo.clear()
        return select(previous.keys)
    }

    /** Mixed selections return to the image together, rather than inverting each cell. */
    val selectionHasManualEdits: Boolean get() = selectedCells.any { it in mutableEdits }

    fun makeManual(coords: Collection<Coord>, baselineValue: (Coord) -> ModuleValue?): Set<Coord> =
        setSource(coords, CellEditSource.FIXED, baselineValue)

    /** Source changes retain the current value, including explicit unknowns. */
    fun setSource(
        coords: Collection<Coord>,
        source: CellEditSource,
        baselineValue: (Coord) -> ModuleValue?,
    ): Set<Coord> {
        if (source == CellEditSource.IMAGE) return restoreImage(coords)
        val previous = linkedMapOf<Coord, PreRecoveryCellValue?>()
        val proposed = mutableEdits.toMutableMap()
        val soft = mutableSoftEdits.toMutableSet()
        for (coord in coords.distinct()) {
            val before = currentValue(coord)
            val value = before?.value ?: baselineValue(coord) ?: continue
            // Reject a mixed batch atomically; unknowns have no fixed state.
            if (source == CellEditSource.FIXED && value == ModuleValue.ERASED) return emptySet()
            val tentative = source == CellEditSource.TENTATIVE
            val after = PreRecoveryCellValue(value, before?.prioritySearch == true, tentative)
            if (before == after) continue
            previous[coord] = before
            proposed[coord] = value
            if (tentative) soft.add(coord) else soft.remove(coord)
        }
        if (previous.isEmpty() || !accept(proposed, soft)) return emptySet()
        mutableEdits.clear()
        mutableEdits.putAll(proposed)
        mutableSoftEdits.clear()
        mutableSoftEdits.addAll(soft)
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
            mutableSoftEdits.remove(it)
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
            mutableSoftEdits.add(coord)
            mutableEdits[coord] = ModuleValue.ERASED
            mutablePrioritySearch += coord
        }
        if (previous.isEmpty()) return PriorityEditResult.UNCHANGED
        undo.addLast(previous)
        redo.clear()
        select(previous.keys)
        return PriorityEditResult.CHANGED
    }

    /** Geometry changes must not let undo/redo restore priority marks outside codewords. */
    fun revalidatePrioritySearch(isAllowed: (Coord) -> Boolean): Set<Coord> {
        val removed = mutablePrioritySearch.filterNot(isAllowed).toSet()
        mutablePrioritySearch.removeAll(removed)
        fun sanitize(history: ArrayDeque<Map<Coord, PreRecoveryCellValue?>>) {
            val revised = history.map { entry -> entry.mapValues { (coord, value) ->
                if (value?.prioritySearch == true && !isAllowed(coord)) value.copy(prioritySearch = false) else value
            } }
            history.clear()
            history.addAll(revised)
        }
        sanitize(undo)
        sanitize(redo)
        return removed
    }

    fun undo(): Set<Coord> {
        val previous = undo.lastOrNull() ?: return emptySet()
        if (!accept(restoredEdits(previous), restoredSoftEdits(previous))) return emptySet()
        undo.removeLast()
        redo.addLast(previous.keys.associateWith(::currentValue))
        restore(previous)
        return select(previous.keys)
    }

    fun redo(): Set<Coord> {
        val next = redo.lastOrNull() ?: return emptySet()
        if (!accept(restoredEdits(next), restoredSoftEdits(next))) return emptySet()
        redo.removeLast()
        undo.addLast(next.keys.associateWith(::currentValue))
        restore(next)
        return select(next.keys)
    }

    private fun restoredEdits(values: Map<Coord, PreRecoveryCellValue?>): Map<Coord, ModuleValue> =
        mutableEdits.toMutableMap().apply {
            values.forEach { (coord, value) -> if (value == null) remove(coord) else put(coord, value.value) }
        }

    private fun restoredSoftEdits(values: Map<Coord, PreRecoveryCellValue?>): Set<Coord> =
        mutableSoftEdits.toMutableSet().apply {
            values.forEach { (coord, value) -> if (value?.tentative == true) add(coord) else remove(coord) }
        }

    private fun accept(proposed: Map<Coord, ModuleValue>, soft: Set<Coord> = mutableSoftEdits): Boolean {
        // Allow clearing values even when a loaded legacy session has other conflicts to fix.
        val fixed = proposed - soft
        val currentFixed = fixedEdits
        val onlyClears = fixed.all { (coord, value) ->
            value == ModuleValue.ERASED || currentFixed[coord] == value
        }
        return onlyClears || validateEdits(fixed)
    }

    private fun restore(values: Map<Coord, PreRecoveryCellValue?>) {
        values.forEach { (coord, restored) ->
            mutablePrioritySearch.remove(coord)
            mutableSoftEdits.remove(coord)
            if (restored == null) {
                mutableEdits.remove(coord)
            } else {
                mutableEdits[coord] = restored.value
                if (restored.tentative) mutableSoftEdits.add(coord)
                if (restored.prioritySearch) mutablePrioritySearch += coord
            }
        }
    }

    private fun currentValue(coord: Coord): PreRecoveryCellValue? =
        mutableEdits[coord]?.let { PreRecoveryCellValue(it, coord in mutablePrioritySearch, coord in mutableSoftEdits) }

    /** Project edits onto a fresh sampled grid without changing the image evidence cache. */
    fun applyTo(matrix: QrMatrix, allowFixedCorrections: Boolean) {
        edits.forEach { (c, v) ->
            if (c.x in 0 until matrix.dimension && c.y in 0 until matrix.dimension) {
                // Explicit source overrides are independent of whether the image agrees.
                matrix.set(c.x, c.y, v, 1f, when {
                    c in softEdits -> ModuleSource.USER_SOFT
                    allowFixedCorrections -> ModuleSource.USER_UNLOCKED
                    else -> ModuleSource.USER
                })
            }
            if (c in prioritySearch) matrix.setPrioritySearch(c.x, c.y, true)
        }
    }
}
