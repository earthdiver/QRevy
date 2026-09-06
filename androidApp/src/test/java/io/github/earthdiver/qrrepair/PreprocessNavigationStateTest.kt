package io.github.earthdiver.qrrepair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessNavigationStateTest {
    @Test
    fun startsOnBasicPageWithBasicActionsVisible() {
        val state = PreprocessNavigationState()

        assertEquals(PreprocessPage.BASIC, state.currentPage)
        assertTrue(state.showsBasicActions)
    }

    @Test
    fun childPagesReturnToTheirParent() {
        val cellEditor = PreprocessNavigationState()
        cellEditor.navigateTo(PreprocessPage.CELL_EDITOR)
        assertFalse(cellEditor.showsBasicActions)
        assertEquals(PreprocessPage.BASIC, cellEditor.navigateBack())
        assertTrue(cellEditor.showsBasicActions)

        val data = PreprocessNavigationState()
        data.navigateTo(PreprocessPage.DETAILS)
        data.navigateTo(PreprocessPage.DATA)
        assertFalse(data.showsBasicActions)
        assertEquals(PreprocessPage.DETAILS, data.navigateBack())
        assertEquals(PreprocessPage.BASIC, data.navigateBack())
        assertTrue(data.showsBasicActions)
    }

    @Test
    fun backOnBasicPageIsStable() {
        val state = PreprocessNavigationState()

        assertEquals(PreprocessPage.BASIC, state.navigateBack())
        assertEquals(PreprocessPage.BASIC, state.currentPage)
    }
}
