package com.brightblu.parc.feature.search

import com.brightblu.parc.feature.search.calculator.Calculator
import com.brightblu.parc.feature.search.calculator.Calculation
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.AppShortcut
import com.brightblu.parc.model.MatchRange
import com.brightblu.parc.model.SearchEngine
import com.brightblu.parc.model.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One visible row of the search sheet. Immutable, non-null. */
data class SearchRow(
    val appId: AppId,
    val label: String,
    val matchRanges: List<MatchRange> = emptyList(),
    val isRecent: Boolean = false,
    /** Static and dynamic shortcuts of the top hit, shown as indented sub-rows (T67). */
    val shortcuts: List<AppShortcut> = emptyList(),
)

/**
 * Immutable sheet state. Non-null; [rows] is an immutable snapshot. [calculation] is the calculator
 * answer for the current query (null when the query is not an expression) and [webQuery] is the
 * query the last "Search the web" row hands to the browser.
 */
data class SearchUiState(
    val isOpen: Boolean = false,
    val query: String = "",
    val rows: List<SearchRow> = emptyList(),
    val showingRecents: Boolean = false,
    val abcListOpen: Boolean = false,
    val calculation: Calculation? = null,
    val webQuery: String? = null,
)

/**
 * State of the search sheet and the ABC list. The host owns the swipe-down detector and the keyboard
 * rules (DESIGN §4); this only exposes [open] / [close] and the derived rows.
 *
 * Threading: values are published on a [StateFlow]; collectors run in [scope] (the main dispatcher in
 * the app). Reads from any thread are safe.
 *
 * Rules: a blank query shows recents from [UsageRepository] (until T13 provides real ranking); a
 * non-blank query shows [SearchEngine] hits with their labels resolved from [AppIndex]. Rows whose
 * app is no longer known are dropped rather than shown without a name.
 */
class SearchSheetState(
    private val engine: SearchEngine,
    private val appIndex: AppIndex,
    private val usage: UsageRepository,
    scope: CoroutineScope,
    recentsLimit: Int = DefaultRecentsLimit,
) {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    @Volatile
    private var labels: Map<AppId, String> = emptyMap()

    @Volatile
    private var recents: List<AppId> = emptyList()

    private val jobs: List<Job> = listOf(
        scope.launch {
            appIndex.apps.collect { entries ->
                labels = entries.associate { it.id to it.label }
                refresh()
            }
        },
        scope.launch {
            usage.recents(recentsLimit).collect { ids ->
                recents = ids.toList()
                refresh()
            }
        },
    )

    val isOpen: Boolean get() = _state.value.isOpen

    fun open() {
        _state.update { it.copy(isOpen = true) }
        refresh()
    }

    /** Closing forgets the query so the next open starts clean (HOME intent / Back semantics). */
    fun close() {
        _state.value = SearchUiState()
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        refresh()
    }

    fun openAbcList() {
        _state.update { it.copy(abcListOpen = true) }
    }

    fun closeAbcList() {
        _state.update { it.copy(abcListOpen = false) }
    }

    /** App that Enter launches, or null when there is nothing to launch. */
    fun topHit(): AppId? = _state.value.rows.firstOrNull()?.appId

    /**
     * `RingState.highlight` input: null while search is closed or the query is blank (no dimming),
     * otherwise the matched ids. An empty set means "query with no matches", which dims everything.
     */
    fun highlight(): Set<AppId>? {
        val current = _state.value
        if (!current.isOpen || current.query.isBlank()) return null
        return current.rows.mapTo(LinkedHashSet()) { it.appId }
    }

    fun cancel() {
        jobs.forEach { it.cancel() }
    }

    private fun refresh() {
        _state.update { current ->
            val query = current.query.trim()
            val rows = if (query.isEmpty()) recentsRows() else hitRows(query)
            current.copy(
                rows = rows,
                showingRecents = current.isOpen && query.isEmpty(),
                calculation = if (query.isEmpty()) null else Calculator.evaluate(query),
                webQuery = query.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun recentsRows(): List<SearchRow> =
        recents.distinct().mapNotNull { id -> row(id, emptyList(), isRecent = true) }

    private fun hitRows(query: String): List<SearchRow> {
        val seen = HashSet<AppId>()
        val rows = engine.query(query).mapNotNull { hit ->
            if (!seen.add(hit.appId)) null else row(hit.appId, hit.matchRanges, isRecent = false)
        }
        if (rows.isEmpty()) return rows
        // T67: shortcuts belong to the matched (top) app only, so the sheet stays a few rows tall.
        val top = rows.first()
        val shortcuts = appIndex.shortcuts(top.appId).take(MaxShortcutRows)
        if (shortcuts.isEmpty()) return rows
        return rows.toMutableList().apply { this[0] = top.copy(shortcuts = shortcuts) }
    }

    private fun row(id: AppId, ranges: List<MatchRange>, isRecent: Boolean): SearchRow? {
        val label = labels[id] ?: return null
        return SearchRow(id, label, ranges, isRecent)
    }

    companion object {
        /** Recents length for both the sheet and the engine boost. */
        const val DefaultRecentsLimit = 12

        /** Shortcut sub-rows under the top hit; the long-press menu uses the same cap. */
        const val MaxShortcutRows = 5
    }
}
