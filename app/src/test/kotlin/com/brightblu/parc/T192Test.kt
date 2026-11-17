package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import org.junit.Assert.*
import org.junit.Test

class T192Test {
    @Test fun todayIsFirstAndVisibleForNewAndExistingUsers() {
        assertEquals(TodayPage.Id, HubCatalog.pages.first())
        // T263: Clock is now part of Today and Power is part of Device.
        assertEquals(listOf(TodayPage.Id, DevicePage.Id, "notes"), HubConfig().pages)
        val old = HubConfig.decode("clock:true::;power:false::;notes:true::")
        assertTrue("today" in old.pages)
        assertFalse(DevicePage.Id in old.pages)
        assertTrue("notes" in old.pages)
        assertFalse("today" in HubCatalog.defaultHiddenPages)
        assertFalse("today" in HubCatalog.newUserHiddenPages)
        assertEquals(old.pages, HubConfig.decode(old.encode()).pages)
        val hidden = old.showPage("today", false)
        assertFalse("today" in HubConfig.decode(hidden.encode()).pages)
    }
    @Test fun registeredAsTilelessWithDemoTemplateAndOptions() {
        assertTrue("today" in HubCatalog.tilelessPages)
        assertTrue("today" in HubCatalog.m13Pages)
        assertTrue(HubCatalog.tiles("today").isEmpty())
        assertEquals(listOf(TodayPage.Id, DevicePage.Id), DemoHubPages.take(2))
        assertNotNull(hubTemplates["today"])
        assertNotNull(hubPageOptions["today"])
        assertEquals("Today", hubPageName("today"))
    }
    @Test fun oldEmptyOrInvalidOptionsDefaultOnAndOffRoundTripsInBackupKey() {
        listOf(null, "", "old", "TRUE").forEach { assertEquals(TodayOptions(), TodayOptions.decode(it)) }
        for (enabled in listOf(true, false)) {
            val options = TodayOptions(enabled)
            assertEquals(options, TodayOptions.decode(options.encode()))
        }
        assertEquals(32, BackupSpecs.hub.keys.single { it.name == TodayOptions.Key }.maxLength)
    }
}
