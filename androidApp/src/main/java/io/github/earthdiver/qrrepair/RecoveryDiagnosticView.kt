package io.github.earthdiver.qrrepair

import android.content.Context
import android.graphics.Typeface
import android.text.Selection
import android.text.Spannable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import io.github.earthdiver.qrrepair.core.DiagnosticDetailGroup
import io.github.earthdiver.qrrepair.core.DiagnosticDetailId
import io.github.earthdiver.qrrepair.core.DiagnosticSection
import io.github.earthdiver.qrrepair.core.DiagnosticStage
import io.github.earthdiver.qrrepair.core.RecoveryDiagnosticReport

/** Keeps section controls stable when details open or the selected candidate changes. */
internal class RecoveryDiagnosticView(context: Context) : LinearLayout(context) {
    private val scope = textView(16f, heading = true)
    private val overview = textView()
    private val sections = DiagnosticStage.entries.associateWith(::SectionView)

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        if (AppStylePreferences.isModern(context)) PrecisionStyle.panel(this)
        else setBackgroundColor(0xFFF4F4F4.toInt())
        addView(scope)
        addView(overview)
        sections.values.forEach { addView(it) }
    }

    fun showReport(report: RecoveryDiagnosticReport, resetExpanded: Boolean) {
        scope.text = report.scope
        overview.text = report.overview
        overview.visibility = if (report.overview.isBlank()) GONE else VISIBLE
        report.sections.forEach { sections.getValue(it.stage).bind(it, resetExpanded) }
    }

    fun clear() {
        scope.text = ""
        overview.text = ""
        sections.forEach { (stage, view) -> view.bind(DiagnosticSection(stage, ""), resetExpanded = true) }
    }

    private inner class SectionView(private val stage: DiagnosticStage) : LinearLayout(context) {
        private val heading = textView(15f, heading = true)
        private val summary = textView()
        private val details = LinearLayout(context).apply { orientation = VERTICAL }
        private val groups = mutableMapOf<DiagnosticDetailId, GroupView>()
        private val toggle = disclosureButton().apply {
            setOnClickListener {
                expanded = !expanded
                updateExpansion()
            }
        }
        private var expanded = false

        init {
            orientation = VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(heading)
            addView(summary)
            addView(toggle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(details)
        }

        fun bind(section: DiagnosticSection, resetExpanded: Boolean) {
            heading.text = stage.title()
            summary.replaceText(section.summary)
            val present = section.groups.map { it.id }.toSet()
            groups.filterKeys { it !in present }.values.forEach { it.bind(null, resetExpanded = true) }
            section.groups.forEach { group ->
                val view = groups.getOrPut(group.id) {
                    GroupView().also { details.addView(it, groups.keys.count { id -> id.ordinal < group.id.ordinal }) }
                }
                view.bind(group, resetExpanded)
            }
            if (resetExpanded || section.groups.isEmpty()) expanded = false
            toggle.visibility = if (section.groups.isEmpty()) GONE else VISIBLE
            updateExpansion()
        }

        private fun updateExpansion() {
            toggle.text = context.getString(
                if (expanded) R.string.diagnostic_close_section else R.string.diagnostic_open_section, stage.label())
            ViewCompat.setStateDescription(toggle,
                context.getString(if (expanded) R.string.diagnostic_expanded else R.string.diagnostic_collapsed))
            if (!expanded) details.moveFocusTo(toggle)
            details.visibility = if (expanded) VISIBLE else GONE
        }

        private inner class GroupView : LinearLayout(context) {
            private val heading = textView(14f, heading = true)
            private val summary = textView()
            private val body = textView()
            private val disclosure = disclosureButton().apply {
                setOnClickListener {
                    open = !open
                    updateGroupExpansion()
                }
            }
            private var group: DiagnosticDetailGroup? = null
            private var open = false

            init {
                orientation = VERTICAL
                addView(heading)
                addView(summary)
                addView(disclosure, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(body)
            }

            fun bind(value: DiagnosticDetailGroup?, resetExpanded: Boolean) {
                if (value == null) moveFocusTo(toggle)
                group = value
                val hasDisclosure = value?.collapsible == true && value.text.isNotBlank()
                if (resetExpanded || !hasDisclosure) open = false
                heading.replaceText(value?.title.orEmpty())
                heading.visibility = if (value?.title.isNullOrBlank()) GONE else VISIBLE
                summary.replaceText(value?.summary.orEmpty())
                summary.visibility = if (value?.summary.isNullOrBlank()) GONE else VISIBLE
                body.replaceText(value?.text.orEmpty())
                if (!hasDisclosure) disclosure.moveFocusTo(toggle)
                disclosure.visibility = if (hasDisclosure) VISIBLE else GONE
                setPadding(0, if (value?.title.isNullOrBlank()) 0 else dp(12), 0, 0)
                visibility = if (value == null) GONE else VISIBLE
                updateGroupExpansion()
            }

            private fun updateGroupExpansion() {
                val value = group
                val calculationDetails = value?.id == DiagnosticDetailId.CALCULATIONS || value?.id == DiagnosticDetailId.SEARCH
                val recordLabel = context.getString(
                    if (open) R.string.diagnostic_close_record else R.string.diagnostic_open_record, value?.title.orEmpty())
                disclosure.text = if (calculationDetails) context.getString(
                    if (open) R.string.diagnostic_close_calculations else R.string.diagnostic_open_calculations)
                else recordLabel
                disclosure.contentDescription = if (calculationDetails) recordLabel else null
                ViewCompat.setStateDescription(disclosure,
                    context.getString(if (open) R.string.diagnostic_expanded else R.string.diagnostic_collapsed))
                val show = value != null && value.text.isNotBlank() && (!value.collapsible || open)
                if (!show) body.moveFocusTo(if (disclosure.isVisible) disclosure else toggle)
                body.visibility = if (show) VISIBLE else GONE
            }
        }
    }

    private fun disclosureButton() = Button(context).apply {
        textSize = 14f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setSingleLine(false)
        PrecisionStyle.button(this)
    }

    private fun TextView.replaceText(value: String) {
        (text as? Spannable)?.let(Selection::removeSelection)
        text = value
    }

    /** Return keyboard focus when content disappears; screen readers manage accessibility focus. */
    private fun View.moveFocusTo(target: View) {
        if (hasFocus()) target.requestFocusFromTouch()
    }

    private fun textView(size: Float = 14f, heading: Boolean = false) = TextView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        textSize = size
        setPadding(0, dp(4), 0, dp(4))
        setTextIsSelectable(true)
        if (AppStylePreferences.isModern(context)) setTextColor(PrecisionStyle.TEXT)
        if (heading) {
            setTypeface(typeface, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(this, true)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
