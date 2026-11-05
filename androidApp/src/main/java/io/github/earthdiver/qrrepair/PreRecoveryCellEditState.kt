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

internal enum class CellEditOutcome { CHANGED, UNCHANGED, REJECTED }

internal enum class PriorityEditResult { CHANGED, UNCHANGED, LIMIT_EXCEEDED }

/** Owns pre-recovery cell edits and undo transactions independently of Android Views. */
internal class PreRecoveryCellEditState(
    initialEdits: Map<Coord, ModuleValue>,
    initialPrioritySearch: Set<Coord>,
    var priorityLimit: Int = 4,
    private val validateEdits: (Map<Coord, ModuleValue>) -> Boolean = { true },
    initialSoftEdits: Set<Coord> = emptySet(),
) {
    private val mutableSoftEdits = linkedSetOf<Coord>().apply {
        addAll(initialSoftEdits)
        addAll(initialEdits.filterValues { it == ModuleValue.ERASED }.keys)
    }
    private val mutableEdits = linkedMapOf<Coord, ModuleValue>().apply { putAll(initialEdits) }
    private val mutablePrioritySearch = linkedSetOf<Coord>().apply { addAll(initialPrioritySearch) }
    private data class Transaction(val values: Map<Coord, PreRecoveryCellValue?>, val selection: CellEditSelection)
    private val undo = ArrayDeque<Transaction>()
    private val redo = ArrayDeque<Transaction>()
    var lastEditOutcome = CellEditOutcome.UNCHANGED
        private set
    var lastChangedCells: Set<Coord> = emptySet()
        private set

    val edits: Map<Coord, ModuleValue> get() = mutableEdits
    val prioritySearch: Set<Coord> get() = mutablePrioritySearch
    val softEdits: Set<Coord> get() = mutableSoftEdits
    val fixedEdits: Map<Coord, ModuleValue> get() = mutableEdits - mutableSoftEdits
    fun sourceAt(coord: Coord): CellEditSource = when (coord) {
        in mutableSoftEdits -> CellEditSource.TENTATIVE
        in mutableEdits -> CellEditSource.FIXED
        else -> CellEditSource.IMAGE
    }
    var selection: CellEditSelection = CellEditSelection.EMPTY
        private set
    val selectedCells: Set<Coord> get() = selection.cells
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
        require(mutablePrioritySearch.size <= 14)
        mutablePrioritySearch.forEach { coord ->
            require(mutableEdits[coord] == ModuleValue.ERASED) { "Priority search requires an erased edit" }
        }
        // Persisted edits predate this dialog's in-memory history. Keep them undoable as one
        // transaction instead of presenting an impossible zero-actions/nonzero-cells state.
        if (mutableEdits.isNotEmpty() || mutablePrioritySearch.isNotEmpty()) {
            val targets = CellEditSelection.of(mutableEdits.keys + mutablePrioritySearch, false)
            undo.addLast(Transaction(targets.cells.associateWith { null }, targets))
        }
    }

    fun select(coords: Collection<Coord>, asRectangle: Boolean = false, focus: Coord? = coords.firstOrNull()): Set<Coord> {
        if (selectedCells != coords.toSet() || selection.isRectangle != asRectangle || selection.focus != focus) {
            selection = CellEditSelection.of(coords, asRectangle, focus)
        }
        return selectedCells
    }

    private fun selectTargets(coords: Collection<Coord>) {
        if (coords.isNotEmpty() && selectedCells != coords.toSet()) select(coords)
        lastEditOutcome = CellEditOutcome.UNCHANGED
        lastChangedCells = emptySet()
    }

    private fun rejected(): Set<Coord> {
        lastEditOutcome = CellEditOutcome.REJECTED
        return emptySet()
    }

    private fun record(previous: Map<Coord, PreRecoveryCellValue?>): Set<Coord> {
        undo.addLast(Transaction(previous, selection))
        redo.clear()
        lastEditOutcome = CellEditOutcome.CHANGED
        lastChangedCells = previous.keys
        return previous.keys
    }

    fun clearSelection() = select(emptySet())

    /** Independent draft/checkpoint; never retains a previous dialog's validation callback. */
    fun copyForEditing(
        validateEdits: (Map<Coord, ModuleValue>) -> Boolean = { true },
    ): PreRecoveryCellEditState = PreRecoveryCellEditState(
        mutableEdits, mutablePrioritySearch, priorityLimit, validateEdits, mutableSoftEdits,
    ).also { copy ->
        copy.undo.clear()
        copy.undo.addAll(undo)
        copy.redo.addAll(redo)
    }

    fun clearAll() {
        mutableSoftEdits.clear()
        mutableEdits.clear()
        mutablePrioritySearch.clear()
        undo.clear()
        redo.clear()
        selection = CellEditSelection.EMPTY
        lastChangedCells = emptySet()
        lastEditOutcome = CellEditOutcome.UNCHANGED
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
        transformCoordinates(::rotate)
    }

    /** Preserves values, provenance, selection and both history stacks on the same image cells. */
    fun transposeCoordinates() = transformCoordinates { Coord(it.y, it.x) }

    private fun transformCoordinates(transform: (Coord) -> Coord) {
        fun <T> Map<Coord, T>.transformed(): Map<Coord, T> = entries.associate { transform(it.key) to it.value }

        val soft = mutableSoftEdits.mapTo(linkedSetOf(), transform)
        mutableSoftEdits.clear()
        mutableSoftEdits.addAll(soft)
        val edits = mutableEdits.transformed()
        mutableEdits.clear()
        mutableEdits.putAll(edits)
        val priority = mutablePrioritySearch.mapTo(linkedSetOf(), transform)
        mutablePrioritySearch.clear()
        mutablePrioritySearch.addAll(priority)
        val transformedSelections = mutableMapOf<CellEditSelection, CellEditSelection>()
        fun CellEditSelection.transformedOnce() = transformedSelections.getOrPut(this) { transformed(transform) }
        selection = selection.transformedOnce()
        lastChangedCells = lastChangedCells.mapTo(linkedSetOf(), transform)

        val transformedUndo = undo.map { Transaction(it.values.transformed(), it.selection.transformedOnce()) }
        undo.clear()
        undo.addAll(transformedUndo)
        val transformedRedo = redo.map { Transaction(it.values.transformed(), it.selection.transformedOnce()) }
        redo.clear()
        redo.addAll(transformedRedo)
    }

    fun applyValues(
        coords: Collection<Coord>,
        value: ModuleValue,
        baselineValue: (Coord) -> ModuleValue?,
    ): Set<Coord> {
        val baselines = coords.distinct().mapNotNull { coord -> baselineValue(coord)?.let { coord to it } }.toMap()
        selectTargets(baselines.keys)
        val previous = linkedMapOf<Coord, PreRecoveryCellValue?>()
        val proposed = mutableEdits.toMutableMap()
        val soft = mutableSoftEdits.toMutableSet()
        for ((coord, baseline) in baselines) {
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
        if (previous.isEmpty()) return emptySet()
        if (!accept(proposed, soft)) return rejected()
        previous.keys.forEach(mutablePrioritySearch::remove)
        mutableSoftEdits.clear()
        mutableSoftEdits.addAll(soft)
        mutableEdits.clear()
        mutableEdits.putAll(proposed)
        return record(previous)
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
        val targets = coords.distinct().filter { currentValue(it) != null || baselineValue(it) != null }
        selectTargets(targets)
        val previous = linkedMapOf<Coord, PreRecoveryCellValue?>()
        val proposed = mutableEdits.toMutableMap()
        val soft = mutableSoftEdits.toMutableSet()
        for (coord in targets) {
            val before = currentValue(coord)
            val value = before?.value ?: baselineValue(coord) ?: continue
            // Reject a mixed batch atomically; unknowns have no fixed state.
            if (source == CellEditSource.FIXED && value == ModuleValue.ERASED) return rejected()
            val tentative = source == CellEditSource.TENTATIVE
            val after = PreRecoveryCellValue(value, before?.prioritySearch == true, tentative)
            if (before == after) continue
            previous[coord] = before
            proposed[coord] = value
            if (tentative) soft.add(coord) else soft.remove(coord)
        }
        if (previous.isEmpty()) return emptySet()
        if (!accept(proposed, soft)) return rejected()
        mutableEdits.clear()
        mutableEdits.putAll(proposed)
        mutableSoftEdits.clear()
        mutableSoftEdits.addAll(soft)
        return record(previous)
    }

    /** Removing the override also removes *; the image will be sampled using current settings. */
    fun restoreImage(coords: Collection<Coord>): Set<Coord> {
        selectTargets(coords)
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
        return record(previous)
    }

    fun setPrioritySearch(coords: Collection<Coord>): PriorityEditResult {
        val distinct = coords.distinct()
        selectTargets(distinct)
        if (distinct.isEmpty()) return PriorityEditResult.UNCHANGED
        val additions = distinct.count { it !in mutablePrioritySearch }
        if (mutablePrioritySearch.size + additions > priorityLimit) {
            rejected()
            return PriorityEditResult.LIMIT_EXCEEDED
        }

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
        record(previous)
        return PriorityEditResult.CHANGED
    }

    /** Geometry changes must not let undo/redo restore priority marks outside codewords. */
    fun revalidatePrioritySearch(isAllowed: (Coord) -> Boolean): Set<Coord> {
        val removed = mutablePrioritySearch.filterNot(isAllowed).toSet()
        mutablePrioritySearch.removeAll(removed)
        fun sanitize(history: ArrayDeque<Transaction>) {
            val revised = history.map { entry -> entry.copy(values = entry.values.mapValues { (coord, value) ->
                if (value?.prioritySearch == true && !isAllowed(coord)) value.copy(prioritySearch = false) else value
            }) }
            history.clear()
            history.addAll(revised)
        }
        sanitize(undo)
        sanitize(redo)
        return removed
    }

    fun undo(): Set<Coord> = restoreTransaction(undo, redo)

    fun redo(): Set<Coord> = restoreTransaction(redo, undo)

    private fun restoreTransaction(from: ArrayDeque<Transaction>, to: ArrayDeque<Transaction>): Set<Coord> {
        lastEditOutcome = CellEditOutcome.UNCHANGED
        lastChangedCells = emptySet()
        val transaction = from.lastOrNull() ?: return emptySet()
        val values = transaction.values
        if (!accept(restoredEdits(values), restoredSoftEdits(values))) return rejected()
        from.removeLast()
        to.addLast(Transaction(values.keys.associateWith(::currentValue), transaction.selection))
        restore(values)
        selection = transaction.selection
        lastEditOutcome = CellEditOutcome.CHANGED
        lastChangedCells = values.keys
        return values.keys
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
