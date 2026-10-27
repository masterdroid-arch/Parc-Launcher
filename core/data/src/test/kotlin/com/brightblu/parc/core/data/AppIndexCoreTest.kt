package com.brightblu.parc.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.brightblu.parc.core.data.profiles.ProfileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppIndexCoreTest {

    @Test
    fun `debounce collapses a burst into one reload`() = runTest {
        val source = FakeLauncherSource(listOf(sourceActivity("p/C", "Alpha")))
        AppIndexCore(
            source = source,
            scope = this,
            debounceMillis = 300,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        val afterInitial = source.activitiesCalls
        assertEquals(1, afterInitial)

        repeat(5) { source.fireChanged() }
        advanceTimeBy(299)
        assertEquals(afterInitial, source.activitiesCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(afterInitial + 1, source.activitiesCalls)
    }

    @Test
    fun `a single callback reloads once after the debounce`() = runTest {
        val source = FakeLauncherSource(listOf(sourceActivity("p/C", "Alpha")))
        AppIndexCore(source, this, 300, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        source.fireChanged()
        advanceUntilIdle()
        assertEquals(2, source.activitiesCalls)
    }

    @Test
    fun `two activities in one package stay distinct`() = runTest {
        val source = FakeLauncherSource(
            listOf(
                sourceActivity("com.x/com.x.One", "Instagram", packageName = "com.x"),
                sourceActivity("com.x/com.x.Two", "Threads", packageName = "com.x"),
            ),
        )
        val core = AppIndexCore(source, this, 300, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        assertEquals(
            setOf("com.x/com.x.One@0", "com.x/com.x.Two@0"),
            core.apps.value.map { it.id.value }.toSet(),
        )
        assertEquals(2, core.apps.value.size)
    }

    @Test
    fun `same component for another user stays distinct`() = runTest {
        val source = FakeLauncherSource(
            listOf(
                sourceActivity("com.x/com.x.Main", "Personal", userSerial = 0),
                sourceActivity("com.x/com.x.Main", "Work", userSerial = 10),
            ),
        )
        val core = AppIndexCore(source, this, 300, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        assertEquals(
            setOf("com.x/com.x.Main@0", "com.x/com.x.Main@10"),
            core.apps.value.map { it.id.value }.toSet(),
        )
    }

    @Test
    fun `entries are sorted by label case-insensitively`() = runTest {
        val source = FakeLauncherSource(
            listOf(
                sourceActivity("p/C", "beta"),
                sourceActivity("p/A", "Alpha"),
                sourceActivity("p/D", "gamma"),
            ),
        )
        val core = AppIndexCore(source, this, 300, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        assertEquals(listOf("Alpha", "beta", "gamma"), core.apps.value.map { it.label })
    }

    @Test
    fun `the profile set follows the source and is remapped`() = runTest {
        val source = FakeLauncherSource(
            entries = listOf(sourceActivity("p/A", "Alpha")),
            profileSet = sourceProfiles(
                0,
                sourceProfile(0, ProfileKind.Personal),
                sourceProfile(10, ProfileKind.Work, paused = true),
            ),
        )
        val core = AppIndexCore(source, this, 300, StandardTestDispatcher(testScheduler))
        assertFalse(core.profiles.value.loaded)

        advanceUntilIdle()

        val profiles = core.profiles.value
        assertTrue(profiles.loaded)
        assertEquals(setOf(10L), profiles.workSerials)
        assertTrue(profiles.isPaused(10L))
        assertEquals(ProfileKind.Personal, profiles.kindOf(0))
    }

    @Test
    fun `removal and re-add both propagate`() = runTest {
        val a = sourceActivity("p/A", "Alpha")
        val b = sourceActivity("p/B", "Beta")
        val source = FakeLauncherSource(listOf(a, b))
        val core = AppIndexCore(source, this, 300, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(2, core.apps.value.size)

        source.entries = listOf(a)
        source.fireChanged()
        advanceUntilIdle()
        assertEquals(listOf("Alpha"), core.apps.value.map { it.label })

        source.entries = listOf(a, b)
        source.fireChanged()
        advanceUntilIdle()
        assertEquals(listOf("Alpha", "Beta"), core.apps.value.map { it.label })
    }
}
