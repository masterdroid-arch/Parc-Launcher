package com.brightblu.parc.core.data

import android.graphics.Bitmap
import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.core.data.profiles.ProfileKind
import com.brightblu.parc.core.data.profiles.isProfilePaused
import com.brightblu.parc.core.data.profiles.isWork
import com.brightblu.parc.model.AppId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T74: a fake [LauncherSource] with a personal (serial 0) and a work (serial 10) profile, through
 * the real index, launcher, shortcut provider and icon producer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileAppIndexTest {

    private val personalId = AppId("com.same/com.same.Main@0")
    private val workId = AppId("com.same/com.same.Main@10")
    private val workShortcuts = listOf(SourceShortcut("msg", "Message"))

    private val personalEntry = sourceActivity("com.same/com.same.Main", "Same", userSerial = 0)
    private val workEntry = sourceActivity("com.same/com.same.Main", "Same", userSerial = 10)

    private class NullIconProducer : IconProducer<Bitmap> {
        override suspend fun rasterise(id: AppId, sizePx: Int): Bitmap? = null
        override suspend fun letterTile(label: String, sizePx: Int): Bitmap? = null
    }

    private fun newStore(): SnapshotStore {
        val dir = Files.createTempDirectory("parc-profiles").toFile()
        return SnapshotStore(File(dir, "app_snapshot.json"))
    }

    private fun build(source: FakeLauncherSource, scope: TestScope): LauncherAppIndex = LauncherAppIndex(
        source = source,
        scope = scope,
        snapshot = newStore(),
        iconProducer = NullIconProducer(),
        ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        debounceMillis = 300,
        logWarn = {},
    )

    private fun twoProfileSource(): FakeLauncherSource = FakeLauncherSource(
        entries = listOf(personalEntry, workEntry),
        profileSet = sourceProfiles(
            0,
            sourceProfile(0, ProfileKind.Personal),
            sourceProfile(10, ProfileKind.Work),
        ),
    )

    @Test
    fun `every profile is listed and the work app is marked by its serial`() = runTest {
        val source = twoProfileSource()
        val index = build(source, this)
        advanceUntilIdle()

        assertEquals(listOf(personalId, workId).map { it.value }, index.apps.value.map { it.id.value })
        val profiles = index.profiles.value
        assertTrue(profiles.loaded)
        assertEquals(0L, profiles.currentSerial)
        assertEquals(setOf(10L), profiles.workSerials)

        val personal = index.apps.value.first { it.id == personalId }
        val work = index.apps.value.first { it.id == workId }
        assertFalse(personal.isWork(profiles))
        assertTrue(work.isWork(profiles))
        assertFalse(personal.isProfilePaused(profiles))
        assertFalse(work.isProfilePaused(profiles))
        index.close()
    }

    @Test
    fun `the profile scan runs with the activity scan`() = runTest {
        val source = twoProfileSource()
        val index = build(source, this)
        assertEquals(0, source.profilesCalls)
        advanceUntilIdle()
        assertEquals(1, source.profilesCalls)
        assertEquals(1, source.activitiesCalls)
        index.close()
    }

    @Test
    fun `launch uses the serial from the app id`() = runTest {
        val source = twoProfileSource()
        val index = build(source, this)
        advanceUntilIdle()

        assertEquals(LaunchOutcome.Started, index.launchWithOutcome(workId))
        assertEquals(listOf("com.same/com.same.Main" to 10L), source.launched)

        index.launch(personalId)
        assertEquals(
            listOf("com.same/com.same.Main" to 10L, "com.same/com.same.Main" to 0L),
            source.launched,
        )
        index.close()
    }

    @Test
    fun `a paused work profile is reported and its launch says paused`() = runTest {
        val source = FakeLauncherSource(
            entries = listOf(personalEntry, workEntry),
            profileSet = sourceProfiles(
                0,
                sourceProfile(0, ProfileKind.Personal),
                sourceProfile(10, ProfileKind.Work, paused = true),
            ),
        ).apply { launchOutcome = LaunchOutcome.ProfilePaused }
        val index = build(source, this)
        advanceUntilIdle()

        val work = index.apps.value.first { it.id == workId }
        assertTrue(work.isProfilePaused(index.profiles.value))
        assertTrue(index.profiles.value.hasWorkProfile)
        assertEquals(LaunchOutcome.ProfilePaused, index.launchWithOutcome(workId))
        index.close()
    }

    @Test
    fun `a removed profile drops its apps and is marked removed`() = runTest {
        val source = twoProfileSource()
        val index = build(source, this)
        advanceUntilIdle()
        assertEquals(2, index.apps.value.size)

        source.entries = listOf(personalEntry)
        source.profileSet = sourceProfiles(0, sourceProfile(0, ProfileKind.Personal))
        source.fireChanged()
        advanceUntilIdle()

        assertEquals(listOf(personalId.value), index.apps.value.map { it.id.value })
        val profiles = index.profiles.value
        assertTrue(profiles.loaded)
        assertTrue(profiles.isRemoved(workId))
        assertFalse(profiles.hasWorkProfile)
        assertNull(profiles.kindOf(workId))
        index.close()
    }

    @Test
    fun `refresh rereads both lists without a package event`() = runTest {
        val source = twoProfileSource()
        val index = build(source, this)
        advanceUntilIdle()

        val newWorkEntry = sourceActivity(
            "com.same/com.same.Other",
            "Other",
            userSerial = 10,
        )
        source.entries = listOf(personalEntry, newWorkEntry)
        index.refresh()
        advanceUntilIdle()

        assertEquals(2, source.activitiesCalls)
        assertEquals(2, source.profilesCalls)
        assertTrue(index.apps.value.any { it.id == AppId("com.same/com.same.Other@10") })
        index.close()
    }

    @Test
    fun `shortcuts are read for the profile that owns the app`() = runTest {
        val source = twoProfileSource().apply { shortcutsResult = workShortcuts }
        val index = build(source, this)
        advanceUntilIdle()

        val shortcuts = index.shortcuts(workId)

        assertEquals(listOf("com.same/com.same.Main" to 10L), source.shortcutRequests)
        assertEquals(1, shortcuts.size)
        assertEquals(workId, shortcuts.first().appId)
        index.close()
    }

    @Test
    fun `icons are rasterised for the profile that owns the app`() = runTest {
        val source = twoProfileSource()
        val producer = AndroidIconProducer(source)

        assertNull(producer.rasterise(workId, 112))
        assertEquals(
            Triple("com.same/com.same.Main", 10L, 112),
            source.iconRequests.single(),
        )
    }
}
