package com.brightblu.parc

import com.brightblu.parc.core.data.focus.*
import org.junit.Assert.*
import org.junit.Test

class T104Test {
    @Test fun summariesDescribeActualSettingsAndUseSingularCounts() {
        val one = FocusMode("Quiet", hiddenPackages = setOf("social"))
        assertEquals("Hiding 1 app", focusSectionSummary("Apps", one))
        assertEquals("Allowing 3 apps · 1 folder", focusSectionSummary("Apps", one.copy(
            appPolicy = FocusAppPolicy.AllowList, hiddenPackages = setOf("one", "two", "three"), folderIds = setOf("tools"))))
        assertEquals("1 schedule · Priority 0", focusSectionSummary("Schedule", one))
        assertEquals("2 schedules", focusScheduleCount(one.copy(extraSchedules = listOf(FocusSchedule()))))
        assertEquals("Silent badges · DND off", focusSectionSummary("Notifications", one.copy(silenceBadges = true)))
        assertEquals("2 passes per day · 5 min · None", focusSectionSummary("Friction", one.copy(pauseBeforeOpen = true, strict = true)))
        assertTrue(focusSectionSummary("Look", one.copy(skinId = "Dusk", iconTreatment = FocusIconTreatment.Greyscale)).contains("Dusk · Greyscale"))
        assertTrue(focusSectionSummary("Basics", one.copy(glyph = "◇")).startsWith("Study"))
    }
    @Test fun summaryUsesSingularAndPluralAttempts() {
        assertEquals("Quiet: 2 min focused · 1 blocked attempt", focusSummaryText(FocusSummary("Quiet", 120000, 1)))
        assertEquals("Quiet: 0 min focused · 0 blocked attempts", focusSummaryText(FocusSummary("Quiet", 0, 0)))
    }
    @Test fun priorityStopsAtEndpointsInsteadOfWrapping() {
        assertEquals(0, stepFocusPriority(0, -1))
        assertEquals(9, stepFocusPriority(9, 1))
        assertEquals(4, stepFocusPriority(5, -1))
    }
    @Test fun invalidEditsStayOpenAndRenameKeepsItsOwnIdentity() {
        val mode = FocusMode("Quiet")
        val modes = listOf(mode, FocusMode("Work"))
        assertEquals("Name required", focusEditIssue(mode, " ", "Quiet", modes))
        assertEquals("Name already used", focusEditIssue(mode, "Work", "Quiet", modes))
        assertEquals("", focusEditIssue(mode, " Quiet ", "Quiet", modes))
        assertEquals("", focusEditIssue(mode.copy(strict = true, confirmationPhrase = ""), "Quiet", "Quiet", modes))
    }
    @Test fun headerKeepsRemainingTimeAndGlyphsHaveUniqueNames() {
        val mode = FocusMode("Quiet")
        assertEquals("30 min left", focusRemaining(FocusState(active = mode, untilMillis = 1800000, evaluatedAtMillis = 1)))
        assertEquals("0 min left", focusRemaining(FocusState(active = mode, untilMillis = 1, evaluatedAtMillis = 2)))
        assertEquals("Until ended", focusRemaining(FocusState(active = mode)))
        assertEquals(12, FocusGlyphs.map { it.second }.distinct().size)
        assertTrue(FocusGlyphs.all { it.second.isNotBlank() })
    }
}
