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
    fun `a fresh install enables Comms with exactly WhatsApp Telegram and Instagram`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())

        assertTrue(prefs.commsEnabled.first())
        assertEquals(CommsPackages.Default, prefs.commsApps.first())
        assertEquals(3, CommsPackages.Default.size)
        assertEquals(
            setOf(CommsPackages.Telegram, CommsPackages.WhatsApp, CommsPackages.Instagram),
            CommsPackages.Default,
        )
        assertFalse(CommsPackages.Default.contains(CommsPackages.Messages))
        assertFalse(CommsPackages.Default.contains(CommsPackages.Signal))
        assertFalse(CommsPackages.Default.contains(CommsPackages.Discord))
        assertFalse(CommsPackages.Default.contains(CommsPackages.Imo))
    }

    @Test
    fun `the four fixed opt-ins stay offered without joining the fresh default`() = runTest {
        assertEquals(
            listOf(CommsPackages.Messages, CommsPackages.Signal, CommsPackages.Discord, CommsPackages.Imo),
            CommsPackages.FixedOptIns,
        )
        assertEquals(CommsPackages.Default + CommsPackages.FixedOptIns, CommsPackages.Known)
    }

    @Test
    fun `an existing seven app allow list is read unchanged after the default shrinks`() = runTest {
        val stored = CommsPackages.Default + CommsPackages.FixedOptIns
        val prefs = ParcPrefs(FakePreferencesStore(preferencesOf(ParcPrefs.KeyCommsApps to stored)))

        assertEquals(stored, prefs.commsApps.first())
        assertEquals(7, prefs.commsApps.first().size)
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
