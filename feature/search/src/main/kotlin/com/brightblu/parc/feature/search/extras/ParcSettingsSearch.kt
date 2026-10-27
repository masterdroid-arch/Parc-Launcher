package com.brightblu.parc.feature.search.extras

/**
 * OPUS8 (T133): one Parc settings page that search can open. [key] is the host's own id for the
 * page; [title] is the exact name used in Settings; [keywords] are its description and synonyms.
 * Entries are fixed page names only: never app labels, folder names or anything a locked folder
 * could reveal.
 */
data class ParcSettingEntry(val key: String, val title: String, val keywords: List<String> = emptyList())

/** One Parc settings result. */
data class ParcSettingHit(val key: String, val title: String, val score: Double)

/** Pure ranking for Parc's own settings pages; same matcher as the phone-settings rows. */
object ParcSettingsRanking {
    const val Header = "Parc settings"
    const val DefaultLimit = 3
    private const val KeywordWeight = 0.9

    fun rank(query: String, entries: List<ParcSettingEntry>, limit: Int = DefaultLimit): List<ParcSettingHit> {
        val phrase = ExtraMatcher.phrase(query)
        if (phrase.isEmpty() || limit <= 0) return emptyList()
        val scored = ArrayList<ParcSettingHit>()
        for (entry in entries) {
            var best = ExtraMatcher.score(phrase, entry.title)
            for (keyword in entry.keywords) {
                // Descriptions are long: no typo allowance there, or every page would match.
                val value = ExtraMatcher.score(phrase, keyword, allowFuzzy = false)?.times(KeywordWeight)
                if (value != null && (best == null || value > best)) best = value
            }
            if (best != null) scored += ParcSettingHit(entry.key, entry.title, best)
        }
        // Stable: ties keep the catalogue order.
        return scored.sortedByDescending { it.score }.distinctBy { it.key }.take(limit)
    }
}
