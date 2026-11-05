package io.github.earthdiver.qrrepair.core

/** RFC 3629 byte ranges. Special second bytes exclude overlongs, surrogates and values above U+10FFFF. */
enum class Utf8State(val requiredBytes: Int) {
    BOUNDARY(0), ONE(1), TWO(2), THREE(3), AFTER_E0(2), AFTER_ED(2), AFTER_F0(3), AFTER_F4(3);

    private data class Rule(val first: Int, val last: Int, val next: Utf8State)

    companion object {
        private val rules = mapOf(
            BOUNDARY to listOf(Rule(0, 0x7f, BOUNDARY), Rule(0xc2, 0xdf, ONE),
                Rule(0xe0, 0xe0, AFTER_E0), Rule(0xe1, 0xec, TWO), Rule(0xed, 0xed, AFTER_ED),
                Rule(0xee, 0xef, TWO), Rule(0xf0, 0xf0, AFTER_F0), Rule(0xf1, 0xf3, THREE),
                Rule(0xf4, 0xf4, AFTER_F4)),
            ONE to listOf(Rule(0x80, 0xbf, BOUNDARY)),
            TWO to listOf(Rule(0x80, 0xbf, ONE)),
            THREE to listOf(Rule(0x80, 0xbf, TWO)),
            AFTER_E0 to listOf(Rule(0xa0, 0xbf, ONE)),
            AFTER_ED to listOf(Rule(0x80, 0x9f, ONE)),
            AFTER_F0 to listOf(Rule(0x90, 0xbf, TWO)),
            AFTER_F4 to listOf(Rule(0x80, 0x8f, TWO)),
        )
    }

    /** Visit only transitions intersecting the known byte range and fitting the segment. */
    fun transitions(low: Int, high: Int, followingBytes: Int, visit: (Int, Int, Utf8State) -> Unit) {
        for (rule in rules.getValue(this)) {
            if (rule.first <= high && low <= rule.last && rule.next.requiredBytes <= followingBytes)
                visit(rule.first, rule.last, rule.next)
        }
    }
}
