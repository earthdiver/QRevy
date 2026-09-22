package io.github.earthdiver.qrrepair

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Hosts preprocess pages and owns their bottom navigation without owning page content. */
internal class PreprocessPageHost(
    context: Context,
    private val pages: Map<PreprocessPage, View>,
    buttonFactory: (String, () -> Unit) -> Button,
    basicActions: List<BasicAction>,
    private val onPageShown: (PreprocessPage) -> Unit,
    private val onDataRequested: (() -> Unit)? = null,
) {
    internal data class BasicAction(
        val label: String,
        val weight: Float = 1f,
        val home: Boolean = false,
        val action: () -> Unit,
    )

    private val modernStyle = AppStylePreferences.isModern(context)
    private val navigationState = PreprocessNavigationState()
    private val basicNavigation = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        gravity = Gravity.CENTER_VERTICAL
    }
    private val detailNavigation = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        gravity = Gravity.CENTER_VERTICAL
    }
    private val modernDataButton = if (modernStyle) buttonFactory(context.getString(R.string.data)) {
        if (onDataRequested != null) onDataRequested.invoke() else navigateTo(PreprocessPage.DATA)
    } else null
    private val modernPageButton = if (modernStyle) buttonFactory(context.getString(R.string.cell_edit_tap)) {
        navigateTo(when (navigationState.currentPage) {
            PreprocessPage.CELL_EDITOR, PreprocessPage.DETAILS -> PreprocessPage.BASIC
            else -> PreprocessPage.CELL_EDITOR
        })
    }.apply { PrecisionStyle.button(this, primary = true) } else null
    val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        require(pages.keys.containsAll(PreprocessPage.entries)) { "Every preprocess page must be registered" }

        val pageContainer = FrameLayout(context).apply {
            pages.values.forEach { page ->
                addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
        view.addView(pageContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        basicActions.forEachIndexed { index, item ->
            basicNavigation.addView(
                buttonFactory(item.label, item.action).apply { PrecisionStyle.button(this, primary = index == basicActions.lastIndex, home = item.home) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, item.weight),
            )
        }
        modernDataButton?.let { basicNavigation.addView(it, minOf(1, basicActions.size),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)) }
        modernPageButton?.let { basicNavigation.addView(it, minOf(1, basicNavigation.childCount),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.3f)) }
        detailNavigation.addView(
            buttonFactory(context.getString(R.string.back)) { navigateBack() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        detailNavigation.addView(
            buttonFactory(context.getString(R.string.data)) { navigateTo(PreprocessPage.DATA) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        view.addView(basicNavigation)
        view.addView(detailNavigation)
        navigateTo(PreprocessPage.BASIC)
    }

    fun navigateTo(page: PreprocessPage) {
        navigationState.navigateTo(page)
        pages.forEach { (candidate, view) ->
            view.visibility = if (candidate == page) View.VISIBLE else View.GONE
        }
        basicNavigation.visibility = if (modernStyle || navigationState.showsBasicActions) View.VISIBLE else View.GONE
        detailNavigation.visibility = if (modernStyle || navigationState.showsBasicActions) View.GONE else View.VISIBLE
        modernPageButton?.setText(when (page) {
            PreprocessPage.CELL_EDITOR, PreprocessPage.DETAILS -> R.string.repair_settings_navigation
            else -> R.string.cell_edit_tap
        })
        onPageShown(page)
    }

    fun navigateBack() {
        navigateTo(navigationState.navigateBack())
    }

    internal fun currentPage(): PreprocessPage = navigationState.currentPage
}
