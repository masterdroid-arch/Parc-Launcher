package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picker persistence (T62): order matters, and a revision makes each write apply exactly once. */
class MainAppsPrefsTest {

    private val alpha = AppId("com.alpha/com.alpha.Main@0")
    private val bravo = AppId("com.bravo/com.bravo.Main@0")

    @Test
    fun `the selection keeps picker order and removes duplicates`() {
        assertEquals(
            listOf(alpha, bravo),
            decodeMainApps(encodeMainApps(listOf(alpha, bravo, alpha))),
        )
    }

    @Test
    fun `a missing or blank value is an empty selection`() {
        assertTrue(decodeMainApps(null).isEmpty())
        assertTrue(decodeMainApps("").isEmpty())
        assertTrue(decodeMainApps("  \n \n").isEmpty())
    }

    @Test
    fun `an id containing spaces or slashes survives the codec`() {
        val odd = AppId("com.example/com.example.Main Activity@0")

        assertEquals(listOf(odd), decodeMainApps(encodeMainApps(listOf(odd))))
    }

    @Test
    fun `a fresh install has no selection and revision zero`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())

        assertTrue(prefs.mainApps.first().isEmpty())
        assertEquals(0L, prefs.mainAppsRevision.first())
        assertEquals(0L, prefs.mainAppsAppliedRevision.first())
    }

    @Test
    fun `each picker write bumps the revision and leaves the applied marker behind`() = runTest {
        val store = FakePreferencesStore()
        val prefs = ParcPrefs(store)

        prefs.setMainApps(listOf(alpha, bravo))
        assertEquals(listOf(alpha, bravo), prefs.mainApps.first())
        assertEquals(1L, prefs.mainAppsRevision.first())
        assertEquals(0L, prefs.mainAppsAppliedRevision.first())

        prefs.setMainApps(listOf(bravo))
        assertEquals(listOf(bravo), prefs.mainApps.first())
        assertEquals(2L, prefs.mainAppsRevision.first())

        prefs.setMainAppsAppliedRevision(2L)
        assertEquals(2L, prefs.mainAppsAppliedRevision.first())
        assertEquals(2L, ParcPrefs(store).mainAppsAppliedRevision.first())
    }

    @Test
    fun `the prefs store reports only a newer revision as pending`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())
        val store = PrefsMainAppsStore(prefs)

        assertNull(store.pending(applied = 0L))

        prefs.setMainApps(listOf(alpha))

        val pending = store.pending(applied = 0L)
        assertEquals(1L, pending?.revision)
        assertEquals(listOf(alpha), pending?.ids)
        assertNull(store.pending(applied = 1L))

        store.markApplied(1L)
        assertEquals(1L, store.appliedRevision())
    }
}
