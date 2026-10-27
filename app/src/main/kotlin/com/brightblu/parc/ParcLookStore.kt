package com.brightblu.parc

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.brightblu.parc.model.LookJson
import com.brightblu.parc.model.LookSettings
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

internal val Context.lookDataStore by preferencesDataStore(name = "parc_look",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("parc_look"))

/**
 * T71: app-local storage for the look settings, following [HubConfigStore] and [GestureOptionsStore].
 * Never `core/data` ParcPrefs: the look is a Sol-owned app setting. A missing, unreadable or invalid
 * document keeps [LookSettings]' defaults, which reproduce the pre-T71 appearance exactly.
 */
internal class ParcLookStore(context: Context) {
    private val store = context.applicationContext.lookDataStore
    private val key = stringPreferencesKey("look_v1")
    private val defaults = LookSettings(wallpaper = com.brightblu.parc.model.WallpaperStyle.System)

    val look = store.data.map { prefs ->
        val text = prefs[key] ?: return@map defaults
        runCatching { LookJson.decode(text) }.getOrDefault(defaults)
    }.catch {
        if (it is IOException) emit(defaults) else throw it
    }

    /** Applies a pure change to the stored look; the whole document is rewritten atomically. */
    suspend fun update(change: (LookSettings) -> LookSettings) {
        store.edit { prefs -> LookDocument.apply(prefs[key], prefs[unreadableKey], defaults, change).let { (next, keep) ->
            prefs[key] = next
            if (keep != null) prefs[unreadableKey] = keep
        } }
    }

    /** OPUS6 (audit F4): the last look document this build could not read, kept for recovery. */
    private val unreadableKey = stringPreferencesKey("look_v1_unreadable")
}

/**
 * OPUS6 (audit F4): one change to the stored look. When the stored text cannot be read, the change
 * starts from the defaults (the screen already shows them), but the unreadable text is first kept
 * under a separate key, never silently lost; an earlier kept copy is not replaced. Pure.
 */
internal object LookDocument {
    fun apply(stored: String?, kept: String?, defaults: LookSettings,
        change: (LookSettings) -> LookSettings): Pair<String, String?> {
        val current = stored?.let { runCatching { LookJson.decode(it) }.getOrNull() }
        val keep = if (stored != null && current == null && kept == null) stored else null
        return LookJson.encode(change(current ?: defaults)) to keep
    }
}
