package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T197: how the Records page is registered. */
class T197Test {
    @Test fun theRecordsPageIsInTheCatalogHiddenForOldAndNewUsersAndHasNoTiles() {
        assertTrue("records" in HubCatalog.pages)
        assertTrue("records" in HubCatalog.defaultHiddenPages)
        assertTrue("records" in HubCatalog.newUserHiddenPages)
        assertTrue("records" in HubCatalog.tilelessPages)
        assertTrue("records" in HubCatalog.m13Pages)
        assertTrue(HubCatalog.tiles("records").isEmpty())
        assertFalse("records" in HubConfig().pages)
        assertNotNull(hubPageOptions["records"])
        assertNotNull(hubTemplates["records"])
        assertEquals("Records", hubPageName("records"))
    }

    @Test fun recordsSitsAfterYearAndAnOldSavedConfigGainsItHidden() {
        assertEquals(HubCatalog.pages.indexOf("year") + 1, HubCatalog.pages.indexOf("records"))
        val old = "clock:true::;system:true:battery:;network:true:traffic:;apps:true:new apps:;sound:true:media:"
        val config = HubConfig.decode(old)
        assertTrue("records" in config.hiddenPages)
        assertFalse("records" in config.pages)
        assertTrue("records" in HubConfig.decode(HubConfig().showPage("records", true).encode()).pages)
    }

    @Test fun theDemoHubHasARecordsPage() {
        assertTrue("records" in DemoHubPages)
    }
}
