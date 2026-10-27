package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.feature.settings.ui.EditorHintStoreName
import com.brightblu.parc.feature.settings.ui.EditorSpec
import org.junit.Assert.*
import org.junit.Test

class T115Test {
    @Test fun educationStateIsNotInBackups() {
        assertNull(BackupSpecs.byId(EditorHintStoreName))
        EditorSpec.entries.forEach { editor ->
            assertTrue(BackupSpecs.all.none { store -> store.keys.any { it.name == editor.name } })
        }
    }
    @Test fun everyEditorHasItsExistingDestination() {
        assertEquals(10, EditorSpec.entries.size)
        assertEquals(setOf(SettingsPage.MainApps, SettingsPage.Folders, SettingsPage.Hidden,
            SettingsPage.Rotation, SettingsPage.HubPages, SettingsPage.Agenda, SettingsPage.StatsAlerts,
            SettingsPage.SkinStudio, SettingsPage.CommsApps, SettingsPage.QuickReplies), editorDestinations.values.toSet())
    }
}
