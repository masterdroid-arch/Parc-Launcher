package com.brightblu.parc.feature.settings.onboarding

import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.ParcSettings
import kotlinx.coroutines.flow.Flow

/**
 * Persistence the onboarding flow needs (T18): the settings snapshot (for the completed flag) and the
 * write that finishes the flow. Declared here so the controller stays off DataStore and a JVM test can
 * supply a plain in-memory fake, mirroring T16's [com.brightblu.parc.feature.settings.skins.SettingsStore].
 */
interface OnboardingStore {
    val settings: Flow<ParcSettings>
    suspend fun setOnboardingCompleted(completed: Boolean)
}

/** Adapts the real DataStore-backed prefs to the flow's [OnboardingStore] surface. */
fun ParcPrefs.asOnboardingStore(): OnboardingStore = object : OnboardingStore {
    override val settings: Flow<ParcSettings> get() = this@asOnboardingStore.settings
    override suspend fun setOnboardingCompleted(completed: Boolean) =
        this@asOnboardingStore.setOnboardingCompleted(completed)
}
