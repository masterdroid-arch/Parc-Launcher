package com.brightblu.parc

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

internal val Context.gestureDataStore by preferencesDataStore(name = "gestures",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("gestures"))

/**
 * T69: app-local storage for the two gesture actions, following [HubConfigStore]. Never
 * `core/data` ParcPrefs: gesture choices are a Sol-owned app setting. Absent keys mean "never
 * chosen": swipe up opens All apps (OPUS8/T133), hub long-press enters edit mode.
 */
internal class GestureOptionsStore(context: Context) {
    private val store = context.applicationContext.gestureDataStore
    private val swipeKey = stringPreferencesKey(GestureSlot.SwipeUp.key)
    private val hubKey = stringPreferencesKey(GestureSlot.HubLongPress.key)
    private val defaults = GestureConfig()

    val config = store.data.map { prefs ->
        GestureConfig.decode(prefs[swipeKey], prefs[hubKey])
    }.catch {
        if (it is IOException) emit(defaults) else throw it
    }

    suspend fun set(slot: GestureSlot, action: GestureAction) {
        store.edit { prefs ->
            when (slot) {
                GestureSlot.SwipeUp -> prefs[swipeKey] = action.encode()
                GestureSlot.HubLongPress -> prefs[hubKey] = action.encode()
            }
        }
    }
}
