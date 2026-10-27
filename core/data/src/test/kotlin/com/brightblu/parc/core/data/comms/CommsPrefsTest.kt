package com.brightblu.parc.core.data.comms

import androidx.datastore.preferences.core.preferencesOf
import com.brightblu.parc.core.data.FakePreferencesStore
import com.brightblu.parc.core.data.ParcPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommsPrefsTest {

    @Test
    fun `a fresh install enables Comms with the seven default apps`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())

        assertTrue(prefs.commsEnabled.first())
        assertEquals(CommsPackages.Default, prefs.commsApps.first())
        assertEquals(7, CommsPackages.Default.size)
        assertTrue(CommsPackages.Default.contains(CommsPackages.Telegram))
        assertTrue(CommsPackages.Default.contains(CommsPackages.WhatsApp))
        assertTrue(CommsPackages.Default.contains(CommsPackages.Instagram))
        assertTrue(CommsPackages.Default.contains(CommsPackages.Messages))
        assertTrue(CommsPackages.Default.contains(CommsPackages.Signal))
        assertTrue(CommsPackages.Default.contains(CommsPackages.Discord))
        assertTrue(CommsPackages.Default.contains(CommsPackages.Imo))
    }

    @Test
    fun `an explicitly empty allow list stays empty instead of restoring the defaults`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())

        prefs.setCommsApps(emptySet())

        assertEquals(emptySet<String>(), prefs.commsApps.first())
    }

    @Test
    fun `a custom allow list round trips`() = runTest {
        val store = FakePreferencesStore()
        val prefs = ParcPrefs(store)

        prefs.setCommsApps(setOf(CommsPackages.Signal, CommsPackages.Discord))

        assertEquals(
            setOf(CommsPackages.Signal, CommsPackages.Discord),
            ParcPrefs(store).commsApps.first(),
        )
    }

    @Test
    fun `the Comms switch round trips both ways`() = runTest {
        val store = FakePreferencesStore()
        val prefs = ParcPrefs(store)

        prefs.setCommsEnabled(false)
        assertFalse(ParcPrefs(store).commsEnabled.first())

        prefs.setCommsEnabled(true)
        assertTrue(ParcPrefs(store).commsEnabled.first())
    }

    @Test
    fun `a fresh install has the card canned replies and keeps history`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())

        assertEquals(CommsCannedReplies.Default, prefs.commsCannedReplies.first())
        assertTrue(prefs.commsKeepHistory.first())
    }

    @Test
    fun `canned replies round trip in order and are sanitised on write`() = runTest {
        val store = FakePreferencesStore()
        val prefs = ParcPrefs(store)

        prefs.setCommsCannedReplies(listOf("  Thanks ", "", "OK", "Thanks"))
        assertEquals(listOf("Thanks", "OK"), ParcPrefs(store).commsCannedReplies.first())

        prefs.setCommsCannedReplies((1..10).map { "reply $it" })
        assertEquals(8, ParcPrefs(store).commsCannedReplies.first().size)
    }

    @Test
    fun `an explicitly empty canned reply list stays empty`() = runTest {
        val store = FakePreferencesStore()
        val prefs = ParcPrefs(store)

        prefs.setCommsCannedReplies(emptyList())

        assertEquals(emptyList<String>(), ParcPrefs(store).commsCannedReplies.first())
    }

    @Test
    fun `the history switch round trips both ways`() = runTest {
        val store = FakePreferencesStore()
        val prefs = ParcPrefs(store)

        prefs.setCommsKeepHistory(false)
        assertFalse(ParcPrefs(store).commsKeepHistory.first())

        prefs.setCommsKeepHistory(true)
        assertTrue(ParcPrefs(store).commsKeepHistory.first())
    }
}
