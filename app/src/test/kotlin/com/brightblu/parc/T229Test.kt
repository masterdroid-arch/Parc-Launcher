package com.brightblu.parc

import com.brightblu.parc.core.data.steps.StepOptions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T229: the shared page-options layout, stepper bounds and exclusive-choice persistence. */
class T229Test {
    @Test fun aPageWithItsOwnSettingsGetsTheSameQuietOrder() {
        val steps = hubPageOptionLayout(StepsPage.Id, tileCount = 0, showEditPages = true)
        assertEquals(listOf(HubOptionSection.ThisPage, HubOptionSection.Steps, HubOptionSection.More), steps.sections)
        assertEquals("Steps", steps.ownSection?.title)
        assertEquals("Steps", steps.name)
        assertTrue(steps.summary.isNotBlank())

        val library = hubPageOptionLayout(LibraryPage.Id, tileCount = 0, showEditPages = false)
        assertEquals(listOf(HubOptionSection.ThisPage, HubOptionSection.Library, HubOptionSection.More), library.sections)
        assertEquals("Books", library.ownSection?.title)

        assertEquals(HubOptionSection.Today, hubPageOptionLayout("today", 0, true).ownSection)
        assertEquals(HubOptionSection.Today, hubPageOptionLayout(TodayPage.Id, 0, true).ownSection)
        assertEquals(HubOptionSection.Rhythm, hubPageOptionLayout(RhythmPage.Id, 0, true).ownSection)
        assertEquals(HubOptionSection.Sky, hubPageOptionLayout(SkyPage.Id, 0, true).ownSection)
        assertEquals(HubOptionSection.Year, hubPageOptionLayout(YearPage.Id, 0, true).ownSection)
        assertEquals(HubOptionSection.Commonplace, hubPageOptionLayout(CommonplacePage.Id, 0, true).ownSection)
        if (WeatherVariant.Available) {
            assertEquals(HubOptionSection.Weather, hubPageOptionLayout(WeatherVariant.PageId, 0, true).ownSection)
        }
    }

    @Test fun aPageWithoutItsOwnSettingsSkipsThatSection() {
        val notes = hubPageOptionLayout("notes", tileCount = 0, showEditPages = true)
        assertNull(notes.ownSection)
        assertEquals(listOf(HubOptionSection.ThisPage, HubOptionSection.More), notes.sections)

        val records = hubPageOptionLayout(RecordsPage.Id, tileCount = 0, showEditPages = false)
        assertEquals(listOf(HubOptionSection.ThisPage, HubOptionSection.More), records.sections)
    }

    /** T393: Device and Controls draw nothing of their own under a section label, so they get no label. */
    @Test fun aPageWithNoOptionsOfItsOwnHasNoSectionLabel() {
        listOf(DevicePage.Id, ControlsPage.Id).forEach { page ->
            val layout = hubPageOptionLayout(page, tileCount = 0, showEditPages = true)
            assertNull(page, layout.ownSection)
            assertEquals(page, listOf(HubOptionSection.ThisPage, HubOptionSection.More), layout.sections)
        }
    }

    /** T393: the sheet's cards are inset like its header; the inset is the Metrics row inset, not a literal. */
    @Test fun theSheetInsetsItsCardsFromTheScreenEdge() {
        val options = File("src/main/kotlin/com/brightblu/parc/HubPageOptions.kt").readText()
        assertTrue(options.contains("CardInset(sheet)"))
        assertTrue(options.contains("Metrics.settingsRowInsetDp.dp) else Modifier"))
    }

    @Test fun thePersonalPagesUseTheSharedPageSettingsSection() {
        val extra = hubPageOptionLayout("extra-page", tileCount = 0, showEditPages = true, extraPage = true)
        assertEquals(listOf(HubOptionSection.ThisPage, HubOptionSection.Extra, HubOptionSection.More), extra.sections)
        assertEquals("Page settings", extra.ownSection?.title)
    }

    @Test fun everyCatalogPageKeepsTheCalmOrderAndOnlyAddsTilesWhenItHasThem() {
        HubCatalog.pages.forEach { page ->
            val tiles = HubCatalog.tiles(page)
            val layout = hubPageOptionLayout(page, tiles.size, showEditPages = true, extraPage = extraPage(page) != null)
            assertEquals(page, HubOptionSection.ThisPage, layout.sections.first())
            assertEquals(page, HubOptionSection.More, layout.sections.last())
            assertEquals(page, tiles.isNotEmpty(), HubOptionSection.Tiles in layout.sections)
            assertTrue(page, layout.summary.isNotBlank())
        }
    }

    @Test fun tilesAddOneLabelRatherThanAnotherCardPerPage() {
        val apps = hubPageOptionLayout("apps", tileCount = 3, showEditPages = true)
        assertEquals(listOf(HubOptionSection.ThisPage, HubOptionSection.Tiles, HubOptionSection.More), apps.sections)
        assertNull(apps.ownSection)

        val today = hubPageOptionLayout("today", tileCount = 0, showEditPages = true)
        assertFalse(HubOptionSection.Tiles in today.sections)
    }

