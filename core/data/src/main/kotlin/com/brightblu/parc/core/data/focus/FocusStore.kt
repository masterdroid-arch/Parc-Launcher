package com.brightblu.parc.core.data.focus

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Storage boundary for focus modes (T72). Two documents live in one DataStore: the ordered mode
 * list and the single manual override. [updateModes] is an atomic read-modify-write, so two quick
 * edits (a save and a delete) cannot lose one another.
 *
 * The production implementation is [FocusPrefs]; a test substitutes an in-memory DataStore.
 */
internal interface FocusModeStore {
    /** Ordered mode list; emits an empty list for a missing or unusable value. */
    val modes: Flow<List<FocusMode>>

    /** The stored manual override, or null. */
    val override: Flow<FocusOverride?>

    /**
     * OPUS6 (audit F4): how many stored modes this build cannot read (kept untouched), or -1 when
     * the whole document is unreadable (kept untouched, and no edit is written over it).
     */
    val unreadable: Flow<Int>

    /** Replaces the list with `transform(current)` in one DataStore update. */
    suspend fun updateModes(transform: (List<FocusMode>) -> List<FocusMode>)

    /** Stores [value], or removes the key when null. */
    suspend fun grantExit(modeName: String, use: FocusUse, value: FocusOverride?, retainAfterMillis: Long, valid: () -> Boolean): Boolean

    suspend fun writeOverride(value: FocusOverride?)
}

/**
 * DataStore-backed [FocusModeStore], in its own `parc_focus` preferences file so focus data is
 * independent of the launcher settings and of the layout database (T72).
 *
 * Keys: `focus_modes` holds the JSON list, `focus_override` the JSON override. Both are read through
 * [FocusJson], which falls back to "nothing set" instead of throwing.
 */
internal class FocusPrefs internal constructor(
    private val store: DataStore<Preferences>,
) : FocusModeStore {

    override val modes: Flow<List<FocusMode>> =
        store.data.map { FocusJson.decodeModes(it[KeyModes]) }

    override val override: Flow<FocusOverride?> =
        store.data.map { FocusJson.decodeOverride(it[KeyOverride]) }

    override val unreadable: Flow<Int> =
        store.data.map { prefs -> FocusJson.decodeStored(prefs[KeyModes]).let { if (it.corrupt) -1 else it.unreadable.size } }

    override suspend fun updateModes(transform: (List<FocusMode>) -> List<FocusMode>) {
        store.edit { prefs ->
            val stored = FocusJson.decodeStored(prefs[KeyModes])
            // Text that is not a list at all is never overwritten (the Notes rule): refuse the edit.
            if (stored.corrupt) return@edit
            val next = transform(stored.modes)
            if (next.isEmpty() && stored.unreadable.isEmpty()) {
                prefs.remove(KeyModes)
            } else {
                prefs[KeyModes] = FocusJson.encodeStored(next, stored.unreadable)
            }
        }
    }

    /** Ledger and exit commit together; a cancelled screen never consumes a pass on its own. */
    override suspend fun grantExit(modeName: String, use: FocusUse, value: FocusOverride?, retainAfterMillis: Long, valid: () -> Boolean): Boolean {
        var accepted = false
        store.edit { prefs ->
            val stored = FocusJson.decodeStored(prefs[KeyModes])
            val modes = stored.modes
            if (stored.corrupt || !valid() || modes.none { it.name == modeName }) return@edit
            prefs[KeyModes] = FocusJson.encodeStored(modes.map {
                if (it.name == modeName) it.copy(ledger = FocusLedger(it.ledger.uses.filter { previous -> previous.atMillis >= retainAfterMillis } + use)) else it
            }, stored.unreadable)
            if (value == null) prefs.remove(KeyOverride) else prefs[KeyOverride] = FocusJson.encodeOverride(value)
            accepted = true
        }
        return accepted
    }

    override suspend fun writeOverride(value: FocusOverride?) {
        store.edit { prefs ->
            if (value == null) {
                prefs.remove(KeyOverride)
            } else {
                prefs[KeyOverride] = FocusJson.encodeOverride(value)
            }
        }
    }

    companion object {
        const val StoreName = "parc_focus"
        internal val KeyModes = stringPreferencesKey("focus_modes")
        internal val KeyOverride = stringPreferencesKey("focus_override")

        /** The process-wide store for an app context. */
        fun create(context: Context): FocusPrefs =
            FocusPrefs(context.applicationContext.parcFocusStore)
    }
}

internal val Context.parcFocusStore by preferencesDataStore(name = FocusPrefs.StoreName,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(FocusPrefs.StoreName))
