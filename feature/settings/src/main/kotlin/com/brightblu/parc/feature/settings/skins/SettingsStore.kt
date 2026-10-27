package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.core.data.HapticsLevel
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.ParcSettings
import kotlinx.coroutines.flow.Flow

/**
 * The persistence the settings screen needs (T16). Declared here so the screen and its controller stay
 * free of DataStore, and so a JVM test can supply a plain in-memory fake. [ParcPrefs] is adapted by
 * [asSettingsStore]; the write methods are suspending because they touch disk.
 */
interface SettingsStore {
    val settings: Flow<ParcSettings>
    suspend fun setSkinId(id: String)
    suspend fun setHaptics(level: HapticsLevel)
    suspend fun setClockVisible(visible: Boolean)
}

/** Adapts the real DataStore-backed prefs to the screen's [SettingsStore] surface. */
fun ParcPrefs.asSettingsStore(): SettingsStore = object : SettingsStore {
    override val settings: Flow<ParcSettings> get() = this@asSettingsStore.settings
    override suspend fun setSkinId(id: String) = this@asSettingsStore.setSkinId(id)
    override suspend fun setHaptics(level: HapticsLevel) = this@asSettingsStore.setHaptics(level)
    override suspend fun setClockVisible(visible: Boolean) = this@asSettingsStore.setClockVisible(visible)
}
