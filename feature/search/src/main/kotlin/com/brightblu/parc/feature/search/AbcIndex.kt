package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId

/** One letter bucket of the ABC list. Immutable, non-null. */
data class AbcSection(val key: Char, val apps: List<AppEntry>)

private class KeyedApp(val key: Char, val entry: AppEntry)

/**
 * Alphabetical grouping for the "All apps" list (DESIGN §5, S8). Pure and immutable.
 *
 * Keys come from the folded label, so diacritics group with their plain letter ("Über" under U and
 * "Що" under Щ). Latin A–Z sorts first, other scripts (Cyrillic, Greek) after it, and [Hash] (digits,
 * punctuation, symbols) last. Only keys that occur are listed, which keeps the left-edge index short.
 */
class AbcIndex private constructor(val sections: List<AbcSection>) {

    val keys: List<Char> get() = sections.map { it.key }

    /** List position of the first item of [sectionIndex]; an index tap scrolls there. */
    fun flatOffsetOf(sectionIndex: Int): Int {
        require(sectionIndex in sections.indices) { "Unknown section: $sectionIndex" }
        var offset = 0
        for (i in 0 until sectionIndex) offset += 1 + sections[i].apps.size
        return offset
    }

    /** Section of [key], or -1 when no app has that initial. */
    fun sectionIndexOf(key: Char): Int = sections.indexOfFirst { it.key == key }

    companion object {
        /** Bucket for labels whose first letter cannot be found. */
        const val Hash: Char = '#'

        val Empty = AbcIndex(emptyList())

        /** Groups visible apps (hidden ones dropped) into ordered sections. */
        fun of(apps: List<AppEntry>, hidden: Set<AppId> = emptySet()): AbcIndex {
            val keyed = apps.filterNot { it.id in hidden }
                .map { KeyedApp(sectionKey(it.label), it) }
                .sortedWith(KeyedOrder)
            if (keyed.isEmpty()) return Empty
            val sections = ArrayList<AbcSection>()
            var current: Char? = null
            var bucket = ArrayList<AppEntry>()
            for (item in keyed) {
                if (item.key != current) {
                    val previous = current
                    if (previous != null) sections += AbcSection(previous, bucket)
                    current = item.key
                    bucket = ArrayList()
                }
                bucket += item.entry
            }
            sections += AbcSection(keyed.last().key, bucket)
            return AbcIndex(sections)
        }

        /** Section key of a label: its first letter, folded and upper-cased, else [Hash]. */
        fun sectionKey(label: String): Char {
            val first = TextNormalizer.fold(label).firstOrNull { it.isLetter() } ?: return Hash
            return first.uppercaseChar()
        }

        private fun category(key: Char): Int = when {
            key in 'A'..'Z' -> 0
            key == Hash -> 2
            else -> 1
        }

        private val KeyedOrder: Comparator<KeyedApp> =
            compareBy<KeyedApp> { category(it.key) }
                .thenBy { it.key }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.entry.label }
                .thenBy { it.entry.id.value }
    }
}
