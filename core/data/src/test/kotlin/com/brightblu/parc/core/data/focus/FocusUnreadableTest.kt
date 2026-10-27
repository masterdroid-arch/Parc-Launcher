package com.brightblu.parc.core.data.focus

import androidx.datastore.preferences.core.preferencesOf
import com.brightblu.parc.core.data.FakePreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS6 (audit F4): one unreadable Focus mode never erases the others. */
class FocusUnreadableTest {
    private val good = """{"name":"Work","enabled":true}"""
    private val bad = """{"name":"Night","schedule":{"startMinute":9999,"endMinute":60}}"""

    @Test fun oneBadModeIsKeptAndTheOthersSurviveAnEdit() = runTest {
        val data = FakePreferencesStore(preferencesOf(FocusPrefs.KeyModes to "[$good,$bad]"))
        val store = FocusPrefs(data)
        assertEquals(listOf("Work"), store.modes.first().map { it.name })
        assertEquals(1, store.unreadable.first())

        store.updateModes { it + FocusMode(name = "Read") }

        val text = data.data.first()[FocusPrefs.KeyModes]!!
        assertTrue("the unreadable mode is written back untouched", "\"startMinute\":9999" in text)
        assertEquals(listOf("Work", "Read"), store.modes.first().map { it.name })
        assertEquals(1, store.unreadable.first())

        // Deleting every readable mode still keeps the unreadable one.
        store.updateModes { emptyList() }
        assertTrue("\"Night\"" in data.data.first()[FocusPrefs.KeyModes]!!)
    }

    @Test fun textThatIsNotAListIsNeverOverwritten() = runTest {
        val data = FakePreferencesStore(preferencesOf(FocusPrefs.KeyModes to "{damaged"))
        val store = FocusPrefs(data)
        assertEquals(-1, store.unreadable.first())
        store.updateModes { it + FocusMode(name = "Read") }
        assertEquals("{damaged", data.data.first()[FocusPrefs.KeyModes])
    }
}
