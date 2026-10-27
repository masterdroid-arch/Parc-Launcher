package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.MatchRange
import com.brightblu.parc.model.SearchHit

/** Match quality, best first. The gaps are wider than [SearchConfig.maxBoost] on purpose. */
enum class MatchKind(val score: Double) {
    Exact(1000.0),
    Prefix(800.0),
    WordPrefix(600.0),
    Substring(400.0),
    Subsequence(200.0),
}

/** Searched fields with their weights; the same match kind on a label outranks the other fields. */
enum class SearchField(val weight: Double) {
    Label(1.0),
    Package(0.6),
    Keyword(0.5),
}

/**
 * Ranking knobs, immutable. Values are on-device tuning starting points, never call-site literals.
 * [maxBoost] must stay below the smallest gap between two match kinds so a usage boost can reorder
 * boost can reorder equal matches but never promote a weaker kind.
 */
data class SearchConfig(
    val maxBoost: Double = 100.0,
    val boostStep: Double = 12.0,
) {
    init {
        require(maxBoost >= 0.0 && boostStep >= 0.0)
        require(maxBoost < MatchKind.Substring.score - MatchKind.Subsequence.score) {
            "maxBoost must not be able to flip a match kind"
        }
    }
}

/** Usage boost keyed by app, highest first. Immutable snapshot, thread-safe. */
object SearchBoosts {

    val None: Map<AppId, Double> = emptyMap()

    /** Recents (most recent first) become a decaying boost; entries past the floor are dropped. */
    fun fromRecents(recents: List<AppId>, config: SearchConfig = SearchConfig()): Map<AppId, Double> {
        if (recents.isEmpty()) return emptyMap()
        val boosts = HashMap<AppId, Double>(recents.size)
        for ((position, id) in recents.withIndex()) {
            val value = config.maxBoost - position * config.boostStep
            if (value <= 0.0) break
            if (id !in boosts) boosts[id] = value
        }
        return boosts
    }
}

/** One field that matched, before weighting. */
private class FieldMatch(val kind: MatchKind, val matched: IntArray)

/** The best field of a document, with the field it was found in. */
private class ScoredMatch(val field: SearchField, val match: FieldMatch) {
    val score: Double = match.kind.score * field.weight
}

private class HitCandidate(val hit: SearchHit, val label: String)

/**
 * Pure ranking over folded documents (PROJECT.md §6.3): prefix beats word-prefix beats subsequence,
 * boosted by usage. Deterministic: equal scores fall back to label then id.
 */
object SearchRanker {

    private val HitOrder: Comparator<HitCandidate> =
        compareByDescending<HitCandidate> { it.hit.score }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            .thenBy { it.hit.appId.value }

    /**
     * Results in descending score order. An empty or blank query returns no hits; the sheet shows
     * recents for that case instead.
     */
    fun rank(
        query: String,
        docs: List<NormalizedDoc>,
        boosts: Map<AppId, Double> = SearchBoosts.None,
        config: SearchConfig = SearchConfig(),
    ): List<SearchHit> {
        if (docs.isEmpty()) return emptyList()
        val folded = TextNormalizer.fold(query)
        if (folded.isBlank()) return emptyList()
        val candidates = ArrayList<HitCandidate>(docs.size)
        for (doc in docs) {
            val best = bestMatch(doc, folded) ?: continue
            val score = best.score + (boosts[doc.doc.appId] ?: 0.0)
            val ranges =
                if (best.field == SearchField.Label) rangesFor(doc.label, best.match.matched) else emptyList()
            candidates += HitCandidate(SearchHit(doc.doc.appId, score, ranges), doc.doc.label)
        }
        candidates.sortWith(HitOrder)
        return candidates.map { it.hit }
    }

    /** Label first, then package, then every keyword; the first field wins a tie. */
    private fun bestMatch(doc: NormalizedDoc, query: String): ScoredMatch? {
        var best = match(SearchField.Label, doc.label, query)
        best = pick(best, match(SearchField.Package, doc.packageName, query))
        for (keyword in doc.keywords) best = pick(best, match(SearchField.Keyword, keyword, query))
        return best
    }

    /**
     * OPUS3: scattered letters (a subsequence) count only in the app's name. In a package name they
     * matched almost everything: every package starts with "com.", so "cal" found Citymapper, Clock
     * and Planner through "com...a...l" and filled the list with noise.
     */
    private fun match(field: SearchField, value: NormalizedText, query: String): ScoredMatch? =
        matchField(value, query)?.takeIf { usable(field, it.kind) }?.let { ScoredMatch(field, it) }

    /**
     * OPUS3: a package name counts only where one of its words starts ("insta" in
     * com.instagram.android). Its first word is "com", "org" or similar on nearly every app, so a
     * prefix, a mid-word piece or scattered letters there matched almost every app for any query
     * starting with "c" or "o".
     */
    private fun usable(field: SearchField, kind: MatchKind): Boolean = when (field) {
        SearchField.Label -> true
        SearchField.Package -> kind == MatchKind.WordPrefix || kind == MatchKind.Exact
        SearchField.Keyword -> kind != MatchKind.Subsequence
    }

    private fun pick(current: ScoredMatch?, candidate: ScoredMatch?): ScoredMatch? = when {
        candidate == null -> current
        current == null -> candidate
        current.score >= candidate.score -> current
        else -> candidate
    }

    private fun matchField(field: NormalizedText, query: String): FieldMatch? {
        val text = field.text
        if (text.isEmpty()) return null
        if (text == query) return FieldMatch(MatchKind.Exact, IntArray(query.length) { it })
        val index = text.indexOf(query)
        if (index == 0) return FieldMatch(MatchKind.Prefix, IntArray(query.length) { it })
        if (index > 0) {
            val kind = if (isWordStart(text, index)) MatchKind.WordPrefix else MatchKind.Substring
            return FieldMatch(kind, IntArray(query.length) { index + it })
        }
        val subsequence = subsequenceMatch(text, query) ?: return null
        return FieldMatch(MatchKind.Subsequence, subsequence)
    }

    private fun isWordStart(text: String, index: Int): Boolean =
        index == 0 || !text[index - 1].isLetterOrDigit()

    private fun subsequenceMatch(text: String, query: String): IntArray? {
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

    /** Coalesces folded match indices into source ranges, merging characters that share a source. */
    private fun rangesFor(field: NormalizedText, matched: IntArray): List<MatchRange> {
        if (matched.isEmpty()) return emptyList()
        val ranges = ArrayList<MatchRange>(matched.size)
        var runStart = matched[0]
        var runEnd = matched[0]
        for (i in 1 until matched.size) {
            if (matched[i] == runEnd + 1) runEnd = matched[i]
            else {
                ranges += field.sourceRange(runStart, runEnd + 1)
                runStart = matched[i]
                runEnd = matched[i]
            }
        }
        ranges += field.sourceRange(runStart, runEnd + 1)
        return ranges
    }
}
