package com.brightblu.parc.feature.search.extras

import com.brightblu.parc.feature.search.SearchRow
import com.brightblu.parc.feature.search.SearchSheetItems
import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS8 (T133): Parc's own settings pages in search. */
class ParcSettingsSearchTest {
    private val entries = listOf(
        ParcSettingEntry("root", "Parc settings", listOf("Settings", "Preferences")),
        ParcSettingEntry("page:Wallpaper", "Wallpaper", listOf("Choose what sits behind Parc")),
        ParcSettingEntry("page:LockedFolders", "Locked folders", listOf("Hide the apps in chosen folders")),
        ParcSettingEntry("page:HubPages", "Hub pages", listOf("Add, Remove or Move pages")),
    )

    @Test fun aTitleMatchBeatsADescriptionMatch() {
        val hits = ParcSettingsRanking.rank("wallpaper", entries)
        assertEquals("page:Wallpaper", hits.first().key)
    }

    @Test fun settingsFindsParcSettings() {
        assertEquals("root", ParcSettingsRanking.rank("settings", entries).first().key)
        assertEquals("root", ParcSettingsRanking.rank("parc set", entries).first().key)
    }

    @Test fun shortOrUnmatchedQueriesFindNothingAndTheCapHolds() {
        assertTrue(ParcSettingsRanking.rank("w", entries).isEmpty())
        assertTrue(ParcSettingsRanking.rank("zzzz", entries).isEmpty())
        assertTrue(ParcSettingsRanking.rank("p", entries).size <= ParcSettingsRanking.DefaultLimit)
    }

    @Test fun descriptionsGetNoTypoAllowance() {
        // "behimd" is one slip from "behind" in a description: not a match.
        assertTrue(ParcSettingsRanking.rank("behimd", entries).isEmpty())
        // A slip in a title still matches.
        assertEquals("page:Wallpaper", ParcSettingsRanking.rank("walpaper", entries).first().key)
    }

    @Test fun appsStayNextToTheFieldThenParcSettingsThenPhoneSettings() {
        val app = SearchRow(AppId("p.Settings/Main@0"), "Settings")
        val parc = ParcSettingsRanking.rank("settings", entries)
        val phone = ExtraResults(phoneSettings = listOf(PhoneSettingHit("Settings", "android.settings.SETTINGS", 1.0)))
        val items = SearchSheetItems.build("settings", emptyList(), null, listOf(app), emptyList(), phone, parc)
        assertEquals(listOf(app.appId.value, "parc-setting-root", "header-${ParcSettingsRanking.Header}",
            "extra-setting-android.settings.SETTINGS", "header-${ExtraText.SettingsHeader}"), items.map { it.key })
    }

    @Test fun withoutParcSettingsTheListIsUnchanged() {
        val app = SearchRow(AppId("p.Maps/Main@0"), "Maps")
        val before = SearchSheetItems.build("ma", emptyList(), null, listOf(app), emptyList(), ExtraResults.Empty)
        val after = SearchSheetItems.build("ma", emptyList(), null, listOf(app), emptyList(), ExtraResults.Empty, emptyList())
        assertEquals(before, after)
    }
}
