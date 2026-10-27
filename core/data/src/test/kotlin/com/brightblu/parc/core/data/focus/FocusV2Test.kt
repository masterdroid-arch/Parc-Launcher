package com.brightblu.parc.core.data.focus

import com.brightblu.parc.core.data.FakePreferencesStore
import com.brightblu.parc.core.data.backup.*
import com.brightblu.parc.model.ParcLayout
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FocusV2Test {
    private val utc = ZoneId.of("UTC")
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun oldModesMigrateWithoutLosingRules() {
        val modes = FocusJson.decodeModes("""[{"name":"Quiet","hiddenPackages":["test.social"],"silenceBadges":true,"schedule":{"days":["Mon"],"startMinute":1320,"endMinute":420}}]""")
        assertEquals(1, modes.size)
        val mode = modes.single()
        assertEquals(setOf("test.social"), mode.hiddenPackages)
        assertEquals(FocusAppPolicy.BlockList, mode.appPolicy)
        assertTrue(mode.enabled)
        assertFalse(mode.syncDnd)
        assertTrue(FocusRules.isScheduledActive(at("2026-10-20T02:00:00Z"), mode.schedule, utc))
        assertEquals(modes, FocusJson.decodeModes(FocusJson.encodeModes(modes)))
    }

    @Test fun allowAndBlockListsExpandFoldersAndAlwaysAllowEssentials() {
        val mode = FocusMode("Study", setOf("social"), appPolicy = FocusAppPolicy.BlockList, alwaysAllowed = setOf("phone"))
        val packages = setOf("social", "phone", "maps", "video")
        assertEquals(setOf("social", "video"), FocusPolicies.blocked(mode, packages, setOf("video", "phone")))
        assertEquals(setOf("maps"), FocusPolicies.blocked(mode.copy(appPolicy = FocusAppPolicy.AllowList), packages, setOf("video")))
    }

    @Test fun multipleRangesPriorityDisabledAndStableTies() {
        val first = FocusMode("First", schedule = FocusSchedule(setOf(FocusDay.Mon), 9 * 60, 12 * 60),
            extraSchedules = listOf(FocusSchedule(setOf(FocusDay.Mon), 14 * 60, 18 * 60)))
        val second = first.copy(name = "Second", priority = 2)
        val now = at("2026-10-19T15:00:00Z")
        assertEquals(second, FocusRules.activeFocus(now, listOf(first, second), utc))
        assertEquals(first, FocusRules.activeFocus(now, listOf(first, second.copy(enabled = false)), utc))
        assertEquals(first, FocusRules.activeFocus(now, listOf(first, second.copy(priority = 0)), utc))
        assertEquals(at("2026-10-19T18:00:00Z"), FocusRules.currentWindowEnd(now, first, utc))
    }

    @Test fun daylightSavingGapAndRepeatedHourUseInstantBoundaries() {
        val zone = ZoneId.of("Europe/Lisbon")
        val gap = FocusSchedule(setOf(FocusDay.Sun), 90, 180)
        assertTrue(FocusRules.isScheduledActive(at("2026-03-29T01:45:00Z"), gap, zone))
        assertFalse(FocusRules.isScheduledActive(at("2026-03-29T02:00:00Z"), gap, zone))
        val overnight = FocusSchedule(setOf(FocusDay.Sat), 22 * 60, 3 * 60)
        listOf("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z").forEach {
            assertTrue(FocusRules.isScheduledActive(at(it), overnight, zone))
        }
    }

    @Test fun presetsAreCompleteSafeAndDistinct() {
        assertEquals(6, FocusPreset.entries.size)
        FocusPreset.entries.forEach { preset ->
            val mode = preset.mode(setOf("phone", "messages", "maps"))
            assertTrue(mode.glyph.isNotBlank())
            assertFalse(mode.syncDnd)
            assertFalse(mode.schedule.enabled)
            assertEquals(setOf("phone", "messages", "maps"), mode.alwaysAllowed)
        }
        assertEquals(FocusAppPolicy.AllowList, FocusPreset.Sleep.mode().appPolicy)
        assertTrue(FocusPreset.Study.mode().pauseBeforeOpen)
        assertEquals(setOf(FocusDay.Sat, FocusDay.Sun), FocusPreset.Weekend.mode().schedule.days)
    }

    @Test fun strictSessionRequiresChallengeAndCannotBeEditedOrReplaced() = runTest {
        val now = at("2026-10-19T12:00:00Z")
        val repository = FocusRepository(FocusPrefs(FakePreferencesStore()), backgroundScope,
            { now + testScheduler.currentTime }, { utc })
        val strict = FocusMode("Study", strict = true, confirmationPhrase = "continue later")
        repository.save(strict)
        repository.save(FocusMode("Other"))
        runCurrent()
        repository.quickStart("Study", 60)
        runCurrent()
        assertFalse(repository.setManualOff("Study", "wrong"))
        assertFalse(repository.breakPass("wrong"))
        repository.save(strict.copy(strict = false))
        repository.delete("Study")
        repository.quickStart("Other", 30)
        runCurrent()
        assertTrue(repository.state.value.active!!.strict)
        assertEquals("Study", repository.state.value.activeName)
        val end = repository.beginExit(FocusExit.End)!!
        advanceTimeBy(30_000); runCurrent()
        assertFalse(repository.completeExit(end, "continue later"))
        assertTrue(repository.completeExit(end, end.sentence))
        runCurrent()
        assertNull(repository.state.value.active)
        assertEquals("Study", repository.summary.value?.name)
        repository.consumeSummary()
        assertNull(repository.summary.value)
    }

    @Test fun breakResumesAndTimedSessionExpiresWithSummary() = runTest {
        val now = at("2026-10-19T12:00:00Z")
        val repository = FocusRepository(FocusPrefs(FakePreferencesStore()), backgroundScope,
            { now + testScheduler.currentTime }, { utc })
        repository.save(FocusMode("Study"))
        runCurrent()
        repository.quickStart("Study", 30)
        runCurrent()
        repository.recordBlockedAttempt()
        advanceTimeBy(60_000); runCurrent()
        val pass = repository.beginExit(FocusExit.Pass)!!
        advanceTimeBy(30_000); runCurrent()
        assertTrue(repository.completeExit(pass))
        runCurrent()
        assertNull(repository.state.value.active)
        assertNull(repository.summary.value)
        advanceTimeBy(300_000); runCurrent()
        assertEquals("Study", repository.state.value.activeName)
        advanceTimeBy(24 * 60_000); runCurrent()
        assertNull(repository.state.value.active)
        assertEquals(25 * 60_000L, repository.summary.value?.focusedMillis)
        assertEquals(1, repository.summary.value?.blockedAttempts)
    }

    @Test fun subpageSaveRoundTripsAllOptionsAndRenameNeverDeletesAnotherMode() = runTest {
        val store = FocusPrefs(FakePreferencesStore())
        val repo = FocusRepository(store, backgroundScope, { at("2026-10-19T12:00:00Z") }, { utc })
        val initial = FocusPreset.Work.mode()
        repo.save(initial)
        repo.save(FocusMode("Other"))
        val edited = initial.copy(name = "Work revised", glyph = "◇", priority = 9,
            hiddenPackages = setOf("social"), hiddenComms = setOf("social"), syncDnd = true,
            skinId = "dusk", hubPages = setOf("system"), iconTreatment = FocusIconTreatment.Greyscale,
            pauseBeforeOpen = true, strict = true, confirmationPhrase = "done")
        assertTrue(repo.save(edited, initial.name))
        runCurrent()
        assertEquals(listOf(edited, FocusMode("Other")), repo.modes.value)
        assertFalse(repo.save(edited.copy(name = "Other"), edited.name))
        runCurrent()
        assertEquals(listOf(edited, FocusMode("Other")), repo.modes.value)
        val restored = FocusRepository(store, backgroundScope, { at("2026-10-19T12:00:00Z") }, { utc })
        runCurrent()
        assertEquals(edited, restored.modes.value.first())
    }

    @Test fun scheduleStartsAndEndsWithoutRefreshOrManualAction() = runTest {
        val now = at("2026-10-19T11:59:00Z")
        val repo = FocusRepository(FocusPrefs(FakePreferencesStore()), backgroundScope,
            { now + testScheduler.currentTime }, { utc })
        repo.save(FocusMode("Scheduled", schedule = FocusSchedule(setOf(FocusDay.Mon), 720, 721)))
        runCurrent()
        assertNull(repo.state.value.active)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("Scheduled", repo.state.value.activeName)
        assertFalse(repo.state.value.manual)
        advanceTimeBy(60_000); runCurrent()
        assertNull(repo.state.value.active)
        assertEquals(60_000L, repo.summary.value?.focusedMillis)
    }

    @Test fun everyNewModeOptionTravelsThroughBackup() {
        val mode = FocusPreset.Study.mode(setOf("phone")).copy(accent = 0xFF112233, glyph = "◇",
            folderIds = setOf("tools"), hiddenComms = setOf("social"), syncDnd = true,
            skinId = "midnight", hubPages = setOf("system"), mainAndAllowedOnly = true,
            iconTreatment = FocusIconTreatment.Greyscale, strict = true, confirmationPhrase = "finish",
            friction = FocusFriction(15, 5, PassPeriod.Week, FocusChallenge.Accountability, "JBSWY3DPEHPK3PXP"),
            ledger = FocusLedger(listOf(FocusUse(1234, true))),
            priority = 4, extraSchedules = listOf(FocusSchedule(setOf(FocusDay.Tue), 120, 240)))
        val text = FocusJson.encodeModes(listOf(mode))
        val document = BackupCodec.encode(ParcLayout(), mapOf(BackupSpecs.Focus to mapOf("focus_modes" to text)))
        val restored = BackupCodec.parse(document, BackupSpecs.all)
        assertEquals(listOf(mode), FocusJson.decodeModes(restored.values.getValue(BackupSpecs.Focus)["focus_modes"] as String))
    }
}
