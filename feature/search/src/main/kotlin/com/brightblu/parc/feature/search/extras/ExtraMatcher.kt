package com.brightblu.parc.feature.search.extras

import com.brightblu.parc.feature.search.TextNormalizer

/**
 * Pure matching shared by the three extra sources (T102): folded text, prefix beats word prefix beats
 * substring, and a small typo allowance on whole words. Stateless and thread-safe. App ranking keeps
 * its own [com.brightblu.parc.feature.search.SearchRanker]; this is only for settings, files and events.
 */
object ExtraMatcher {

    const val Exact = 1000.0
    const val Prefix = 800.0
    const val WordPrefix = 600.0
    const val Substring = 400.0
    const val Fuzzy = 250.0

    /** One-letter queries match too much of the phone; the sheet already shows apps for them. */
    const val MinQueryLength = 2

    /** Folded words of [text]: letters and digits only, in order. */
    fun words(text: String): List<String> {
        val folded = TextNormalizer.fold(text)
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (char in folded) {
            if (char.isLetterOrDigit()) current.append(char)
            else if (current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Folded query as a phrase with single spaces, or empty when it is too short to search. */
    fun phrase(query: String): String {
        val joined = words(query).joinToString(" ")
        return if (joined.length >= MinQueryLength) joined else ""
    }

    /**
     * Score of [query] (already a [phrase]) against one [field]; null when it does not match. A
     * multi-word query matches when every query word starts a word of the field (order free).
     * Hyphen and spacing differences are ignored: "wifi" finds "Wi-Fi" through [joinedSubstring].
     */
    fun score(query: String, field: String, allowFuzzy: Boolean = true): Double? {
        val fieldWords = words(field)
        if (fieldWords.isEmpty() || query.isEmpty()) return null
        val phrase = fieldWords.joinToString(" ")
        if (phrase == query) return Exact
        if (phrase.startsWith(query)) return Prefix
        val at = phrase.indexOf(query)
        if (at > 0) return if (phrase[at - 1] == ' ') WordPrefix else Substring
        val queryWords = query.split(' ')
        if (queryWords.size > 1 && queryWords.all { q -> fieldWords.any { it.startsWith(q) } }) return WordPrefix
        val squashed = phrase.replace(" ", "")
        if (query.length >= 3 && squashed.contains(query.replace(" ", ""))) return Substring
        if (!allowFuzzy) return null
        if (queryWords.all { q -> q.length >= FuzzyMinLength && fieldWords.any { isTypo(q, it) } }) return Fuzzy
        return null
    }

    /** True when [word] is [target] with at most one slip (two for long words), judged on a prefix of the same length too. */
    fun isTypo(word: String, target: String): Boolean {
        val allowed = if (word.length >= LongWord) 2 else 1
        if (distance(word, target, allowed) <= allowed) return true
        // Typing a word that is not finished yet: "bluetoth" is close to the start of "bluetooth".
        if (target.length > word.length) {
            val head = target.substring(0, minOf(target.length, word.length + 1))
            if (distance(word, head, allowed) <= allowed) return true
        }
        return false
    }

    /** Damerau-style edit distance, giving up (returning limit + 1) once it cannot stay within [limit]. */
    fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        val previous2 = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        var older = previous2
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = minOf(value, older[j - 2] + 1)
                }
                current[j] = value
                if (value < rowMin) rowMin = value
            }
            if (rowMin > limit) return limit + 1
            val recycled = older
            older = previous
            previous = current
            current = recycled
        }
        return previous[b.length]
    }

    private const val FuzzyMinLength = 4
    private const val LongWord = 8
}
