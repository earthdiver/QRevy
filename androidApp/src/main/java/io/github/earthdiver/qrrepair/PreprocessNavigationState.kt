package io.github.earthdiver.qrrepair

internal enum class PreprocessPage {
    BASIC,
    DETAILS,
    CELL_EDITOR,
    DATA,
}

/**
 * Owns preprocess-dialog navigation independently of Android View visibility.
 *
 * Keeping this transition rule explicit prevents a hidden or stale View from changing where the
 * Back button leads after the dialog contents are rebuilt.
 */
internal class PreprocessNavigationState(
    initialPage: PreprocessPage = PreprocessPage.BASIC,
) {
    var currentPage: PreprocessPage = initialPage
        private set

    val showsBasicActions: Boolean get() = currentPage == PreprocessPage.BASIC

    fun navigateTo(page: PreprocessPage): PreprocessPage {
        currentPage = page
        return currentPage
    }

    fun navigateBack(): PreprocessPage = navigateTo(
        when (currentPage) {
            PreprocessPage.CELL_EDITOR -> PreprocessPage.BASIC
            PreprocessPage.DATA -> PreprocessPage.DETAILS
            PreprocessPage.DETAILS, PreprocessPage.BASIC -> PreprocessPage.BASIC
        },
    )
}
