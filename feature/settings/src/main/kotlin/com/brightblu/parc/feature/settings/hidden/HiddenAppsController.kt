package com.brightblu.parc.feature.settings.hidden

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.LayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Connects the hidden apps list to the layout (T17, F-17).
 *
 * Threading: [setHidden] and [setQuery] are safe from the main thread; the write is launched into
 * [scope]. [state] is a [StateFlow]. The layout stays the source of truth: after a write its new
 * snapshot rebuilds the rows through [HiddenAppsProjection], so the list can never show a flag the
 * repository did not store.
 */
class HiddenAppsController(
    private val repository: LayoutRepository,
    private val apps: StateFlow<List<AppEntry>>,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow(HiddenAppsUiState())
    val state: StateFlow<HiddenAppsUiState> = stateFlow.asStateFlow()

    init {
        scope.launch { repository.layout.collect { rebuild() } }
        scope.launch { apps.collect { rebuild() } }
    }

    /** Applies the filter text. UI only; nothing is persisted. */
    fun setQuery(text: String) {
        stateFlow.value = stateFlow.value.copy(query = text)
        rebuild()
    }

    /** Hides or unhides one app through the frozen contract. */
    fun setHidden(id: AppId, hidden: Boolean) {
        scope.launch { if (hidden) repository.hide(id) else repository.unhide(id) }
    }

    private fun rebuild() {
        val query = stateFlow.value.query
        val installed = apps.value
        val hidden = repository.layout.value.hidden
        stateFlow.value = stateFlow.value.copy(
            rows = HiddenAppsProjection.rows(installed, hidden, query),
            hiddenCount = installed.count { it.id in hidden },
        )
    }
}
