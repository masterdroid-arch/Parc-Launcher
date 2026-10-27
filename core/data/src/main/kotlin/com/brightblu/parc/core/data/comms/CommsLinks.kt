package com.brightblu.parc.core.data.comms

/**
 * Link extraction for message text (T55). Only absolute `http://`, `https://` and `www.` links are
 * spans; a bare domain is not guessed. Ranges are UTF-16 offsets into the original string, so a
 * renderer can underline exactly the matched text without re-finding it.
 *
 * Trailing sentence punctuation and unmatched closing brackets are trimmed, while a balanced pair
 * inside a URL (for example a Wikipedia article title) is kept. Pure and unit-tested.
 */
object CommsLinks {

    private val Link = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'`]+""")
    private const val TrailingPunctuation = ".,;:!?\"'"

    /** All links in [text], in order, without overlaps. Empty when there are none. */
    fun extract(text: String): List<CommsLink> {
        if (text.isEmpty()) return emptyList()
        val found = ArrayList<CommsLink>(2)
        for (match in Link.findAll(text)) {
            val start = match.range.first
            val end = trimEnd(text, start, match.range.last + 1)
            if (end > start) found += CommsLink(start, end)
        }
        return found
    }

    /**
     * Drops trailing punctuation and closing brackets that are not balanced within the match. The
     * URL itself is untouched, so the span always covers a substring of [text].
     */
    private fun trimEnd(text: String, start: Int, endExclusive: Int): Int {
        var end = endExclusive
        while (end > start) {
            val last = text[end - 1]
            when {
                last in TrailingPunctuation -> end--
                last == ')' && count(text, start, end, ')') > count(text, start, end, '(') -> end--
                last == ']' && count(text, start, end, ']') > count(text, start, end, '[') -> end--
                last == '}' && count(text, start, end, '}') > count(text, start, end, '{') -> end--
                else -> return end
            }
        }
        return end
    }

    private fun count(text: String, start: Int, endExclusive: Int, needle: Char): Int {
        var total = 0
        for (index in start until endExclusive) if (text[index] == needle) total++
        return total
    }
}
