package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.feature.settings.ui.SettingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T102: the three search-source switches in the settings catalog and the backup. */
class T102Test {
    private val ids = listOf(SettingsRowId.SearchPhoneSettings, SettingsRowId.SearchFiles, SettingsRowId.SearchEvents)

    @Test fun eachSourceIsAToggleInTheSearchSectionWithPlainText() {
        ids.forEach { id ->
            val spec = settingsRowSpec(id)
            assertEquals(SettingsSectionId.Search, spec.section)
            assertEquals(SettingKind.Toggle, spec.kind)
            assertTrue(spec.title!!.startsWith("Search "))
            assertFalse(spec.description.contains('—'))
        }
        assertTrue(settingsRowSpec(SettingsRowId.SearchFiles).description.contains("permission"))
        assertTrue(settingsRowSpec(SettingsRowId.SearchEvents).description.contains("60 days"))
    }

    @Test fun rootRowsCarryTheRealSwitchState() {
        val defaults = settingsRootRows("Midnight", false, true, false, true)
        ids.forEach { id -> assertEquals(true, defaults.single { it.id == id }.checked) }
        val custom = settingsRootRows("Midnight", false, true, false, true,
            searchPhoneSettings = false, searchFiles = true, searchEvents = false)
        assertEquals(false, custom.single { it.id == SettingsRowId.SearchPhoneSettings }.checked)
        assertEquals("Off", custom.single { it.id == SettingsRowId.SearchEvents }.detail)
        assertEquals("On", custom.single { it.id == SettingsRowId.SearchFiles }.detail)
        val search = defaults.indexOfFirst { it.id == SettingsRowId.Search }
        assertEquals(ids, defaults.subList(search + 1, search + 4).map { it.id })
    }

    @Test fun theSwitchesAreFindableInSettingsSearch() {
        val rows = settingsRootRows("Midnight", false, true, false, true)
        assertTrue(filterSettingsRows(rows, "photos").any { it.id == SettingsRowId.SearchFiles })
        assertTrue(filterSettingsRows(rows, "calendar events").any { it.id == SettingsRowId.SearchEvents })
    }

    @Test fun theSourceSwitchesTravelInTheBackupButThePromptMarkerDoesNot() {
        listOf("source_settings", "source_files", "source_events").forEach { assertNotNull(BackupSpecs.search.key(it)) }
        assertNull(BackupSpecs.search.key("extras_prompt_done"))
        assertNull(BackupSpecs.search.key("recent_searches"))
    }
}
