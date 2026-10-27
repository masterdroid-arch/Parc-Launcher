package com.brightblu.parc.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParcPrefsTest {

    /** In-memory DataStore; the real one only differs in where the bytes live. */
    private class FakeStore(initial: Preferences = preferencesOf()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private suspend fun ParcPrefs.snapshot(): ParcSettings = settings.first()

    @Test
    fun `a fresh install starts on Midnight, light haptics, a visible clock and a visible stats module`() = runTest {
        val prefs = ParcPrefs(FakeStore())

        assertEquals(ParcSettings(), prefs.snapshot())
        assertEquals("midnight", prefs.snapshot().skinId)
        assertEquals(HapticsLevel.Light, prefs.snapshot().haptics)
        assertTrue(prefs.snapshot().clockVisible)
        assertTrue(prefs.snapshot().statsVisible)
        assertFalse(prefs.snapshot().statsExpanded)
    }

    @Test
    fun `settings round trip through the store`() = runTest {
        val store = FakeStore()
        val prefs = ParcPrefs(store)

        prefs.setSkinId("dusk")
        prefs.setHaptics(HapticsLevel.Strong)
        prefs.setClockVisible(false)

        assertEquals(ParcSettings("dusk", HapticsLevel.Strong, false), ParcPrefs(store).snapshot())
    }

    @Test
    fun `an unknown haptics value falls back to light`() = runTest {
        val prefs = ParcPrefs(FakeStore(preferencesOf(stringPreferencesKey("haptics") to "Loud")))

        assertEquals(HapticsLevel.Light, prefs.snapshot().haptics)
    }

    @Test
    fun `a missing or blank skin id falls back to midnight`() = runTest {
        assertEquals(ParcPrefs.DefaultSkinId, ParcPrefs(FakeStore()).snapshot().skinId)
        assertEquals(
            ParcPrefs.DefaultSkinId,
            ParcPrefs(FakeStore(preferencesOf(stringPreferencesKey("skin_id") to "  "))).snapshot().skinId,
        )
    }

    @Test
    fun `every haptics level survives a write`() = runTest {
        val store = FakeStore()
        val prefs = ParcPrefs(store)

        HapticsLevel.entries.forEach { level ->
            prefs.setHaptics(level)
            assertEquals(level, prefs.snapshot().haptics)
        }
    }

    @Test
    fun `the stats flags default to visible and collapsed`() = runTest {
        val prefs = ParcPrefs(FakeStore())

        assertTrue(prefs.snapshot().statsVisible)
        assertFalse(prefs.snapshot().statsExpanded)
    }

    @Test
    fun `the stats flags round trip through the store`() = runTest {
        val store = FakeStore()
        val prefs = ParcPrefs(store)

        prefs.setStatsVisible(false)
        prefs.setStatsExpanded(true)

        val reloaded = ParcPrefs(store).snapshot()
        assertFalse(reloaded.statsVisible)
        assertTrue(reloaded.statsExpanded)
    }

    @Test
    fun `a fresh install has not completed onboarding`() = runTest {
        assertFalse(ParcPrefs(FakeStore()).snapshot().onboardingCompleted)
    }

    @Test
    fun `the onboarding flag round trips through the store`() = runTest {
        val store = FakeStore()
        val prefs = ParcPrefs(store)

        prefs.setOnboardingCompleted(true)
        assertTrue(ParcPrefs(store).snapshot().onboardingCompleted)

        prefs.setOnboardingCompleted(false)
        assertFalse(ParcPrefs(store).snapshot().onboardingCompleted)
    }

    @Test
    fun `the usage layer pref is unset on a fresh install`() = runTest {
        val prefs = ParcPrefs(FakeStore())

        assertNull(prefs.usageSlots.first())
        assertNull(prefs.usageRankDay.first())
        assertNull(prefs.layoutGeneration.first())
    }

    @Test
    fun `the usage layer pref round trips through the store`() = runTest {
        val store = FakeStore()
        val prefs = ParcPrefs(store)

        prefs.setUsageSlots("A2:0,A2:1")
        prefs.setUsageRankDay(20_300L)
        prefs.setLayoutGeneration(2)

        val reloaded = ParcPrefs(store)
        assertEquals("A2:0,A2:1", reloaded.usageSlots.first())
        assertEquals(20_300L, reloaded.usageRankDay.first())
        assertEquals(2, reloaded.layoutGeneration.first())
    }
}
