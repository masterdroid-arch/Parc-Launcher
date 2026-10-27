package com.brightblu.parc.core.data.focus

import androidx.datastore.preferences.core.preferencesOf
import com.brightblu.parc.core.data.FakePreferencesStore
import com.brightblu.parc.core.data.apps.epoch
import com.brightblu.parc.core.data.apps.nairobi
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T72 repository on virtual time: DataStore writes, the process ticker moving the state at
 * schedule boundaries, and the manual override lifecycle. The wall clock is derived from the test
 * scheduler, so `advanceTimeBy` moves both the coroutine timer and the clock the repository reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusRepositoryTest {

    private val zone: ZoneId = nairobi

    /** Monday 2026-10-19, 12:00 local; virtual time zero. */
    private val start: Long = epoch(2026, 10, 19, 12, 0, zone)

    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10): Long =
        epoch(2026, month, day, hour, minute, zone)

    private inner class Fixture(scope: TestScope) {
        val data = FakePreferencesStore()
        val store = FocusPrefs(data)
        val repository = FocusRepository(
            store = store,
            scope = scope.backgroundScope,
            clock = { start + scope.testScheduler.currentTime },
            zone = { zone },
        )

        fun latest(): FocusState = repository.state.value

        suspend fun storedOverride(): FocusOverride? = store.override.first()
    }

    /** Runs every task due at the current virtual time, then checks nothing is left pending. */
    private fun TestScope.settle() {
        runCurrent()
    }

    /** Advances virtual time until the wall clock reads [target], then settles. */
    private fun TestScope.advanceTo(target: Long) {
        advanceTimeBy(target - (start + testScheduler.currentTime))
        runCurrent()
    }

    private suspend fun TestScope.endAfterDelay(repository: FocusRepository) {
        val ticket = repository.beginExit(FocusExit.End)!!
        advanceTimeBy(repository.exitRemaining(ticket)); runCurrent()
        assertTrue(repository.completeExit(ticket))
    }

    // --- stored modes ------------------------------------------------------

    @Test
    fun `a fresh repository has no modes, nothing active and no wakeup`() = runTest {
        val fixture = Fixture(this)
        settle()

        assertEquals(FocusState(), fixture.latest())
        assertEquals(emptyList<FocusMode>(), fixture.repository.modes.value)
        assertNull(fixture.storedOverride())
    }

    @Test
    fun `saving preserves order and replaces a mode with the same name`() = runTest {
        val fixture = Fixture(this)
        settle()

        fixture.repository.save(mode("Work"))
        fixture.repository.save(mode("Night"))
        settle()
        assertEquals(listOf("Work", "Night"), fixture.repository.modes.value.map { it.name })

        fixture.repository.save(mode("Work", hidden = setOf("com.changed")))
        settle()

        assertEquals(listOf("Work", "Night"), fixture.repository.modes.value.map { it.name })
        assertEquals(setOf("com.changed"), fixture.repository.modes.value[0].hiddenPackages)
    }

    @Test
    fun `saving trims the name and drops blank package names`() = runTest {
        val fixture = Fixture(this)
        settle()

        fixture.repository.save(
            FocusMode(
                name = "  Work  ",
                hiddenPackages = setOf(" com.real ", "", "   "),
                silenceBadges = true,
                schedule = schedule(),
            ),
        )
        settle()

        val stored = fixture.repository.modes.value.single()
        assertEquals("Work", stored.name)
        assertEquals(setOf("com.real"), stored.hiddenPackages)
        assertTrue(stored.silenceBadges)
    }

    @Test
    fun `deleting a mode also clears the override that named it`() = runTest {
        val fixture = Fixture(this)
        fixture.repository.save(mode("Work"))
        settle()
        fixture.repository.setManualOn("Work")
        settle()
        assertTrue(fixture.latest().manual)

        fixture.repository.delete("Work")
        settle()

        assertEquals(emptyList<FocusMode>(), fixture.repository.modes.value)
        assertNull(fixture.storedOverride())
        assertEquals(FocusState(), fixture.latest())
    }

    @Test
    fun `modes and override survive a new repository over the same DataStore`() = runTest {
        val fixture = Fixture(this)
        fixture.repository.save(mode("Night", schedule = schedule(days = setOf(FocusDay.Mon))))
        settle()
        fixture.repository.setManualOn("Night")
        settle()

        val restarted = FocusRepository(
            store = fixture.store,
            scope = backgroundScope,
            clock = { start + testScheduler.currentTime },
            zone = { zone },
        )
        settle()

        assertEquals(listOf("Night"), restarted.modes.value.map { it.name })
        assertEquals("Night", restarted.state.value.activeName)
        assertTrue(restarted.state.value.manual)
    }

    @Test
    fun `an unreadable stored list reads as no modes`() = runTest {
        val data = FakePreferencesStore(preferencesOf(FocusPrefs.KeyModes to "{not json"))
        val repository = FocusRepository(
            store = FocusPrefs(data),
            scope = backgroundScope,
            clock = { start + testScheduler.currentTime },
            zone = { zone },
        )
        settle()

        assertEquals(emptyList<FocusMode>(), repository.modes.value)
        assertEquals(FocusState(), repository.state.value)
    }

    // --- the ticker --------------------------------------------------------

    @Test
    fun `the state follows the schedule across every boundary`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(mode("Night", schedule = schedule()))
        settle()

        assertNull(fixture.latest().active)
        assertEquals(at(19, 22), fixture.latest().nextChangeMillis)

        advanceTo(at(19, 22))
        assertEquals("Night", fixture.latest().activeName)
        assertFalse(fixture.latest().manual)
        assertEquals(at(20, 7), fixture.latest().nextChangeMillis)

        advanceTo(at(20, 7))
        assertNull(fixture.latest().active)
        assertEquals(at(20, 22), fixture.latest().nextChangeMillis)
    }

    @Test
    fun `an overnight window closes even when the next day is not selected`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(mode("Night", schedule = schedule(days = setOf(FocusDay.Mon))))
        settle()

        advanceTo(at(19, 22))
        assertEquals("Night", fixture.latest().activeName)

        advanceTo(at(20, 7))
        assertNull(fixture.latest().active)
        assertEquals(at(26, 22), fixture.latest().nextChangeMillis)
    }

    @Test
    fun `an edit wakes the timer immediately instead of waiting for the old boundary`() = runTest {
        val fixture = Fixture(this)
        settle()
        assertNull(fixture.latest().nextChangeMillis)

        fixture.repository.save(
            mode("Lunch", schedule = schedule(days = setOf(FocusDay.Mon), startHour = 13, endHour = 14)),
        )
        settle()
        assertEquals(at(19, 13), fixture.latest().nextChangeMillis)

        advanceTo(at(19, 13))
        assertEquals("Lunch", fixture.latest().activeName)
    }

    @Test
    fun `deleting the active mode falls back to the next schedule match`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(mode("Work", schedule = schedule(days = setOf(FocusDay.Mon), startHour = 9, endHour = 17)))
        fixture.repository.save(mode("Home", schedule = schedule(days = setOf(FocusDay.Mon), startHour = 8, endHour = 18)))
        settle()

        advanceTo(at(19, 12))
        assertEquals("Work", fixture.latest().activeName)

        fixture.repository.delete("Work")
        settle()

        assertEquals("Home", fixture.latest().activeName)
        assertFalse(fixture.latest().manual)
    }

    // --- manual override ---------------------------------------------------

    @Test
    fun `manual on applies now and expires at the next closing time`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(mode("Night", schedule = schedule()))
        settle()

        fixture.repository.setManualOn("Night")
        settle()

        assertEquals("Night", fixture.latest().activeName)
        assertTrue(fixture.latest().manual)
        assertEquals(at(20, 7), fixture.latest().nextChangeMillis)

        advanceTo(at(20, 7))
        assertNull(fixture.latest().active)
        assertFalse(fixture.latest().manual)
        assertEquals(at(20, 22), fixture.latest().nextChangeMillis)
    }

    @Test
    fun `manual off lasts only to the end of the window it suppresses`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(mode("Night", schedule = schedule()))
        settle()

        advanceTo(at(19, 23))
        assertEquals("Night", fixture.latest().activeName)

        endAfterDelay(fixture.repository)
        settle()
        assertNull(fixture.latest().active)
        assertEquals(at(20, 7), fixture.latest().nextChangeMillis)

        advanceTo(at(20, 7))
        assertNull(fixture.latest().active)

        advanceTo(at(20, 22))
        assertEquals("Night", fixture.latest().activeName)
        assertFalse(fixture.latest().manual)
        assertNull(fixture.storedOverride()?.takeIf { it.isLiveAt(start + testScheduler.currentTime) })
    }

    @Test
    fun `manual off outside a window clears a manual on`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(mode("Night", schedule = schedule()))
        settle()

        fixture.repository.setManualOn("Night")
        settle()
        assertTrue(fixture.latest().manual)

        endAfterDelay(fixture.repository)
        settle()

        assertNull(fixture.latest().active)
        assertNull(fixture.storedOverride())
    }

    @Test
    fun `a manual off for one mode lets the next scheduled mode show`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(mode("Work", schedule = schedule()))
        fixture.repository.save(mode("Home", schedule = schedule()))
        settle()

        advanceTo(at(19, 23))
        assertEquals("Work", fixture.latest().activeName)

        endAfterDelay(fixture.repository)
        settle()

        assertEquals("Home", fixture.latest().activeName)
        assertFalse(fixture.latest().manual)
    }

    @Test
    fun `manual on for a disabled schedule lasts until turned off`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(mode("Work", schedule = schedule(enabled = false)))
        settle()

        fixture.repository.setManualOn("Work")
        settle()

        assertEquals("Work", fixture.latest().activeName)
        assertTrue(fixture.latest().manual)
        assertNull(fixture.latest().nextChangeMillis)

        fixture.repository.clearManualOverride()
        settle()
        assertEquals("Work", fixture.latest().activeName)
        endAfterDelay(fixture.repository)
        settle()

        assertNull(fixture.latest().active)
    }

    @Test
    fun `an unknown mode name changes nothing`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(mode("Work"))
        settle()

        fixture.repository.setManualOn("Missing")
        fixture.repository.setManualOff("Missing")
        fixture.repository.delete("Missing")
        settle()

        assertEquals(listOf("Work"), fixture.repository.modes.value.map { it.name })
        assertNull(fixture.storedOverride())
    }

    @Test
    fun `manual off for a mode outside its window leaves another mode's override alone`() = runTest {
        val fixture = Fixture(this)
        fixture.repository.save(mode("Work", schedule = schedule()))
        fixture.repository.save(
            mode("Home", schedule = schedule(days = setOf(FocusDay.Tue), startHour = 9, endHour = 17)),
        )
        settle()
        fixture.repository.setManualOn("Work")
        settle()

        fixture.repository.setManualOff("Home")
        settle()

        assertEquals("Work", fixture.latest().activeName)
        assertTrue(fixture.latest().manual)
        assertEquals("Work", fixture.storedOverride()?.modeName)
    }

    @Test
    fun `the active state carries the package set and badge rule the renderer needs`() = runTest {
        val fixture = Fixture(this)
        settle()
        fixture.repository.save(
            mode(
                "Work",
                schedule = schedule(),
                hidden = setOf("com.social", "com.news"),
                silenceBadges = true,
            ),
        )
        settle()

        advanceTo(at(19, 23))

        assertEquals(setOf("com.social", "com.news"), fixture.latest().hiddenPackages)
        assertTrue(fixture.latest().silenceBadges)
        assertFalse(FocusState().silenceBadges)
        assertEquals(emptySet<String>(), FocusState().hiddenPackages)
    }
}
