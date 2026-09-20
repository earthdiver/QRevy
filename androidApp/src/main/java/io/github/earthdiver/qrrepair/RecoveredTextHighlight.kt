package io.github.earthdiver.qrrepair

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan

/** Explicit foreground and background keep payload text readable in either appearance. */
internal fun highlightedRecoveredText(text: String, ranges: List<IntRange> = listOf(text.indices)): CharSequence =
    SpannableString(text).apply {
        for (range in ranges.filterNot { it.isEmpty() }) {
            setSpan(BackgroundColorSpan(0xFFD9F1FF.toInt()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(0xFF124C70.toInt()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
