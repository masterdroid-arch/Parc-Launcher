package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.SearchEngine
import com.brightblu.parc.model.SearchHit

/**
 * In-memory [SearchEngine] over folded label, package and keyword fields.
 *
 * Thread-safe: the index and the boost map are immutable snapshots replaced by a single reference
 * write, so [query] can run on the main thread while [SearchIndexer] refreshes from a collector.
 * Query cost is one fold of the query plus a scan of every document (about 150 apps).
 */
class LabelSearchEngine(
    private val config: SearchConfig = SearchConfig(),
) : SearchEngine {

    @Volatile
    private var index: List<NormalizedDoc> = emptyList()

    @Volatile
    private var boosts: Map<AppId, Double> = SearchBoosts.None

    /** Number of indexed documents; diagnostics and tests. */
    val size: Int get() = index.size

    /** Replaces the whole index. Caller keeps ownership of [docs]; it is copied into folded form. */
    fun update(docs: List<SearchDoc>) {
        index = SearchIndex.of(docs)
    }

    /** Replaces the usage boost snapshot; see [SearchBoosts.fromRecents]. */
    fun updateBoosts(value: Map<AppId, Double>) {
        boosts = if (value.isEmpty()) SearchBoosts.None else HashMap(value)
    }

    override fun query(text: String): List<SearchHit> = SearchRanker.rank(text, index, boosts, config)
}
