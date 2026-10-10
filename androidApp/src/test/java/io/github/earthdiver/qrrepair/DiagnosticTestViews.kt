package io.github.earthdiver.qrrepair

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import io.github.earthdiver.qrrepair.core.DiagnosticStage

internal fun diagnosticView(activity: MainActivity): RecoveryDiagnosticView =
    MainActivity::class.java.getDeclaredField("diagnosticView").apply { isAccessible = true }
        .get(activity) as RecoveryDiagnosticView

/** Inspect expanded sections even when the result screen's outer disclosure is closed. */
internal fun diagnosticText(view: RecoveryDiagnosticView): String {
    fun text(child: View): List<String> = when {
        child.visibility == View.GONE -> emptyList()
        child is TextView -> listOf(child.text.toString())
        child is ViewGroup -> (0 until child.childCount).flatMap { text(child.getChildAt(it)) }
        else -> emptyList()
    }
    return (0 until view.childCount).flatMap { text(view.getChildAt(it)) }.joinToString("\n")
}

internal fun diagnosticText(activity: MainActivity): String = diagnosticText(diagnosticView(activity))

internal fun diagnosticButton(view: RecoveryDiagnosticView, stage: DiagnosticStage): Button {
    return diagnosticButtons(view).single { it.text in listOf(
        view.context.getString(R.string.diagnostic_open_section, stage.label()),
        view.context.getString(R.string.diagnostic_close_section, stage.label())) }
}

internal fun diagnosticRecordButton(view: RecoveryDiagnosticView, title: String): Button =
    diagnosticButtons(view).single { it.text in listOf(
        view.context.getString(R.string.diagnostic_open_record, title),
        view.context.getString(R.string.diagnostic_close_record, title)) }

private fun diagnosticButtons(child: View): List<Button> = when (child) {
    is Button -> listOf(child)
    is ViewGroup -> (0 until child.childCount).flatMap { diagnosticButtons(child.getChildAt(it)) }
    else -> emptyList()
}

internal fun expandDiagnosticSections(activity: MainActivity) {
    val view = diagnosticView(activity)
    DiagnosticStage.entries.forEach { stage ->
        val button = diagnosticButton(view, stage)
        if (button.visibility == View.VISIBLE && button.text == view.context.getString(R.string.diagnostic_open_section, stage.label()))
            button.performClick()
    }
    diagnosticButtons(view).filter { it.visibility == View.VISIBLE &&
        ViewCompat.getStateDescription(it) == view.context.getString(R.string.diagnostic_collapsed) }.forEach { it.performClick() }
}
