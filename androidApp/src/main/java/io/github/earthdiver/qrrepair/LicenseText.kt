package io.github.earthdiver.qrrepair

import android.content.res.Resources
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/** Only the bundled document headings are markup; license bodies remain verbatim. */
internal fun licenseText(resources: Resources): CharSequence {
    fun raw(id: Int) = resources.openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText() }
    val result = SpannableStringBuilder()
    fun heading(text: String, level: Int = 1) {
        val start = result.length
        result.append(text).append("\n")
        result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        result.setSpan(RelativeSizeSpan(if (level == 1) 1.5f else 1.2f), start, result.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val privacy = raw(R.raw.privacy)
    heading(privacy.substringBefore('\n'))
    result.append(privacy.substringAfter('\n')).append("\n\n")
    val headingPattern = Regex("^(#{1,3}) (.+)$")
    for (line in raw(R.raw.third_party_notices).split('\n')) {
        val match = headingPattern.matchEntire(line)
        if (match != null) heading(match.groupValues[2], match.groupValues[1].length)
        else result.append(line).append('\n')
    }
    result.append("\n")
    heading("Apache License 2.0")
    result.append('\n').append(raw(R.raw.apache_2_0))
    return result
}
