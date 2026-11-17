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
    /** T248: this row is one of the most-used suggestions shown while the field is empty. */
    val isMostUsed: Boolean = false,
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
    /** T194: the active mode id ([SearchModeIds]); search always opens in Apps. */
    val mode: String = SearchModeIds.Apps,
)

/**
 * State of the search sheet and the ABC list. The host owns the swipe-down detector and the keyboard
 * rules (DESIGN §4); this only exposes [open] / [close] and the derived rows.
 *
 * Threading: values are published on a [StateFlow]; collectors run in [scope] (the main dispatcher in
 * the app). Reads from any thread are safe.
 *
 * Rules: a blank query shows the most-used apps from [UsageRepository] first, then the recent apps;
 * a non-blank query shows [SearchEngine] hits with their labels resolved from [AppIndex]. Rows whose
 * app is no longer known are dropped rather than shown without a name.
 */
class SearchSheetState(
    private val engine: SearchEngine,
    private val appIndex: AppIndex,
    private val usage: UsageRepository,
    scope: CoroutineScope,
    recentsLimit: Int = DefaultRecentsLimit,
    /** T194: the modes of this build besides Apps, in chip order (see [SearchModes.standard]). */
    val modes: List<SearchMode> = SearchModes.standard(),
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
        // T194: a search starts in Apps; an open call on an already open sheet keeps its mode.
        _state.update { it.copy(isOpen = true, mode = if (it.isOpen) it.mode else SearchModeIds.Apps) }
        refresh()
    }

    /** Closing forgets the query so the next open starts clean (HOME intent / Back semantics). */
    fun close() {
        _state.value = SearchUiState()
    }

    /** In Apps mode a leading prefix ("tr ", "n ", "= ") switches mode and is removed from the text. */
    fun onQueryChange(text: String) {
        _state.update { current ->
            val match = if (current.mode == SearchModeIds.Apps) SearchModes.detectPrefix(text, modes) else null
            if (match != null) current.copy(mode = match.modeId, query = match.rest) else current.copy(query = text)
        }
        refresh()
    }

    /** A chip tap. The text stays; an id that is not registered is ignored. */
    fun setMode(id: String) {
        if (id != SearchModeIds.Apps && modes.none { it.id == id }) return
        _state.update { it.copy(mode = id) }
        refresh()
    }

    /** Backspace pressed on an empty field: a non-Apps mode returns to Apps. True when it did. */
    fun backspaceOnEmpty(): Boolean {
        val current = _state.value
        val next = SearchModes.afterBackspace(current.mode, current.query)
        if (next == current.mode) return false
        _state.update { it.copy(mode = next) }
        refresh()
        return true
    }

    fun modeOf(id: String): SearchMode? = modes.firstOrNull { it.id == id }

    fun openAbcList() {
        _state.update { it.copy(abcListOpen = true) }
    }

    fun closeAbcList() {
        _state.update { it.copy(abcListOpen = false) }
    }

    /** App that Enter launches, or null when there is nothing to launch. */
    fun topHit(): AppId? =
        _state.value.takeIf { it.mode == SearchModeIds.Apps }?.rows?.firstOrNull()?.appId

    /**
     * `RingState.highlight` input: null while search is closed or the query is blank (no dimming),
     * otherwise the matched ids. An empty set means "query with no matches", which dims everything.
     */
    fun highlight(): Set<AppId>? {
        val current = _state.value
        if (!current.isOpen || current.query.isBlank() || current.mode != SearchModeIds.Apps) return null
        return current.rows.mapTo(LinkedHashSet()) { it.appId }
    }

    fun cancel() {
        jobs.forEach { it.cancel() }
    }

    private fun refresh() {
        _state.update { current ->
            if (current.mode != SearchModeIds.Apps) {
                // T194: other modes never touch the app engine, the calculator or the web row.
                return@update current.copy(rows = emptyList(), showingRecents = false, calculation = null, webQuery = null)
            }
            val query = current.query.trim()
            val rows = if (query.isEmpty()) suggestionRows() else hitRows(query)
            current.copy(
                rows = rows,
                showingRecents = current.isOpen && query.isEmpty(),
                calculation = if (query.isEmpty()) null else Calculator.evaluate(query),
                webQuery = query.takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * T248: the empty field offers most-used apps first (nearest the field), then recent apps. An app
     * is shown once: a most-used app already in the recent list is left to that list. Unknown apps
     * (hidden, locked or uninstalled) are dropped by [row].
     */
    private fun suggestionRows(): List<SearchRow> {
        val recentIds = recents.distinct()
        val shownRecent = recentIds.take(RecentAppsLimit)
            .mapNotNull { id -> row(id, emptyList(), isRecent = true) }
        // Only the recents actually shown hide a most-used app; a recent beyond the limit, or one that
        // cannot be drawn, must not take a most-used app off the list.
        val seen = shownRecent.mapTo(HashSet()) { it.appId }
        val used = ArrayList<SearchRow>(MostUsedLimit)
        for (id in usage.mostUsed(MostUsedLimit)) {
            if (!seen.add(id)) continue
            val suggestion = row(id, emptyList(), isMostUsed = true) ?: continue
            used += suggestion
            if (used.size == MostUsedLimit) break
        }
        return used + shownRecent
    }

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

    private fun row(id: AppId, ranges: List<MatchRange>, isRecent: Boolean = false,
                    isMostUsed: Boolean = false): SearchRow? {
        val label = labels[id] ?: return null
        return SearchRow(id, label, ranges, isRecent, isMostUsed)
    }

    companion object {
        /** Recents length for both the sheet and the engine boost. */
        const val DefaultRecentsLimit = 12

        /** T248: the most-used suggestions under an empty field. */
        const val MostUsedLimit = 4

        /** T248: the recent apps under an empty field; the newest are shown. */
        const val RecentAppsLimit = 6

        /** Shortcut sub-rows under the top hit; the long-press menu uses the same cap. */
        const val MaxShortcutRows = 5
    }
}
