package com.brightblu.parc

import com.brightblu.parc.core.data.agenda.AgendaSettings
import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.feature.settings.ui.SettingKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** T100: Agenda settings row, summary text and backup. */
class T100Test {

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
