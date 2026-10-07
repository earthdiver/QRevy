package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.candidateContentText

/** Full payload text with the same candidate identities as the result selectors. */
internal class CandidateContentsDialog(
    private val activity: Activity,
    candidates: List<Pair<Int, RecoveryCandidate>>,
    private val selectedIndex: Int,
    private val selectionNotice: String? = null,
    private val selectCandidate: (Int) -> Unit,
) {
    private val entries = candidates.take(MAX_DISPLAYED_RECOVERY_CANDIDATES)

    fun show() {
        val rows = entries.mapIndexed { position, (index, candidate) ->
            val label = activity.getString(R.string.result_tab, position + 1) + localizedText(
                "  ―  データ・ECCの不一致: ${candidate.knownBitMismatchesAfterCorrection}bit",
                "  ―  Data/ECC mismatches: ${candidate.knownBitMismatchesAfterCorrection}bit",
            )
            Triple(index, label, candidate.candidateContentText())
        }
        val list = ListView(activity).apply {
            adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(position: Int) = rows[position]
                override fun getItemId(position: Int) = rows[position].first.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (convertView as? RadioButton ?: RadioButton(activity).apply {
                        isAllCaps = false
                        textSize = 14f
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        // The list owns selection, including taps directly on the payload text.
                        isClickable = false
                        isFocusable = false
                    }).apply {
                        val (index, label, content) = rows[position]
                        val rowText = "$label\n$content"
                        text = highlightedRecoveredText(rowText, listOf(label.length + 1 until rowText.length))
                        isChecked = index == selectedIndex
                    }
            }
        }
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = listOfNotNull(selectionNotice, localizedText(
                    "文字列をタップして候補を選択します。バイトデータの表示は文字コードによって変わります。",
                    "Tap the text to select a candidate. Text converted from bytes depends on the character encoding."
                )).joinToString("\n")
                setPadding(dp(12), dp(8), dp(12), dp(8))
            })
            addView(list, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(localizedText("候補の内容一覧", "Candidate contents"))
            .setView(content)
            .setNegativeButton(R.string.close, null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            selectCandidate(entries[position].first)
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(-1, (activity.resources.displayMetrics.heightPixels * .85f).toInt())
            list.setSelection(entries.indexOfFirst { it.first == selectedIndex }.coerceAtLeast(0))
        }
        dialog.show()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
