package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T220: Pages & alerts is a nested page under Appearance > Hubs, not another name for a flat Stats pages screen. */
class HubPagesSettingsStructureTest {
    @Test fun theHubPagesRowOpensTheNestedPagesAndAlertsPage() {
        assertEquals(SettingsPage.HubPages, SettingsRegistry.byId.getValue("HubPages").opens)
        assertEquals(SettingsPage.HubPages, SettingsRegistry.byId.getValue("HubPages").opens)
        assertEquals(SettingsScreen.Page(SettingsPage.Hubs), settingsParent(SettingsPage.HubPages))
        assertEquals(SettingsScreen.Group(SettingsGroup.Appearance), settingsParent(SettingsPage.Hubs))
    }

    @Test fun backFromAPagesOptionsGoesToTheListAndFromTheListToHubs() {
        assertEquals(SettingsScreen.Page(SettingsPage.HubPages), settingsParent(SettingsPage.HubOptions))
        assertEquals(SettingsScreen.Page(SettingsPage.Hubs), settingsParent(SettingsPage.HubPages))
        assertEquals(SettingsScreen.Group(SettingsGroup.Appearance), settingsParent(SettingsPage.Hubs))
    }

    @Test fun settingsSearchStillFindsTheHubPagesEntryAndItLandsOnTheNestedPage() {
        val hit = SettingsRegistry.search("hub pages").firstOrNull { it.id == "HubPages" }
        assertTrue(hit != null)
        assertEquals(SettingsPage.Hubs, hit!!.page)
        assertEquals(SettingsPage.HubPages, hit.opens)
        assertTrue(SettingsRegistry.search("hub pages").any { it.page == SettingsPage.Hubs })
        assertEquals("Appearance · Hubs", hit.page.breadcrumb)
    }
}
