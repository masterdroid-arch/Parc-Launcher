package com.brightblu.parc.feature.settings.mainapps

import com.brightblu.parc.model.AppId
import java.util.Locale

/** One app the picker can place. Immutable; [label] is what the row shows. */
data class MainAppChoice(
    val id: AppId,
    val label: String,
    val packageName: String,
)

/**
 * Immutable picker state (T62): the installed apps, the search text, and at most four chosen ids in
 * slot order. [suggestions] is kept so Skip can fall back to them without a second platform read.
 */
data class MainAppsState(
    val options: List<MainAppChoice> = emptyList(),
    val selected: List<AppId> = emptyList(),
    val suggestions: List<AppId> = emptyList(),
    val query: String = "",
    val loaded: Boolean = false,
) {
    /** The four slots, null where nothing is chosen. Always length four. */
    val slots: List<MainAppChoice?>
        get() = (0 until MainAppsPicker.MaxMainApps).map { index ->
            val id = selected.getOrNull(index) ?: return@map null
            options.firstOrNull { it.id == id }
        }

    /** Installed apps matching the query, alphabetical by label then package. */
    val visible: List<MainAppChoice>
        get() {
            val text = query.trim()
            return options
                .asSequence()
                .filter { choice ->
                    text.isEmpty() ||
                        choice.label.contains(text, ignoreCase = true) ||
                        choice.packageName.contains(text, ignoreCase = true)
                }
                .sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.packageName }))
                .toList()
        }

    val canAdd: Boolean get() = selected.size < MainAppsPicker.MaxMainApps

    val isFull: Boolean get() = selected.size >= MainAppsPicker.MaxMainApps

    fun isSelected(id: AppId): Boolean = id in selected
}

/** Picker projection only: selected ids are preserved when their labels are hidden. */
fun MainAppsState.withoutLocked(packages: Set<String>): MainAppsState =
    copy(options = options.filterNot { it.packageName in packages })

/**
 * Pure picker rules (T62): selection, the four-app limit, reorder and skip defaults.
 *
 * Unknown ids are ignored, duplicates never enter a slot, and a fifth tap changes nothing. All
 * functions return a new immutable state, so the Compose surface stays dumb and the rules are
 * unit-tested on the JVM.
 */
object MainAppsPicker {

    const val MaxMainApps = 4

    /** Initial state: the stored selection when it still names installed apps, else the suggestions. */
    fun initial(
        options: List<MainAppChoice>,
        stored: List<AppId>,
        suggestions: List<AppId>,
    ): MainAppsState {
        val known = options.mapTo(mutableSetOf()) { it.id }
        val suggested = suggestions.filter { it in known }.distinct().take(MaxMainApps)
        val chosen = stored.filter { it in known }.distinct().take(MaxMainApps)
        return MainAppsState(
            options = options.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.packageName })),
            selected = chosen.ifEmpty { suggested },
            suggestions = suggested,
            loaded = true,
        )
    }

    fun withQuery(state: MainAppsState, query: String): MainAppsState = state.copy(query = query)

    /** Adds [id] when there is room, removes it when it is already chosen, ignores unknown ids. */
    fun toggle(state: MainAppsState, id: AppId): MainAppsState {
        if (state.options.none { it.id == id }) return state
        return if (id in state.selected) {
            state.copy(selected = state.selected - id)
        } else {
            if (!state.canAdd) state else state.copy(selected = state.selected + id)
        }
    }

    /** Moves the app at [from] to [to], clamping to the chosen list. A no-op for equal/empty moves. */
    fun move(state: MainAppsState, from: Int, to: Int): MainAppsState {
        if (from == to) return state
        if (from !in state.selected.indices) return state
        val target = to.coerceIn(0, state.selected.lastIndex)
        if (target == from) return state
        val next = state.selected.toMutableList()
        val moved = next.removeAt(from)
        next.add(target, moved)
        return state.copy(selected = next)
    }

    /** What Next saves: the selection as it stands, at most four. */
    fun confirmed(state: MainAppsState): List<AppId> = state.selected.distinct().take(MaxMainApps)

    /**
     * What Skip saves: the current selection, or the suggestions when nothing is chosen. The card's
     * "defaults are the suggestions" rule lives here.
     */
    fun confirmedOrDefault(state: MainAppsState): List<AppId> {
        val chosen = confirmed(state)
        return chosen.ifEmpty { state.suggestions.distinct().take(MaxMainApps) }
    }
}
