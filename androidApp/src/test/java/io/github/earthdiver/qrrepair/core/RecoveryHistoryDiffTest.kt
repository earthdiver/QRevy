package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryHistoryDiffTest {
    @Test fun countsValueAndPriorityChangesBetweenMatrices() {
        val before = QrMatrix(21)
        val after = before.copy().apply {
            set(3, 4, ModuleValue.ONE, 1f, ModuleSource.USER)
            setPrioritySearch(8, 9, true)
        }

        assertEquals(2, historyCellChangeCount(snapshot(before), snapshot(after)))
    }

    @Test fun countsPreRecoveryEditChangesWhenMatricesAreNotAvailable() {
        val a = Coord(2, 3)
        val b = Coord(4, 5)
        val before = PreprocessSettings(version = 1, preRecoveryEdits = mapOf(a to ModuleValue.ZERO))
        val after = PreprocessSettings(
            version = 1,
            preRecoveryEdits = mapOf(a to ModuleValue.ONE, b to ModuleValue.ERASED),
            preRecoveryPrioritySearch = setOf(b),
        )

        assertEquals(2, historyCellChangeCount(settingsSnapshot(before), settingsSnapshot(after)))
    }

    @Test fun incompatibleMatrixDimensionsHaveNoCellDiff() {
        assertNull(historyCellChangeCount(snapshot(QrMatrix(21)), snapshot(QrMatrix(25))))
    }

    private fun snapshot(matrix: QrMatrix) = RecoverySnapshot(null, matrix, matrix, -1)
    private fun settingsSnapshot(settings: PreprocessSettings) = RecoverySnapshot(null, null, null, -1, settings)
}
