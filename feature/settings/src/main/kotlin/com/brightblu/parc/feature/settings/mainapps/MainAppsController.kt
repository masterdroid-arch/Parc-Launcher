package com.brightblu.parc.feature.settings.mainapps

import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.firstrun.AndroidInstalledApps
import com.brightblu.parc.core.data.firstrun.AndroidMainAppSuggestions
import com.brightblu.parc.core.data.firstrun.InstalledAppsSource
import com.brightblu.parc.core.data.firstrun.InstalledApp
import com.brightblu.parc.model.AppId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persistence the picker needs (T62). A JVM test supplies memory; [ParcPrefs] adapts DataStore. */
interface MainAppsStore {
    /** The current selection in slot order, empty when the user never picked. */
    suspend fun selected(): List<AppId>

    /** Replaces the selection. The layout applies it once through the revision counter. */
    suspend fun save(ids: List<AppId>)
}

/** Adapts the real DataStore-backed prefs to the picker's [MainAppsStore] surface. */
fun ParcPrefs.asMainAppsStore(): MainAppsStore = object : MainAppsStore {
    override suspend fun selected(): List<AppId> = mainApps.first()

    override suspend fun save(ids: List<AppId>) = setMainApps(ids)
}

/**
 * Drives the picker (T62): reads the installed apps off the main thread, files them alphabetically,
 * and applies pure [MainAppsPicker] rules to the published [state].
 *
 * Threading: [state] is a [StateFlow]. The initial read is launched in [scope] on [io]; every user
 * action updates the state synchronously and does not touch disk. [save] writes one list; a failed
 * write still calls its callback so onboarding can never trap the user, and the layout keeps the
 * previous four apps.
 */
class MainAppsController(
    private val source: InstalledAppsSource,
    private val suggestions: (List<InstalledApp>) -> List<AppId>,
    private val store: MainAppsStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val useSuggestions: Boolean = true,
) {
    private val stateFlow = MutableStateFlow(MainAppsState())
    val state: StateFlow<MainAppsState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            val loaded = try {
                withContext(io) { source.read() }
            } catch (_: Exception) {
                emptyList()
            }
            val choices = loaded.map { MainAppChoice(it.id, it.label, it.packageName) }
            val stored = try {
                store.selected()
            } catch (_: Exception) {
                emptyList()
            }
            val suggested = try {
                suggestions(loaded)
            } catch (_: Exception) {
                emptyList()
            }
            stateFlow.value = MainAppsPicker.initial(choices, stored, if (useSuggestions) suggested else emptyList())
        }
    }

    fun setQuery(query: String) = stateFlow.update { MainAppsPicker.withQuery(it, query) }

    fun toggle(id: AppId) = stateFlow.update { MainAppsPicker.toggle(it, id) }

    fun move(from: Int, to: Int) = stateFlow.update { MainAppsPicker.move(it, from, to) }

    /**
     * Saves the current selection (or the suggestions when empty) and then calls [onSaved] on the
     * caller's dispatcher. A write or read failure is swallowed: the flow continues, no app name is
     * logged, and the previous selection stays.
     */
    fun save(onSaved: () -> Unit) {
        val ids = MainAppsPicker.confirmedOrDefault(stateFlow.value)
        scope.launch {
            try {
                store.save(ids)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Keep the flow moving; the layout simply keeps what it had.
            } finally {
                onSaved()
            }
        }
    }

    /** Settings auto-save preserves an intentionally empty selection; onboarding retains defaults. */
    suspend fun saveSelection(ids: List<AppId>) { store.save(ids) }
    fun restoreSelection(ids: List<AppId>) { stateFlow.update { it.copy(selected = ids) } }

    companion object {
        /** Builds the production controller for an app context and composition scope. */
        fun create(context: android.content.Context, scope: CoroutineScope, useSuggestions: Boolean = true): MainAppsController {
            val installed = AndroidInstalledApps.create(context)
            val suggestions = AndroidMainAppSuggestions(context)
            return MainAppsController(
                source = installed,
                suggestions = suggestions::suggest,
                store = ParcPrefs.create(context).asMainAppsStore(),
                scope = scope,
                useSuggestions = useSuggestions,
            )
        }
    }
}
