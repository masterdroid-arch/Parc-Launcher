package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.core.data.HapticsLevel
import com.brightblu.parc.core.data.ParcSettings
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Connects the skin picker and the haptics/clock rows to the persisted settings (T16).
 *
 * Threading: [state] is a [StateFlow]; the persisted snapshot is collected in [scope] (main in the app)
 * and writes are launched into the same scope, so a write never blocks the frame. Reads from any thread
 * are safe. The store is the source of truth: an optimistic update is corrected by the next collected
 * snapshot, so a failed write cannot leave the UI claiming a value that was not saved.
 *
 * [skins] is the validated catalog (default first). A selected id that is not in the catalog resolves
 * to the first skin, mirroring [com.brightblu.parc.core.data.skins.SkinCatalog.byId], so the picker
 * always shows a real selection.
 */
class SettingsController(
    private val store: SettingsStore,
    skins: List<Skin>,
    private val scope: CoroutineScope,
) {
    private val catalog: List<Skin> = skins.ifEmpty { listOf(Skin.Midnight) }

    private val stateFlow = MutableStateFlow(
        SettingsUiState(skins = catalog, selectedSkinId = catalog.first().id),
    )
    val state: StateFlow<SettingsUiState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            store.settings.collect { snapshot -> stateFlow.value = stateFlow.value.applied(snapshot) }
        }
    }

    /** Selects a skin. An id that is not in the catalog selects the default instead (never an empty pick). */
    fun selectSkin(id: String) {
        val effective = resolve(id)
        stateFlow.update { it.copy(selectedSkinId = effective) }
        scope.launch { store.setSkinId(effective) }
    }

    fun selectHaptics(level: HapticsLevel) {
        stateFlow.update { it.copy(haptics = level) }
        scope.launch { store.setHaptics(level) }
    }

    fun setClockVisible(visible: Boolean) {
        stateFlow.update { it.copy(clockVisible = visible) }
        scope.launch { store.setClockVisible(visible) }
    }

    private fun resolve(id: String): String =
        catalog.firstOrNull { it.id == id }?.id ?: catalog.first().id

    private fun SettingsUiState.applied(snapshot: ParcSettings): SettingsUiState = copy(
        selectedSkinId = resolve(snapshot.skinId),
        haptics = snapshot.haptics,
        clockVisible = snapshot.clockVisible,
    )
}
