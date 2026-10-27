package com.brightblu.parc

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.brightblu.parc.ring.FeelPlayer
import com.brightblu.parc.ring.FeelPrefs
import com.brightblu.parc.ring.HapticPreset
import com.brightblu.parc.ring.SoundTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

internal val Context.feelDataStore by preferencesDataStore(name = "feel",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("feel"))

/** Pure encode/decode so defaults and bad stored values are testable without a DataStore. */
internal object FeelPrefsCodec {
    const val HapticKey = "haptic_preset"
    const val SoundKey = "sound_theme"
    const val VolumeKey = "sound_volume"

    /** Unknown or missing values fall back to the defaults (Crisp, sound Off, volume 60). */
    fun decode(haptic: String?, sound: String?, volume: Int?): FeelPrefs = FeelPrefs(
        haptic = HapticPreset.fromId(haptic),
        sound = SoundTheme.fromId(sound),
        volumePercent = (volume ?: FeelPrefs.DefaultVolume).coerceIn(0, 100),
    )
}

/**
 * T80 app-local store for the Feel settings (not `core/data` ParcPrefs): haptic preset, sound
 * theme and sound volume. Defaults leave today's behaviour unchanged.
 */
internal class FeelStore(context: Context) {
    private val store = context.applicationContext.feelDataStore
    private val hapticKey = stringPreferencesKey(FeelPrefsCodec.HapticKey)
    private val soundKey = stringPreferencesKey(FeelPrefsCodec.SoundKey)
    private val volumeKey = intPreferencesKey(FeelPrefsCodec.VolumeKey)

    private fun Preferences.toPrefs() = FeelPrefsCodec.decode(this[hapticKey], this[soundKey], this[volumeKey])

    val prefs = store.data.map { it.toPrefs() }.catch {
        if (it is IOException) emit(FeelPrefs()) else throw it
    }

    suspend fun update(change: (FeelPrefs) -> FeelPrefs) {
        store.edit {
            val next = change(it.toPrefs())
            it[hapticKey] = next.haptic.id
            it[soundKey] = next.sound.id
            it[volumeKey] = next.volumePercent.coerceIn(0, 100)
        }
    }
}

/**
 * Remembers one [FeelPlayer] that follows the stored prefs. The tones are built off the main
 * thread whenever the sound theme changes.
 */
@Composable
internal fun rememberFeelPlayer(): FeelPlayer {
    val context = LocalContext.current
    val store = remember { FeelStore(context) }
    val state: State<FeelPrefs> = store.prefs.collectAsState(initial = FeelPrefs())
    val player = remember { FeelPlayer { state.value } }
    val theme = state.value.sound
    LaunchedEffect(theme) { withContext(Dispatchers.Default) { player.warmUp(context) } }
    return player
}
