package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T220: Pages & alerts is not another name for a flat Stats pages screen. T373: it is also the root's "Hub pages" group,
 * so from the root Back leaves it for the root; the Hubs row still returns to Hubs (see [settingsBackFor]).
 */
class HubPagesSettingsStructureTest {
    @Test fun theHubPagesRowOpensTheNestedPagesAndAlertsPage() {
        assertEquals(SettingsPage.HubPages, SettingsRegistry.byId.getValue("HubPages").opens)
        assertEquals(SettingsPage.HubPages, SettingsRegistry.byId.getValue("HubPages").opens)
        assertEquals(SettingsScreen.Root, settingsParent(SettingsPage.HubPages))
        assertEquals(SettingsScreen.Group(SettingsGroup.Appearance), settingsParent(SettingsPage.Hubs))
    }

    @Test fun backFromPagesAndAlertsGoesWhereTheUserCameFrom() {
        val hubs = SettingsScreen.Page(SettingsPage.Hubs)
        val pages = SettingsScreen.Page(SettingsPage.HubPages)
        assertEquals(hubs, settingsBackFor(pages, SettingsPage.HubPages to hubs))
        assertEquals(SettingsScreen.Root, settingsBackFor(pages, null))
        // A remembered origin never changes the Back of another page.
        assertEquals(SettingsScreen.Page(SettingsPage.HubPages), settingsBackFor(SettingsScreen.Page(SettingsPage.HubOptions), SettingsPage.HubPages to hubs))
    }

    @Test fun backFromAPagesOptionsGoesToTheListAndFromTheListToHubs() {
        assertEquals(SettingsScreen.Page(SettingsPage.HubPages), settingsParent(SettingsPage.HubOptions))
        assertEquals(SettingsScreen.Root, settingsParent(SettingsPage.HubPages))
        assertEquals(SettingsScreen.Group(SettingsGroup.Appearance), settingsParent(SettingsPage.Hubs))
    }

    @Test fun settingsSearchStillFindsTheHubPagesEntryAndItLandsOnTheNestedPage() {
        val hit = SettingsRegistry.search("hub pages").firstOrNull { it.id == "HubPages" }
        assertTrue(hit != null)
        assertEquals(SettingsPage.HubPages, hit!!.page)
        assertEquals(SettingsPage.HubPages, hit.opens)
        assertTrue(SettingsRegistry.search("hub pages").any { it.page == SettingsPage.HubPages })
        assertEquals("Hub pages · Hub pages", hit.page.breadcrumb)
    }
}
