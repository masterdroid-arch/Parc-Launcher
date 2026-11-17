package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.LayoutRepository
import com.brightblu.parc.model.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Keeps a [LabelSearchEngine] fed from the app index, the layout and the usage log (T09 wiring).
 *
 * Main-thread confined for construction; the two collectors run in [scope]. Closing cancels them,
 * which is what a test (or a later process-lifetime owner) needs.
 */
class SearchIndexer(
    private val appIndex: AppIndex,
    private val layout: LayoutRepository,
    private val usage: UsageRepository,
    private val engine: LabelSearchEngine,
    scope: CoroutineScope,
    recentsLimit: Int = DefaultRecentsLimit,
    private val config: SearchConfig = SearchConfig(),
    /** T259: stable app id to the alias the user set; an update re-indexes without a restart. */
    aliases: Flow<Map<String, String>> = flowOf(emptyMap()),
) {
    private val jobs: List<Job> = listOf(
        scope.launch {
            combine(appIndex.apps, layout.layout, aliases) { apps, current, names ->
                SearchIndexBuilder.build(apps, current, names)
            }.collect { engine.update(it) }
        },
        scope.launch {
            usage.recents(recentsLimit).collect { engine.updateBoosts(SearchBoosts.fromRecents(it, config)) }
        },
    )

    fun cancel() {
        jobs.forEach { it.cancel() }
    }

    companion object {
        /** Recents used for the boost; matches the sheet's recents list length. */
        const val DefaultRecentsLimit = SearchSheetState.DefaultRecentsLimit
    }
}
