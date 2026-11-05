package io.github.earthdiver.qrrepair

import android.app.Activity
import android.content.res.Configuration
import android.text.Selection
import android.text.Spannable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import io.github.earthdiver.qrrepair.core.DiagnosticSection
import io.github.earthdiver.qrrepair.core.DiagnosticDetailGroup
import io.github.earthdiver.qrrepair.core.DiagnosticDetailId
import io.github.earthdiver.qrrepair.core.DiagnosticStage
import io.github.earthdiver.qrrepair.core.RecoveryDiagnosticReport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryDiagnosticViewTest {
    private fun report(candidate: Int) = RecoveryDiagnosticReport("Candidate $candidate", DiagnosticStage.entries.map {
        DiagnosticSection(it, "Summary: ${it.label()}", "Candidate $candidate: ${it.label()}\nDetailed evidence\nHEX: 00 01 02")
    }, "Candidate $candidate: verification\nSearch-wide status")

    private val calculationTitle get() = localizedText("このQRを得た計算方法", "Calculation methods yielding this QR")
    private val listDecodingTitle get() = localizedText("リスト復号の実行状況（探索全体）", "List decoding execution status (whole search)")

    private fun groupedReport(candidate: Int, records: Boolean = true, omittedOnly: Boolean = false,
        singleSegment: Boolean = false): RecoveryDiagnosticReport = report(candidate).let { base ->
        base.copy(sections = base.sections.map { section ->
            if (section.stage != DiagnosticStage.PROCESSING) section else DiagnosticSection(section.stage, section.summary,
                listOfNotNull(
                    DiagnosticDetailGroup(DiagnosticDetailId.METHODS, localizedText("この候補を得た方法", "Methods used to obtain this candidate"),
                        text = "Candidate $candidate: representative methods"),
                    if (records) DiagnosticDetailGroup(DiagnosticDetailId.CALCULATIONS, calculationTitle,
                        summary = "Method A [used for cell explanations]\nMethod B\nSome records were omitted.",
                        text = if (omittedOnly) "" else (1..8).joinToString("\n\n") {
                            "Candidate $candidate, block $it\nMethod: partial-bit constraints\nDegrees of freedom: 4\nComplete evidence: ABC-123 日本語"
                        } + "\nCandidate $candidate: alternative evidence", collapsible = true) else null,
                    if (singleSegment) DiagnosticDetailGroup(DiagnosticDetailId.SINGLE_SEGMENT,
                        localizedText("単一セグメントの終端後構造の強制の適用状況（探索全体）", "Single-segment terminator and padding forcing application status (whole search)"),
                        text = "Single-segment application evidence") else null,
                    DiagnosticDetailGroup(DiagnosticDetailId.SEARCH, listDecodingTitle,
                        summary = "New trials: 3\nThese totals also include trials for other candidates.",
                        text = "Reused results: 2", collapsible = true),
                ))
        })
    }

    @Test fun nestedRecordsKeepTheirStateAndUpdateTheirSelectableEvidenceOnCandidateChanges() {
        val view = RecoveryDiagnosticView(RuntimeEnvironment.getApplication())
        val first = groupedReport(1)
        view.showReport(first, resetExpanded = true)
        val sectionButton = diagnosticButton(view, DiagnosticStage.PROCESSING)
        sectionButton.performClick()
        assertTrue(diagnosticText(view).contains("Candidate 1: representative methods"))
        assertTrue(diagnosticText(view).contains("New trials: 3"))
        assertTrue(diagnosticText(view).contains("Some records were omitted"))
        assertFalse(diagnosticText(view).contains("Complete evidence"))
        assertFalse(diagnosticText(view).contains("alternative evidence"))
        val calculation = diagnosticCalculationButton(view)
        calculation.performClick()
        assertEquals(view.context.getString(R.string.diagnostic_expanded), ViewCompat.getStateDescription(calculation))
        val body = descendants(view).filterIsInstance<TextView>().single { it.text.startsWith("Candidate 1, block 1") }
        Selection.setSelection(body.text as Spannable, 0, 9)
        sectionButton.performClick()
        sectionButton.performClick()
        assertTrue(diagnosticText(view).contains("Complete evidence"))
        view.showReport(groupedReport(2), resetExpanded = false)
        assertSame(calculation, diagnosticCalculationButton(view))
        assertTrue(diagnosticText(view).contains("Candidate 2, block 1"))
        assertFalse(diagnosticText(view).contains("Candidate 1"))
        assertTrue(diagnosticText(view).contains("Candidate 2: alternative evidence"))
        assertFalse(body.hasSelection())
        assertTrue(body.isTextSelectable)
        view.showReport(first, resetExpanded = true)
        sectionButton.performClick()
        assertFalse(diagnosticText(view).contains("Complete evidence"))
        assertEquals(view.context.getString(R.string.diagnostic_collapsed), ViewCompat.getStateDescription(calculation))
    }

    @Test fun disappearingRecordsReturnFocusToTheSectionAndDoNotRestoreStaleExpansion() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = RecoveryDiagnosticView(activity)
            activity.setContentView(view)
            view.showReport(groupedReport(1), resetExpanded = true)
            val sectionButton = diagnosticButton(view, DiagnosticStage.PROCESSING)
            sectionButton.performClick()
            val alternative = diagnosticCalculationButton(view)
            alternative.performClick()
            view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertTrue(alternative.requestFocusFromTouch())
            alternative.performClick()
            assertTrue(alternative.isFocused)
            alternative.performClick()
            assertTrue(alternative.isFocused)
            view.showReport(groupedReport(2, records = false), resetExpanded = false)
            assertTrue(sectionButton.isFocused)
            assertFalse(diagnosticText(view).contains("alternative evidence"))
            assertEquals(View.GONE, alternative.visibility)
            view.showReport(groupedReport(3), resetExpanded = false)
            assertSame(alternative, diagnosticCalculationButton(view))
            assertFalse(diagnosticText(view).contains("alternative evidence"))
            alternative.performClick()
            assertTrue(diagnosticText(view).contains("Candidate 3: alternative evidence"))
            val headings = descendants(view).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE &&
                ViewCompat.isAccessibilityHeading(it) }.map { it.text.toString() }
            assertTrue(headings.contains(calculationTitle))
            assertTrue(alternative.requestFocusFromTouch())
            view.showReport(groupedReport(4, omittedOnly = true), resetExpanded = false)
            assertTrue(sectionButton.isFocused)
            assertEquals(View.GONE, alternative.visibility)
            assertTrue(diagnosticText(view).contains("Some records were omitted"))
            view.showReport(groupedReport(5), resetExpanded = false)
            assertFalse(diagnosticText(view).contains("alternative evidence"))
            view.clear()
            assertFalse(descendants(view).filterIsInstance<TextView>().any { it.text.contains("Candidate") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun calculationDisclosuresHaveIndependentStatesAndAccessibleNames() {
        val view = RecoveryDiagnosticView(RuntimeEnvironment.getApplication())
        view.showReport(groupedReport(1), resetExpanded = true)
        diagnosticButton(view, DiagnosticStage.PROCESSING).performClick()
        val candidate = diagnosticCalculationButton(view)
        val listDecoding = diagnosticCalculationButton(view, listDecodingTitle)
        val openLabel = view.context.getString(R.string.diagnostic_open_calculations)
        assertEquals(openLabel, candidate.text.toString())
        assertEquals(openLabel, listDecoding.text.toString())
        assertNotEquals(candidate.contentDescription, listDecoding.contentDescription)
        assertEquals(1, diagnosticText(view).split(listDecodingTitle).size - 1)
        assertTrue(diagnosticText(view).contains("New trials: 3"))
        assertFalse(diagnosticText(view).contains("Reused results"))
        candidate.performClick()
        assertTrue(diagnosticText(view).contains("Complete evidence"))
        assertFalse(diagnosticText(view).contains("Reused results"))
        listDecoding.performClick()
        assertTrue(diagnosticText(view).contains("Reused results: 2"))
        assertEquals(view.context.getString(R.string.diagnostic_close_record, listDecodingTitle), listDecoding.contentDescription)
        candidate.performClick()
        assertFalse(diagnosticText(view).contains("Complete evidence"))
        assertTrue(diagnosticText(view).contains("Reused results: 2"))
        view.showReport(groupedReport(2), resetExpanded = false)
        assertSame(listDecoding, diagnosticCalculationButton(view, listDecodingTitle))
        assertTrue(diagnosticText(view).contains("Reused results: 2"))
        view.showReport(groupedReport(2), resetExpanded = true)
        diagnosticButton(view, DiagnosticStage.PROCESSING).performClick()
        assertFalse(diagnosticText(view).contains("Reused results"))
        assertTrue(diagnosticText(view).contains("New trials: 3"))
    }

    @Test fun openingDetailsPreservesFocusAndExposesTheirStateToAccessibility() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = RecoveryDiagnosticView(activity)
            activity.setContentView(view)
            val report = report(1)
            view.showReport(report, resetExpanded = true)
            view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val button = diagnosticButton(view, DiagnosticStage.INPUT)
            assertEquals(activity.getString(R.string.diagnostic_collapsed), ViewCompat.getStateDescription(button))
            assertFalse(diagnosticText(view).contains(report.sections[1].details))
            assertTrue(button.requestFocusFromTouch())
            button.performClick()
            assertTrue(button.isFocused)
            assertEquals(activity.getString(R.string.diagnostic_expanded), ViewCompat.getStateDescription(button))
            assertEquals(activity.getString(R.string.diagnostic_close_section, DiagnosticStage.INPUT.label()), button.text.toString())
            assertTrue(diagnosticText(view).contains(report.sections[1].details))
            assertFalse(diagnosticText(view).contains(report.sections[2].details))
            val headings = descendants(view).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && ViewCompat.isAccessibilityHeading(it) }
            assertEquals(listOf(report.scope) + DiagnosticStage.entries.map { it.title() }, headings.map { it.text.toString() })
            button.performClick()
            assertTrue(button.isFocused)
            assertFalse(diagnosticText(view).contains(report.sections[1].details))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun candidateChangesKeepOpenSectionsAndReplaceAllOfTheirEvidence() {
        val view = RecoveryDiagnosticView(RuntimeEnvironment.getApplication())
        val first = report(1)
        view.showReport(first, resetExpanded = true)
        val button = diagnosticButton(view, DiagnosticStage.PROCESSING)
        button.performClick()
        val second = report(2)
        view.showReport(second, resetExpanded = false)
        assertSame(button, diagnosticButton(view, DiagnosticStage.PROCESSING))
        assertTrue(diagnosticText(view).contains(second.sections[2].details))
        assertFalse(diagnosticText(view).contains("Candidate 1"))
        assertFalse(diagnosticText(view).contains(second.sections[1].details))
        view.showReport(first, resetExpanded = true)
        assertFalse(diagnosticText(view).contains(first.sections[2].details))
        view.clear()
        assertFalse(diagnosticText(view).contains("Candidate"))
        assertTrue(descendants(view).filterIsInstance<Button>().all { it.visibility == View.GONE })
    }

    @Test fun textAndSectionButtonsWrapWithoutClippingAtLargeFontSizes() {
        val app = RuntimeEnvironment.getApplication()
        val previous = AppLanguageManager.current
        try {
            for (style in AppStyle.entries) for (language in AppLanguage.entries) for (scale in listOf(1f, 2f)) {
                AppStylePreferences.save(app, style)
                AppLanguageManager.applyTo(app, language)
                val configuration = Configuration(app.resources.configuration).apply { fontScale = scale }
                val context = ContextThemeWrapper(app.createConfigurationContext(configuration), style.theme)
                val view = RecoveryDiagnosticView(context)
                val report = groupedReport(1, singleSegment = true)
                view.showReport(report, resetExpanded = true)
                DiagnosticStage.entries.forEach { diagnosticButton(view, it).performClick() }
                diagnosticCalculationButton(view).performClick()
                diagnosticCalculationButton(view, listDecodingTitle).performClick()
                view.measure(View.MeasureSpec.makeMeasureSpec(288, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                for (text in descendants(view).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }) {
                    val layout = requireNotNull(text.layout)
                    val width = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
                    for (line in 0 until layout.lineCount) {
                        assertEquals(0, layout.getEllipsisCount(line))
                        assertTrue("$style/$language/$scale: ${text.text}; line=$line, width=$width, drawn=${layout.getLineMax(line)}",
                            layout.getLineMax(line) <= width + 1)
                    }
                    assertTrue(layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    if (text is Button) {
                        assertTrue(text.height >= 48)
                        assertTrue(text.isClickable)
                    } else assertTrue(text.isTextSelectable)
                }
            }
        } finally { AppLanguageManager.applyTo(app, previous) }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
