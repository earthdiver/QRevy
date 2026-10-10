package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class GeometryRecoveryCacheTest {
    @Test fun onlyIdenticalRecoveryEvidenceReusesASuccessOrFailure() {
        val cache = GeometryRecoveryCache()
        val original = QrMatrix(21).apply { set(2, 3, ModuleValue.ZERO, .5f, ModuleSource.IMAGE) }
        var computations = 0
        fun result(matrix: QrMatrix, text: String = "expected") = cache.getOrPut(matrix, text) {
            computations++
            false
        }
        assertFalse(result(original))
        assertFalse(result(original.copy()))
        assertEquals(1, computations)
        val variants = listOf(
            original.copy().apply { set(2, 3, ModuleValue.ONE, .5f, ModuleSource.IMAGE) },
            original.copy().apply { set(2, 3, ModuleValue.ZERO, Float.fromBits(.5f.toRawBits() + 1), ModuleSource.IMAGE) },
            original.copy().apply { set(2, 3, ModuleValue.ZERO, .5f, ModuleSource.USER) },
            original.copy().apply { setPrioritySearch(3, 3, true) },
            original.copy().apply { set(3, 2, ModuleValue.ZERO, .5f, ModuleSource.IMAGE) },
            QrMatrix(25).apply { set(2, 3, ModuleValue.ZERO, .5f, ModuleSource.IMAGE) },
        )
        variants.forEach { assertFalse(result(it)) }
        assertFalse(result(original, "different payload"))
        assertEquals(variants.size + 2, computations)
        val succeeded = QrMatrix(29)
        assertTrue(cache.getOrPut(succeeded, "expected") { true })
        assertTrue(cache.getOrPut(succeeded.copy(), "expected") { error("Repeated successful recovery") })
    }

    @Test fun snapshotsSurviveMatrixEditsAndRecentResultsStayWithinTheCapacity() {
        val cache = GeometryRecoveryCache(capacity = 2)
        val matrix = QrMatrix(21)
        assertTrue(cache.getOrPut(matrix, "first") { true })
        matrix.set(0, 0, ModuleValue.ONE, 1f, ModuleSource.USER)
        assertFalse(cache.getOrPut(matrix, "first") { false })
        matrix.set(0, 0, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        assertTrue(cache.getOrPut(matrix, "first") { error("Original snapshot was mutated") })
        assertTrue(cache.getOrPut(matrix, "second") { true })
        assertTrue(cache.getOrPut(matrix, "first") { error("Recent result was evicted") })
        matrix.set(0, 0, ModuleValue.ONE, 1f, ModuleSource.USER)
        var recomputed = false
        assertFalse(cache.getOrPut(matrix, "first") { recomputed = true; false })
        assertTrue(recomputed)
    }
}
