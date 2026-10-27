package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.focus.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class T107Test {
    private val zone = ZoneId.of("Europe/Lisbon")
    private val now = Instant.parse("2026-10-19T17:00:00Z").toEpochMilli()
    private val mode = FocusMode("Study", glyph = "◇", hiddenPackages = setOf("locked.package"), folderIds = setOf("private-folder"))
    private fun model(state: FocusState = FocusState(), modes: List<FocusMode> = listOf(mode), at: Long = now) = focusHubModel(state, modes, at, zone)

    @Test fun noModeAndNoSession() {
        assertEquals(FocusHubModel("No active focus", null, null), model(modes = emptyList()))
    }
    @Test fun activeCountsNeverExposeAppOrFolderIdentityAndTickRoundsUp() {
        val state = FocusState(active = mode, untilMillis = now + 90_000)
        assertEquals("◇ Study · 2 min left", model(state).status)
        assertEquals("Hiding 1 app · 1 folder", model(state).apps)
        assertEquals("Pass 0 of 2 used", model(state).passStatus)
        assertFalse(model(state).toString().contains("locked.package"))
        assertFalse(model(state).toString().contains("private-folder"))
        assertEquals("◇ Study · 1 min left", model(state, at = now + 60_000).status)
        assertEquals("◇ Study · 0 min left", model(state, at = now + 120_000).status)
        assertEquals("Allowing 1 app · 1 folder", model(state.copy(active = mode.copy(appPolicy = FocusAppPolicy.AllowList))).apps)
    }
    @Test fun pausedAndStrictStates() {
        assertEquals("Focus paused · 5 min break left", model(FocusState(breakUntilMillis = now + 300_000)).status)
        assertNull(model(FocusState(breakUntilMillis = now + 300_000)).apps)
        assertEquals("◇ Study · Until ended · Strict", model(FocusState(active = mode.copy(strict = true))).status)
    }
    @Test fun nextScheduleSkipsDisabledModesAndRangesAndUsesNextWeek() {
        val scheduled = mode.copy(schedule = FocusSchedule(setOf(FocusDay.Mon), 19 * 60, 20 * 60))
        assertEquals("Study starts 19:00", model(modes = listOf(scheduled)).nextSchedule)
        assertNull(model(modes = listOf(scheduled.copy(enabled = false))).nextSchedule)
        assertNull(model(modes = listOf(scheduled.copy(schedule = scheduled.schedule.copy(enabled = false)))).nextSchedule)
        assertEquals("Study starts Mon 19:00", model(modes = listOf(scheduled), at = now + 3 * 3_600_000).nextSchedule)
        val tomorrow = scheduled.copy(extraSchedules = listOf(FocusSchedule(setOf(FocusDay.Tue), 60, 120)))
        assertEquals("Study starts tomorrow 01:00", model(modes = listOf(tomorrow), at = now + 3 * 3_600_000).nextSchedule)
    }
    @Test fun overlapAnnouncementUsesPriorityThenListOrder() {
        val schedule = FocusSchedule(setOf(FocusDay.Mon), 19 * 60, 20 * 60)
        assertEquals("Work starts 19:00", model(modes = listOf(mode.copy(schedule = schedule), FocusMode("Work", schedule = schedule, priority = 1))).nextSchedule)
    }
    @Test fun quickOptionsRoundTripExistingFieldsAndKeepOtherSettings() {
        FocusQuickOption.entries.forEach { option ->
            val changed = focusQuickChange(mode, option)
            assertNotEquals(mode, changed)
            assertEquals(changed, FocusJson.decodeModes(FocusJson.encodeModes(listOf(changed))).single())
            if (option != FocusQuickOption.Comms) assertEquals(mode, focusQuickChange(changed, option))
            assertEquals(mode.hiddenPackages, changed.hiddenPackages)
            assertEquals(mode.folderIds, changed.folderIds)
        }
        assertTrue(focusQuickChange(mode, FocusQuickOption.Badges).silenceBadges)
        assertTrue(focusQuickChange(mode, FocusQuickOption.Dnd).syncDnd)
        assertEquals(FocusIconTreatment.Greyscale, focusQuickChange(mode, FocusQuickOption.Greyscale).iconTreatment)
        val global = setOf("clock", "focus", "comms")
        val hidden = focusQuickChange(mode, FocusQuickOption.Comms, global)
        assertEquals(setOf("clock", "focus"), hidden.hubPages)
        assertEquals(global, focusQuickChange(hidden, FocusQuickOption.Comms, global).hubPages)
        assertFalse("system" in hidden.hubPages!!)
        assertEquals(BackupSpecs.focus, AppBackup.FocusModesSpec)
        assertNotNull(BackupSpecs.focus.key(BackupSpecs.FocusModesKey))
    }
    @Test fun quickOptionsRespectStrictSessionsAndOptionalDndAccess() {
        FocusQuickOption.entries.forEach { option ->
            assertFalse(focusQuickOptionAllowed(mode.copy(strict = true), option, true))
            assertTrue(focusQuickOptionAllowed(mode, option, true))
            assertEquals(option != FocusQuickOption.Dnd, focusQuickOptionAllowed(mode, option, false))
        }
    }
    @Test fun focusIsDefaultVisibleAfterMediaAndCanBeHiddenMovedAndRestored() {
        assertEquals(HubCatalog.pages.indexOf("media") + 1, HubCatalog.pages.indexOf("focus"))
        val old = HubConfig().encode().split(';').filterNot { it.startsWith("focus:") }.joinToString(";")
        assertTrue("focus" in HubConfig.decode(old).pages)
        val changed = HubConfig().movePage("focus", -2).showPage("focus", false)
        assertEquals(changed.encode(), HubConfig.decode(changed.encode()).encode())
        assertFalse("focus" in changed.pages)
        assertTrue("focus" in changed.showPage("focus", true).pages)
    }
}