    @Test fun moreIsTheThreeQuietOneLineRowsAtTheBottom() {
        val sheet = hubPageOptionLayout("notes", 0, showEditPages = true)
        assertEquals(listOf(HubOptionMoreRow.WhatATapDoes, HubOptionMoreRow.EditPages, HubOptionMoreRow.FullSettings),
            sheet.moreRows)
        val settings = hubPageOptionLayout("notes", 0, showEditPages = false)
        assertEquals(listOf(HubOptionMoreRow.WhatATapDoes, HubOptionMoreRow.FullSettings), settings.moreRows)
    }

    @Test fun steppersStopAtTheirBoundsAndKeepTheirStep() {
        val stride = HubOptionNumbers.Stride
        assertEquals(30, stride.clamp(1))
        assertEquals(150, stride.clamp(999))
        assertEquals(80, stride.up(75))
        assertEquals(70, stride.down(75))
        assertEquals(150, stride.up(145))
        assertEquals(30, stride.down(35))
        assertFalse(stride.canGoDown(30))
        assertFalse(stride.canGoUp(150))
        assertTrue(stride.canGoUp(145))
        assertTrue(stride.canGoDown(35))

        val goal = HubOptionNumbers.Goal
        assertEquals(StepOptions.MinGoal, goal.clamp(0))
        assertEquals(StepOptions.MaxGoal, goal.clamp(999_999))
        assertEquals(8_500, goal.up(8_000))
        assertEquals(7_500, goal.down(8_000))
        assertFalse(goal.canGoDown(StepOptions.MinGoal))
        assertFalse(goal.canGoUp(StepOptions.MaxGoal))
    }

    @Test fun theRhythmWindowKeepsItsThreeHourSpan() {
        val start = HubOptionNumbers.rhythmStart(24)
        assertEquals(0, start.min)
        assertEquals(21, start.max)
        assertEquals(0, start.down(0))
        assertEquals(1, start.up(0))

        val end = HubOptionNumbers.rhythmEnd(6)
        assertEquals(9, end.min)
        assertEquals(24, end.max)
        assertEquals(24, end.up(24))
        assertEquals(23, end.down(24))
        assertFalse(end.canGoUp(24))
        assertFalse(end.canGoDown(9))
    }

    @Test fun valuesCarryTheirUnitsOnTheRow() {
        assertEquals("0.75 m", HubOptionNumbers.strideText(75))
        assertEquals("8,000 steps", HubOptionNumbers.goalText(8_000))
        assertEquals("06:00", HubOptionNumbers.hourText(6))
        assertEquals("24:00", HubOptionNumbers.hourText(24))
    }

    @Test fun theHubSizeSegmentedChoiceSurvivesASaveAndReload() {
        HubSize.entries.forEach { size ->
            val config = HubConfig().copy(size = size)
            assertEquals(size, HubOptionChoice.sizeAt(HubOptionChoice.sizeIndex(config.size)))
            assertEquals(size, HubOptionChoice.sizeAfterReload(size.id))
        }
        assertEquals(HubSize.Standard, HubOptionChoice.sizeAfterReload(null))
        assertEquals(HubSize.Tall, HubOptionChoice.sizeAfterReload("tall"))
        assertEquals(HubSize.Standard, HubOptionChoice.sizeAfterReload("not-a-size"))
    }

    @Test fun theGoalSwitchAndStepPersistThroughTheExistingOptionText() {
        val goalOn = StepOptions(goal = 8_000)
        assertEquals(8_000, StepOptions.decode(goalOn.encode()).goal)
        val goalOff = StepOptions(goal = null)
        assertNull(StepOptions.decode(goalOff.encode()).goal)
        val custom = StepOptions(strideCm = 80, goal = 12_500)
        assertEquals(custom, StepOptions.decode(custom.encode()))
    }

    @Test fun theOldNoisyRowsAreGoneFromThePageOptionsSources() {
        val options = File("src/main/kotlin/com/brightblu/parc/HubPageOptions.kt").readText()
        listOf("First time here", "Shows:", "On Home:", "Remove page", "Move left", "Move right",
            "Use Move to reorder").forEach { assertFalse(it, options.contains(it)) }
        listOf("Show on Home", "Move earlier", "Move later", "Hub size", "SettingsSegmented", "Springs.sheet").forEach {
            assertTrue(it, options.contains(it))
        }

        val steps = File("src/main/kotlin/com/brightblu/parc/StepsPage.kt").readText()
        listOf("Shorter stride", "Longer stride", "Lower goal", "Higher goal").forEach {
            assertFalse(it, steps.contains(it))
        }
        assertTrue(steps.contains("HubOptionStepper"))
        assertTrue(steps.contains("HubOptionNumbers.strideText"))

        val rhythm = File("src/main/kotlin/com/brightblu/parc/RhythmPage.kt").readText()
        listOf("Start earlier", "Start later", "End earlier", "End later").forEach {
            assertFalse(it, rhythm.contains(it))
        }
        assertTrue(rhythm.contains("HubOptionStepper"))
    }
}
