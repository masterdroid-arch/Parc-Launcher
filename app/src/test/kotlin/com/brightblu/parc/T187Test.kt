package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.ledger.LedgerPeriod
import com.brightblu.parc.core.data.steps.StepOptions
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T187: how the Steps page is registered, and what its presentation says. */
class T187Test {
    private val today = LocalDate.of(2026, 11, 10)

    @Test fun theStepsPageIsInTheCatalogHiddenForOldAndNewUsersAndHasNoTiles() {
        assertTrue("steps" in HubCatalog.pages)
        assertTrue("steps" in HubCatalog.defaultHiddenPages)
        assertTrue("steps" in HubCatalog.newUserHiddenPages)
        assertTrue("steps" in HubCatalog.tilelessPages)
        assertTrue(HubCatalog.tiles("steps").isEmpty())
        assertFalse("steps" in HubConfig().pages)
        assertNotNull(hubPageOptions["steps"])
        assertNotNull(hubTemplates["steps"])
        assertEquals("Steps", hubPageName("steps"))
    }

    @Test fun anOldSavedConfigGainsStepsHiddenWithoutChangingItsOtherPages() {
        val old = "clock:true::;system:true:battery:;network:true:traffic:;apps:true:new apps:;sound:true:media:"
        val config = HubConfig.decode(old)
        assertTrue("steps" in config.hiddenPages)
        assertFalse("steps" in config.pages)
        assertTrue(config.pages.containsAll(listOf(TodayPage.Id, DevicePage.Id, "apps", ControlsPage.Id)))
        assertEquals(config.orderedPages.indexOf("apps") + 1, config.orderedPages.indexOf("steps"))
        assertTrue("steps" in HubConfig.decode(config.encode()).hiddenPages)
    }

    @Test fun stepsCanBeShownAndSurvivesEncoding() {
        val shown = HubConfig().showPage("steps", true)
        assertTrue("steps" in HubConfig.decode(shown.encode()).pages)
    }

    @Test fun theDemoHubHasAStepsPage() {
        assertTrue("steps" in DemoHubPages)
    }

    @Test fun stepsRemembersItsOwnPeriodAndOldStoresReadAsToday() {
        assertTrue("steps" in HubPeriods.pages)
        assertEquals(LedgerPeriod.Month, HubPeriods.decode { if (it == "period_steps") "30d" else null }["steps"])
        assertEquals(LedgerPeriod.Today, HubPeriods.decode { null }["steps"])
        assertEquals(LedgerPeriod.Today, HubPeriods.decode { "from the future" }["steps"])
    }

    @Test fun theBackupCarriesStepOptionsAndThePeriod() {
        val keys = BackupSpecs.hub
        assertNotNull(keys.key("steps_options"))
        assertNotNull(keys.key("period_steps"))
        // The counter baseline is one phone's sensor state and is not carried.
        assertEquals(null, keys.key("last_reading_v1"))
    }

    @Test fun presentationCombinesLedgerPendingAndOptions() {
        val ledger = DayLedger.Empty.withDay(LedgerDay(today, steps = 4_000))
            .withDay(LedgerDay(today.minusDays(1), steps = 6_000))
        val model = StepsPresentation.model(ledger, com.brightblu.parc.core.data.steps.StepPending(today, 250), StepOptions(80, 5_000), LedgerPeriod.Week, today)
        assertEquals(4_250, model.today)
        assertEquals(10_250L, model.summary.total)
        assertEquals(7, model.bars.size)
        assertEquals("Steps today 4,250, about 3.4 km, 85 percent of the goal of 5,000", StepsPresentation.heroDescription(model))
    }

    @Test fun presentationWithoutDataSaysSo() {
        val model = StepsPresentation.model(DayLedger.Empty, null, StepOptions(), LedgerPeriod.Today, today)
        assertEquals(null, model.today)
        assertEquals("No step data yet", StepsPresentation.heroDescription(model))
        assertTrue(StepsPresentation.barsDescription(model.bars).contains("no data"))
    }

    @Test fun goalOffLeavesTheGoalOutOfTheDescription() {
        val ledger = DayLedger.Empty.withDay(LedgerDay(today, steps = 1_000))
        val model = StepsPresentation.model(ledger, null, StepOptions(75, null), LedgerPeriod.Today, today)
        assertEquals("Steps today 1,000, about 750 m", StepsPresentation.heroDescription(model))
    }
}
