package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T190: how the Year page is registered. */
class T190Test {
    @Test fun theYearPageIsInTheCatalogHiddenForOldAndNewUsersAndHasNoTiles() {
        assertTrue("year" in HubCatalog.pages)
        assertTrue("year" in HubCatalog.defaultHiddenPages)
        assertTrue("year" in HubCatalog.newUserHiddenPages)
        assertTrue("year" in HubCatalog.tilelessPages)
        assertTrue("year" in HubCatalog.m13Pages)
        assertTrue(HubCatalog.tiles("year").isEmpty())
        assertFalse("year" in HubConfig().pages)
        assertNotNull(hubPageOptions["year"])
        assertNotNull(hubTemplates["year"])
        assertEquals("Year", hubPageName("year"))
    }

    @Test fun yearSitsAfterSkyAndAnOldSavedConfigGainsItHidden() {
        assertEquals(HubCatalog.pages.indexOf("sky") + 1, HubCatalog.pages.indexOf("year"))
        val old = "clock:true::;system:true:battery:;network:true:traffic:;apps:true:new apps:;sound:true:media:"
        val config = HubConfig.decode(old)
        assertTrue("year" in config.hiddenPages)
        assertFalse("year" in config.pages)
        assertTrue("year" in HubConfig.decode(HubConfig().showPage("year", true).encode()).pages)
    }

    @Test fun theDemoHubHasAYearPage() {
        assertTrue("year" in DemoHubPages)
    }

    @Test fun theBackupCarriesTheCountdownsAndTheColourChoice() {
        val countdowns = BackupSpecs.hub.key("year_countdowns")
        assertNotNull(countdowns)
        assertNotNull(BackupSpecs.hub.key("year_metric"))
        // The longest stored text (50 names of 40 characters) must fit the backup key's limit.
        val longest = Countdowns.encode((1..Countdowns.MaxCount).map { Countdown("x".repeat(Countdowns.MaxNameLength), java.time.LocalDate.of(2026, 12, 31), true) })
        assertTrue(longest.length <= countdowns!!.maxLength)
    }
}
