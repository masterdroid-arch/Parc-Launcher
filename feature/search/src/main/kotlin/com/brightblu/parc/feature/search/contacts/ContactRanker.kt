package com.brightblu.parc.feature.search.contacts

import com.brightblu.parc.feature.search.NormalizedText
import com.brightblu.parc.feature.search.TextNormalizer
import com.brightblu.parc.model.MatchRange

/** One contact row the sheet can show. Immutable, non-null; [numbers] keeps the source order. */
data class ContactRecord(
    val id: Long,
    val name: String,
    val numbers: List<String> = emptyList(),
) {
    init {
        require(id >= 0) { "Contact id must be non-negative" }
    }
}

/** Ranked contact match. [matchRanges] point into [ContactRecord.name] (UTF-16). */
data class ContactHit(
    val contact: ContactRecord,
    val score: Double,
    val matchRanges: List<MatchRange> = emptyList(),
    val matchedNumber: String? = null,
)

/**
 * Reads contacts for the search sheet (T67). Implementations must return an empty list when
 * `READ_CONTACTS` is not granted and must never throw. [search] is blocking; call it off the main
 * thread. The pure [ContactRanker] does the matching, so tests need no Android provider.
 */
interface ContactSource {
    fun hasPermission(): Boolean
    fun search(query: String, limit: Int): List<ContactRecord>
}

/**
 * Pure contact ranking: name first, then a digit-normalised phone number. Immutable inputs and
 * outputs; deterministic ordering. Scores are deliberately spread wider than any name/number boost so
 * an exact name always beats a partial number match.
 */
object ContactRanker {

    /** The sheet shows a handful of contacts; more would push the web row out of thumb reach. */
    const val DefaultLimit = 4

    private const val NameExact = 1000.0
    private const val NamePrefix = 800.0
    private const val NameWordPrefix = 600.0
    private const val NameSubstring = 400.0
    private const val NameSubsequence = 200.0
    private const val NumberExact = 900.0
    private const val NumberSuffix = 700.0
    private const val NumberContains = 500.0

    /** Contacts that match [query] on a name or a number, best first, at most [limit]. */
    fun rank(
        query: String,
        contacts: List<ContactRecord>,
        limit: Int = DefaultLimit,
    ): List<ContactHit> {
        if (limit <= 0) return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val folded = TextNormalizer.normalize(trimmed)
        val digits = trimmed.filter(Char::isDigit)
        val phoneQuery = isPhoneQuery(trimmed) && digits.length >= 3

        val hits = ArrayList<ContactHit>(contacts.size)
        for (contact in contacts) {
            val nameMatch = if (folded.isEmpty) null else matchName(contact.name, folded)
            val numberMatch = if (!phoneQuery) null else bestNumber(contact.numbers, digits)
            val nameScore = nameMatch?.score ?: 0.0
            val numberScore = numberMatch?.score ?: 0.0
            if (nameScore <= 0.0 && numberScore <= 0.0) continue
            hits += ContactHit(
                contact = contact,
                score = maxOf(nameScore, numberScore),
                matchRanges = if (nameScore >= numberScore) nameMatch?.ranges.orEmpty() else emptyList(),
                matchedNumber = numberMatch?.number,
            )
        }
        hits.sortWith(
            compareByDescending<ContactHit> { it.score }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.contact.name }
                .thenBy { it.contact.id },
        )
        return hits.take(limit)
    }

    /** A query counts as a phone number when it contains a digit and only phone punctuation. */
    private fun isPhoneQuery(query: String): Boolean =
        query.any(Char::isDigit) && query.all { it.isDigit() || it in "+-() ." }

    private class NameMatch(val score: Double, val ranges: List<MatchRange>)

    private fun matchName(name: String, query: NormalizedText): NameMatch? {
        if (name.isBlank()) return null
        val folded = TextNormalizer.normalize(name)
        val text = folded.text
        if (text.isEmpty()) return null
        if (text == query.text) {
            return NameMatch(NameExact, listOf(folded.sourceRange(0, text.length)))
        }
        val index = text.indexOf(query.text)
        if (index == 0) {
            return NameMatch(NamePrefix, listOf(folded.sourceRange(0, query.length)))
        }
        if (index > 0) {
            val wordStart = !text[index - 1].isLetterOrDigit()
            val score = if (wordStart) NameWordPrefix else NameSubstring
            return NameMatch(score, listOf(folded.sourceRange(index, index + query.length)))
        }
        if (query.length < 2) return null
        val subsequence = subsequenceOffsets(text, query.text) ?: return null
        return NameMatch(NameSubsequence, coalesce(folded, subsequence))
    }

    private fun subsequenceOffsets(text: String, query: String): IntArray? {
        val matched = IntArray(query.length)
        var from = 0
        for (i in query.indices) {
            val found = text.indexOf(query[i], from)
            if (found < 0) return null
            matched[i] = found
            from = found + 1
        }
        return matched
    }

    private fun coalesce(folded: NormalizedText, offsets: IntArray): List<MatchRange> {
        if (offsets.isEmpty()) return emptyList()
        val ranges = ArrayList<MatchRange>(offsets.size)
        var start = offsets[0]
        var end = offsets[0]
        for (i in 1 until offsets.size) {
            if (offsets[i] == end + 1) end = offsets[i]
            else {
                ranges += folded.sourceRange(start, end + 1)
                start = offsets[i]
                end = offsets[i]
            }
        }
        ranges += folded.sourceRange(start, end + 1)
        return ranges
    }

    private class NumberMatch(val score: Double, val number: String)

    /** The best-scoring number of one contact, or null when none contains the query digits. */
    private fun bestNumber(numbers: List<String>, digits: String): NumberMatch? {
        var best: NumberMatch? = null
        for (number in numbers) {
            val stored = number.filter(Char::isDigit)
            if (stored.isEmpty()) continue
            val score = when {
                stored == digits -> NumberExact
                stored.endsWith(digits) -> NumberSuffix
                stored.contains(digits) -> NumberContains
                else -> 0.0
            }
            if (score > (best?.score ?: 0.0)) best = NumberMatch(score, number)
        }
        return best
    }
}
