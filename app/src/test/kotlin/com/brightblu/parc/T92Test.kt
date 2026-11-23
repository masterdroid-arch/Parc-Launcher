package com.brightblu.parc

import com.brightblu.parc.feature.settings.ui.SettingKind
import org.junit.Assert.*
import org.junit.Test

class T92Test {
    @org.junit.Test fun hubPageOptionsCatalogIsCompleteForSettingsAndHome() {
        org.junit.Assert.assertEquals(HubCatalog.pages.toSet(), hubPageOptions.keys)
        org.junit.Assert.assertTrue(SettingsRegistry.byId.getValue("HubPages").info.contains("options"))
        assertTrue("time zone" in SettingsRegistry.byId.getValue("HubPages").words)
        assertTrue(hubPageOptions.getValue(TodayPage.Id).tap.contains("Long press"))
        hubPageOptions.values.forEach { org.junit.Assert.assertTrue(it.fullSettings in SettingsPage.entries) }
    }
    @Test fun sharedEditorsKeepEveryExistingDestination() {
        assertEquals(com.brightblu.parc.feature.settings.ui.EditorSpec.entries.toSet(), editorDestinations.keys)
        assertEquals(10, editorDestinations.values.distinct().size)
        editorDestinations.values.forEach { assertTrue(it in SettingsPage.entries) }
    }







    @Test fun nestedPagesReturnToTheirParent() {
        // T220: a nested editor returns to its page, a sub-page to its group, and a group to the root.
        assertEquals(SettingsScreen.Page(SettingsPage.Messages), settingsParent(SettingsPage.CommsApps))
        assertEquals(SettingsScreen.Page(SettingsPage.Messages), settingsParent(SettingsPage.QuickReplies))
        assertEquals(SettingsScreen.Group(SettingsGroup.NotificationsFocus), settingsParent(SettingsPage.Messages))
        assertEquals(SettingsScreen.Page(SettingsPage.HomeApps), settingsParent(SettingsPage.Folders))
        assertEquals(SettingsScreen.Page(SettingsPage.DataBuild), settingsParent(SettingsPage.WhatsNew))
        assertEquals(SettingsScreen.Page(SettingsPage.HubPages), settingsParent(SettingsPage.HubOptions))
        assertNull(SettingsPage.Root.group)
        SettingsGroup.entries.forEach { assertEquals(SettingsScreen.Root, settingsParent(SettingsScreen.Group(it))) }
    }
}
