package com.brightblu.parc.core.data.search

import java.util.Locale

/**
 * Pure list rules for the last searches shown in the sheet (T67). Immutable, non-null.
 *
 * The list is newest first, case-insensitively de-duplicated and capped at [Limit]. Storage lives in
 * [SearchPrefs]; this object holds only the rule, so it can be tested without DataStore.
 */
object RecentSearchRules {

    /** The card's "last 8". */
    const val Limit = 8

    /** Separator in the single stored string; a query from a one-line field never contains it. */
    internal const val Separator = "\n"

    /**
     * Returns the new list after recording [query]: trimmed, moved to the front, existing copies of
     * the same query (ignoring case) removed, never longer than [limit]. A blank query changes
     * nothing. A stored blank entry is dropped rather than shown as an empty row.
     */
    fun record(existing: List<String>, query: String, limit: Int = Limit): List<String> {
        val trimmed = query.trim()
        if (limit <= 0) return emptyList()
        val cleaned = existing.map(String::trim).filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return cleaned.take(limit)
        val key = trimmed.lowercase(Locale.ROOT)
        val withoutCopy = cleaned.filterNot { it.lowercase(Locale.ROOT) == key }
        return (listOf(trimmed) + withoutCopy).take(limit)
    }

    /** Encodes the ordered list into one DataStore string; newlines inside a query are flattened. */
    internal fun encode(values: List<String>): String =
        values.map { it.replace(Separator, " ") }.joinToString(Separator)

    /** Decodes [encode]'s output; blank rows and duplicates are dropped, order is preserved. */
    internal fun decode(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(Separator)
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .fold(mutableListOf<String>()) { acc, value ->
                if (acc.none { it.equals(value, ignoreCase = true) }) acc.add(value)
                acc
            }
            .take(Limit)
    }
}
