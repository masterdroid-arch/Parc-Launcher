package com.brightblu.parc.core.data.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Immutable search settings snapshot (T67). Non-null; [recentSearches] is newest first, at most
 * [RecentSearchRules.Limit] entries.
 */
data class SearchPrefsSnapshot(
    val recentSearches: List<String> = emptyList(),
    val recentsEnabled: Boolean = SearchPrefs.DefaultRecentsEnabled,
    val webEngine: WebSearchEngine = WebSearchEngine.Google,
    /** T102: the three optional sources. Files and events also need their runtime permission. */
    val phoneSettingsEnabled: Boolean = SearchPrefs.DefaultSourceEnabled,
    val filesEnabled: Boolean = SearchPrefs.DefaultSourceEnabled,
    val eventsEnabled: Boolean = SearchPrefs.DefaultSourceEnabled,
    /** T102: the one-time permission row in the sheet has been used; it is not shown again. */
    val extrasPromptDone: Boolean = false,
)

/**
 * DataStore-backed settings for the search extras (T67): the last searches, the recents switch and
 * the web engine. Thread-safe: reads are a cold flow over DataStore's atomic snapshot and writes use
 * `updateData`.
 *
 * Recent queries are never sent anywhere. When [recentsEnabled] is false, [recordSearch] is a no-op
 * and the sheet hides the list; the stored list is kept so turning the switch back on restores it
 * (unless the user clears it first).
 */
class SearchPrefs(private val store: DataStore<Preferences>) {

    val snapshot: Flow<SearchPrefsSnapshot> = store.data.map(Preferences::toSearchPrefs)

    val recentSearches: Flow<List<String>> = store.data.map { RecentSearchRules.decode(it[KeyRecentSearches]) }

    val recentsEnabled: Flow<Boolean> = store.data.map { it[KeyRecentsEnabled] ?: DefaultRecentsEnabled }

    val webEngine: Flow<WebSearchEngine> = store.data.map { WebSearchEngine.fromId(it[KeyWebEngine]) }

    /** Records a search that actually led somewhere; a blank query is ignored. */
    suspend fun recordSearch(query: String) {
        if (query.isBlank()) return
        store.edit { prefs ->
            if (prefs[KeyRecentsEnabled] ?: DefaultRecentsEnabled) {
                val current = RecentSearchRules.decode(prefs[KeyRecentSearches])
                prefs[KeyRecentSearches] = RecentSearchRules.encode(RecentSearchRules.record(current, query))
            }
        }
    }

    suspend fun clearRecentSearches() {
        store.edit { it[KeyRecentSearches] = "" }
    }

    suspend fun setRecentsEnabled(enabled: Boolean) {
        store.edit { it[KeyRecentsEnabled] = enabled }
    }

    suspend fun setWebEngine(engine: WebSearchEngine) {
        store.edit { it[KeyWebEngine] = engine.id }
    }

    suspend fun setPhoneSettingsEnabled(enabled: Boolean) {
        store.edit { it[KeySourceSettings] = enabled }
    }

    suspend fun setFilesEnabled(enabled: Boolean) {
        store.edit { it[KeySourceFiles] = enabled }
    }

    suspend fun setEventsEnabled(enabled: Boolean) {
        store.edit { it[KeySourceEvents] = enabled }
    }

    /** Marks the one-time "Search files and calendar" row as used. Not part of the backup. */
    suspend fun markExtrasPromptDone() {
        store.edit { it[KeyExtrasPromptDone] = true }
    }

    companion object {
        const val DefaultRecentsEnabled = true

        /** T102: on by default; Files and Calendar events stay silent until the permission is granted. */
        const val DefaultSourceEnabled = true
        const val StoreName = "parc_search"
        internal val KeyRecentSearches = stringPreferencesKey("recent_searches")
        internal val KeyRecentsEnabled = booleanPreferencesKey("recents_enabled")
        internal val KeyWebEngine = stringPreferencesKey("web_engine")
        internal val KeySourceSettings = booleanPreferencesKey("source_settings")
        internal val KeySourceFiles = booleanPreferencesKey("source_files")
        internal val KeySourceEvents = booleanPreferencesKey("source_events")
        internal val KeyExtrasPromptDone = booleanPreferencesKey("extras_prompt_done")

        /** Builds the process-wide search settings store for an app context. */
        fun create(context: Context): SearchPrefs = SearchPrefs(context.applicationContext.parcSearchStore)
    }
}

/** Decodes one DataStore snapshot; missing or unknown values become the documented defaults. */
internal fun Preferences.toSearchPrefs(): SearchPrefsSnapshot = SearchPrefsSnapshot(
    recentSearches = RecentSearchRules.decode(this[SearchPrefs.KeyRecentSearches]),
    recentsEnabled = this[SearchPrefs.KeyRecentsEnabled] ?: SearchPrefs.DefaultRecentsEnabled,
    webEngine = WebSearchEngine.fromId(this[SearchPrefs.KeyWebEngine]),
    phoneSettingsEnabled = this[SearchPrefs.KeySourceSettings] ?: SearchPrefs.DefaultSourceEnabled,
    filesEnabled = this[SearchPrefs.KeySourceFiles] ?: SearchPrefs.DefaultSourceEnabled,
    eventsEnabled = this[SearchPrefs.KeySourceEvents] ?: SearchPrefs.DefaultSourceEnabled,
    extrasPromptDone = this[SearchPrefs.KeyExtrasPromptDone] ?: false,
)

internal val Context.parcSearchStore by preferencesDataStore(name = SearchPrefs.StoreName,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(SearchPrefs.StoreName))
