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
import com.brightblu.parc.model.ArcFeel
import com.brightblu.parc.model.Bounce
import com.brightblu.parc.model.Magnify
import com.brightblu.parc.model.SpinLimit
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

/** T147: how many arcs the home screen shows. Stored here and exposed only; T155 reads it. */
internal enum class HomeArcs(val id: String, val count: Int) {
    Two("two", 2), Four("four", 4);

    companion object {
        fun fromId(id: String?): HomeArcs = entries.firstOrNull { it.id == id } ?: Four
    }
}

/** Pure encode/decode so defaults and bad stored values are testable without a DataStore. */
internal object FeelPrefsCodec {
    const val HapticKey = "haptic_preset"
    const val SoundKey = "sound_theme"
    const val VolumeKey = "sound_volume"
    const val SpinLimitKey = "arc_spin_limit"
    const val BounceKey = "arc_bounce"
    const val MagnifyKey = "arc_magnify"
    const val HomeArcsKey = "arc_home_arcs"

    /** Unknown or missing ids give Balanced, Balanced, Subtle. */
    fun decodeArc(spin: String?, bounce: String?, magnify: String?): ArcFeel =
        ArcFeel(SpinLimit.fromId(spin), Bounce.fromId(bounce), Magnify.fromId(magnify))

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

    private val spinKey = stringPreferencesKey(FeelPrefsCodec.SpinLimitKey)
    private val bounceKey = stringPreferencesKey(FeelPrefsCodec.BounceKey)
    private val magnifyKey = stringPreferencesKey(FeelPrefsCodec.MagnifyKey)
    private val homeArcsKey = stringPreferencesKey(FeelPrefsCodec.HomeArcsKey)

    private fun Preferences.toPrefs() = FeelPrefsCodec.decode(this[hapticKey], this[soundKey], this[volumeKey])

    val prefs = store.data.map { it.toPrefs() }.catch {
        if (it is IOException) emit(FeelPrefs()) else throw it
    }

    /** T147: the arc choices; a read error gives the defaults. */
    val arcFeel = store.data.map { FeelPrefsCodec.decodeArc(it[spinKey], it[bounceKey], it[magnifyKey]) }.catch {
        if (it is IOException) emit(ArcFeel()) else throw it
    }

    /** T147: Two or Four home arcs (default Four). */
    val homeArcs = store.data.map { HomeArcs.fromId(it[homeArcsKey]) }.catch {
        if (it is IOException) emit(HomeArcs.Four) else throw it
    }

    suspend fun updateArcFeel(change: (ArcFeel) -> ArcFeel) {
        store.edit {
            val next = change(FeelPrefsCodec.decodeArc(it[spinKey], it[bounceKey], it[magnifyKey]))
            it[spinKey] = next.spinLimit.id
            it[bounceKey] = next.bounce.id
            it[magnifyKey] = next.magnify.id
        }
    }

    suspend fun setHomeArcs(value: HomeArcs) {
        store.edit { it[homeArcsKey] = value.id }
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
