package com.brightblu.parc

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test

class T114Test {
    @Test fun everyLegacyIdentityHasAHome() {
        assertEquals(48, SettingsRowId.entries.size)
        SettingsRowId.entries.forEach {
            assertTrue(settingsRowSpec(it).description.isNotBlank())
            assertTrue(settingsRowSpec(it).section in SettingsSectionId.entries)
        }
        assertEquals(9, SettingsSectionId.entries.size)
        assertEquals(SettingsSectionId.Backup, settingsRowSpec(SettingsRowId.WallpaperImageBackup).section)
    }
    @Test fun oldReadabilityKeysMigrateForEveryCombinationAndBackground() {
        for (style in WallpaperStyle.entries) for (a in listOf(false, true)) for (b in listOf(false, true)) {
            val old = """{"wallpaper":"$style","wallpaperReadability":$a,"imageReadabilityScrim":$b}"""
            val next = LookJson.decode(old)
            val expected = when (style) { WallpaperStyle.Image -> b; WallpaperStyle.System -> a; else -> a || b }
            assertEquals(expected, next.keepTextReadable)
            assertEquals(expected, next.wallpaperReadability)
            assertEquals(expected, next.imageReadabilityScrim)
            assertEquals(next, LookJson.decode(LookJson.encode(next)))
        }
        assertEquals(true, LookJson.decode("{}").keepTextReadable)
    }
    @Test fun explicitNewValueWinsOnRestoringAMixedBackup() {
        val next = LookJson.decode("""{"keepTextReadable":false,"wallpaperReadability":true,"imageReadabilityScrim":true}""")
        assertFalse(next.wallpaperReadability)
        assertFalse(next.imageReadabilityScrim)
    }
    @Test fun dependentsHideUntilTheirParentIsEnabled() {
        for (style in WallpaperStyle.entries) {
            val look = LookSettings(wallpaper = style)
            assertEquals(style == WallpaperStyle.Image || style == WallpaperStyle.System,
                settingsRowVisible(SettingsRowId.WallpaperDim, look, false, true, true, true))
            assertFalse(settingsRowVisible(SettingsRowId.ImageReadabilityScrim, look, true, true, true, true))
        }
        for (id in listOf(SettingsRowId.WallpaperSkinMode, SettingsRowId.WallpaperSkinLook)) {
            assertFalse(settingsRowVisible(id, LookSettings(), false, true, true, true))
            assertTrue(settingsRowVisible(id, LookSettings(), true, true, true, true))
        }
        for (id in listOf(SettingsRowId.HeaderTime, SettingsRowId.HubPages, SettingsRowId.Agenda, SettingsRowId.StatsAlerts))
            assertFalse(settingsRowVisible(id, LookSettings(), true, false, true, true))
        assertFalse(settingsRowVisible(SettingsRowId.MenuNotificationText, LookSettings(), true, true, true, false))
        assertFalse(settingsRowVisible(SettingsRowId.BadgesInAllApps, LookSettings(), true, true, false, true))
    }
    @Test fun searchAliasTargetsTheSingleReadabilityControl() {
        assertEquals(SettingsRowId.WallpaperReadability, settingsJump(SettingsRowId.ImageReadabilityScrim))
        SettingsRowId.entries.filter { it != SettingsRowId.ImageReadabilityScrim }.forEach { assertEquals(it, settingsJump(it)) }
        val rows = settingsRootRows("Wallpaper", false, true, false, true,
            look = LookSettings(wallpaper = WallpaperStyle.System), wallpaperMode = com.brightblu.parc.core.data.skins.WallpaperMode.System)
        assertEquals(listOf(SettingsRowId.WallpaperReadability), filterSettingsRows(rows, "keep text readable").map { it.id })
    }
}
