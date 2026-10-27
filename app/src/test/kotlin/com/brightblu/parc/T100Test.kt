package com.brightblu.parc

import com.brightblu.parc.core.data.agenda.AgendaSettings
import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.feature.settings.ui.SettingKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** T100: Agenda settings row, summary text and backup. */
class T100Test {
    @Test fun agendaRowIsAPageInTheStatsSectionWithPlainDescription() {
        val spec = settingsRowSpec(SettingsRowId.Agenda)
        assertEquals(SettingsSectionId.Stats, spec.section)
        assertEquals(SettingKind.Page, spec.kind)
        assertEquals("Agenda", spec.title)
        assertTrue(spec.description.contains("calendars"))
        val rows = settingsRootRows("Midnight", false, true, false, true)
        val row = rows.single { it.id == SettingsRowId.Agenda }
        assertEquals("2 days", row.detail)
        assertEquals(SettingsSectionId.Stats, row.section)
        assertEquals(rows.indexOfFirst { it.id == SettingsRowId.HubPages } + 2, rows.indexOf(row))
        val custom = settingsRootRows("Midnight", false, true, false, true,
            agendaSummary = AgendaSettingsText.summary(AgendaSettings(1, setOf("Work"))))
        assertEquals("1 day · 1 left out", custom.single { it.id == SettingsRowId.Agenda }.detail)
        assertEquals(listOf(SettingsRowId.Agenda), filterSettingsRows(rows, "calendars").map { it.id })
    }

    @Test fun summaryText() {
        assertEquals("2 days", AgendaSettingsText.summary(AgendaSettings()))
        assertEquals("7 days · 2 left out", AgendaSettingsText.summary(AgendaSettings(7, setOf("A", "B"))))
        assertEquals("7 days", AgendaSettingsText.summary(AgendaSettings(99)))
        assertEquals("1 day", AgendaSettingsText.days(1))
    }

    @Test fun agendaKeysAreInTheBackupAndRoundTripThroughUiPrefs() = runTest {
        assertNotNull(BackupSpecs.uiPrefs.key("agenda_days"))
        assertNotNull(BackupSpecs.uiPrefs.key("agenda_excluded_calendars"))
        val settings = AgendaSettings(4, setOf("Holidays", "Name, with comma"))
        val source = FakeSharedPreferences()
        source.edit().putString("agenda_days", settings.encodeDays())
            .putString("agenda_excluded_calendars", settings.encodeExcluded()).commit()
        val values = SharedPreferencesStoreAccess(source).read(BackupSpecs.uiPrefs)
        assertEquals("4", values["agenda_days"])
        val target = FakeSharedPreferences()
        SharedPreferencesStoreAccess(target).write(BackupSpecs.uiPrefs, values)
        assertEquals(settings, AgendaSettings.decode(target.getString("agenda_days", null), target.getString("agenda_excluded_calendars", null)))
    }

    @Test fun hostileBackupValuesAreBoundedAndClamped() = runTest {
        val target = FakeSharedPreferences()
        SharedPreferencesStoreAccess(target).write(BackupSpecs.uiPrefs, mapOf(
            "agenda_days" to "999", "agenda_excluded_calendars" to "x".repeat(10_000)))
        // The oversize text is refused by the allow-list; the days value is clamped when read.
        assertNull(target.getString("agenda_excluded_calendars", null))
        assertEquals(7, AgendaSettings.decode(target.getString("agenda_days", null), null).days)
    }
}
