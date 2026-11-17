package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T189: how the Sky page is registered, and what its presentation says. */
class T189Test {
    @Test fun theSkyPageIsInTheCatalogHiddenForOldAndNewUsersAndHasNoTiles() {
        assertTrue("sky" in HubCatalog.pages)
        assertTrue("sky" in HubCatalog.defaultHiddenPages)
        assertTrue("sky" in HubCatalog.newUserHiddenPages)
        assertTrue("sky" in HubCatalog.tilelessPages)
        assertTrue("sky" in HubCatalog.m13Pages)
        assertTrue(HubCatalog.tiles("sky").isEmpty())
        assertFalse("sky" in HubConfig().pages)
        assertNotNull(hubPageOptions["sky"])
        assertNotNull(hubTemplates["sky"])
        assertEquals("Sky", hubPageName("sky"))
    }

    @Test fun skySitsAfterRhythmAndAnOldSavedConfigGainsItHidden() {
        assertEquals(HubCatalog.pages.indexOf("rhythm") + 1, HubCatalog.pages.indexOf("sky"))
        val old = "clock:true::;system:true:battery:;network:true:traffic:;apps:true:new apps:;sound:true:media:"
        val config = HubConfig.decode(old)
        assertTrue("sky" in config.hiddenPages)
        assertFalse("sky" in config.pages)
        assertTrue("sky" in HubConfig.decode(config.encode()).hiddenPages)
        assertTrue("sky" in HubConfig.decode(HubConfig().showPage("sky", true).encode()).pages)
    }

    @Test fun theDemoHubHasASkyPage() {
        assertTrue("sky" in DemoHubPages)
    }

    @Test fun theBackupCarriesTheTypedPlace() {
        assertNotNull(BackupSpecs.hub.key("sky_place"))
    }

    @Test fun theWordsForTalkBackAndTheCells() {
        val now = Instant.parse("2025-06-21T10:00:00Z")
        val lisbon = SkyView.build(SkyPlace(38.72, -9.14, "Lisbon"), now, 3600)
        assertEquals("Lisbon", SkyPresentation.placeLabel(lisbon))
        assertEquals("38.72, -9.14", SkyPresentation.placeLabel(SkyView.build(SkyPlace(38.72, -9.14), now, 3600)))
        assertEquals("Set a place", SkyPresentation.placeLabel(SkyView.build(null, now, 3600)))
        assertTrue(SkyPresentation.arcDescription(lisbon).startsWith("Sun path today. Sunrise 06:1"))
        assertEquals("Sun path today. Set a place", SkyPresentation.arcDescription(SkyView.build(null, now, 3600)))
        assertEquals("Sun now 12° above the horizon", SkyPresentation.heightNow(12.2))
        assertEquals("Sun now 5° below the horizon", SkyPresentation.heightNow(-4.6))
        val tromso = SkyView.build(SkyPlace(69.65, 18.96, "Tromso"), now, 3600)
        assertTrue(SkyPresentation.arcDescription(tromso).startsWith("Sun path today. Sun stays up"))
        assertTrue(SkyPresentation.moonDescription(lisbon).endsWith("percent lit, waning") || SkyPresentation.moonDescription(lisbon).endsWith("percent lit, waxing"))
    }
}
