package com.brightblu.parc

import com.brightblu.parc.feature.settings.ui.SettingKind
import org.junit.Assert.*
import org.junit.Test

class T92Test {
    @org.junit.Test fun hubPageOptionsCatalogIsCompleteForSettingsAndHome() {
        org.junit.Assert.assertEquals(HubCatalog.pages.toSet(), hubPageOptions.keys)
        org.junit.Assert.assertTrue(SettingsRegistry.byId.getValue("HubPages").help.contains("options"))
        assertTrue("time zone" in SettingsRegistry.byId.getValue("HubPages").words)
        assertTrue(hubPageOptions.getValue("clock").tap.contains("Long press"))
        hubPageOptions.values.forEach { org.junit.Assert.assertTrue(it.fullSettings in SettingsPage.entries) }
    }
    @Test fun sharedEditorsKeepEveryExistingDestination() {
        assertEquals(com.brightblu.parc.feature.settings.ui.EditorSpec.entries.toSet(), editorDestinations.keys)
        assertEquals(10, editorDestinations.values.distinct().size)
        editorDestinations.values.forEach { assertTrue(it in SettingsPage.entries) }
    }







    @Test fun nestedPagesReturnToTheirParent() {
        // T177: an editor returns to the page whose row opened it; the seven pages return to the root.
        assertEquals(SettingsPage.Comms, settingsParent(SettingsPage.CommsApps))
        assertEquals(SettingsPage.Comms, settingsParent(SettingsPage.QuickReplies))
        assertEquals(SettingsPage.SearchNotifications, settingsParent(SettingsPage.Comms))
        assertEquals(SettingsPage.Layout, settingsParent(SettingsPage.Folders))
        assertEquals(SettingsPage.BackupAbout, settingsParent(SettingsPage.WhatsNew))
        assertEquals(SettingsPage.StatsPages, settingsParent(SettingsPage.HubOptions))
        SettingsHome.entries.forEach { assertEquals(SettingsPage.Root, settingsParent(it.settingsPage)) }
    }
}
