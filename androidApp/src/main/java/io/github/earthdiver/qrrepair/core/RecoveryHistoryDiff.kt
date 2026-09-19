package io.github.earthdiver.qrrepair.core

/** Counts logical cells changed between adjacent history snapshots when they are comparable. */
fun historyCellChangeCount(before: RecoverySnapshot, after: RecoverySnapshot): Int? {
    val beforeMatrix = before.workingMatrix ?: before.sourceMatrix
    val afterMatrix = after.workingMatrix ?: after.sourceMatrix
    if (beforeMatrix != null || afterMatrix != null) {
        if (beforeMatrix == null || afterMatrix == null || beforeMatrix.dimension != afterMatrix.dimension) return null
        var changed = 0
        for (y in 0 until beforeMatrix.dimension) for (x in 0 until beforeMatrix.dimension) {
            val a = beforeMatrix[x, y]
            val b = afterMatrix[x, y]
            if (a.value != b.value || a.source != b.source || a.prioritySearch != b.prioritySearch) changed++
        }
        return changed
    }

    val beforeSettings = before.preprocessSettings ?: return null
    val afterSettings = after.preprocessSettings ?: return null
    val coordinates = beforeSettings.preRecoveryEdits.keys + afterSettings.preRecoveryEdits.keys +
        beforeSettings.preRecoveryPrioritySearch + afterSettings.preRecoveryPrioritySearch
    return coordinates.count { coord ->
        beforeSettings.preRecoveryEdits[coord] != afterSettings.preRecoveryEdits[coord] ||
            (coord in beforeSettings.effectiveSoftEdits) != (coord in afterSettings.effectiveSoftEdits) ||
            (coord in beforeSettings.preRecoveryPrioritySearch) != (coord in afterSettings.preRecoveryPrioritySearch)
    }
}
