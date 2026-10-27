package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.skins.WallpaperLook
import com.brightblu.parc.core.data.skins.WallpaperMode
import com.brightblu.parc.feature.settings.ui.SettingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T96Test {
    @Test fun wallpaperSkinRowsAreChoicesInAppearanceAndShowStoredValues() {
        val rows = settingsRootRows("Wallpaper", false, true, false, true,
            wallpaperMode = WallpaperMode.Dark, wallpaperLook = WallpaperLook.Phosphor)
        val mode = rows.single { it.id == SettingsRowId.WallpaperSkinMode }
        val look = rows.single { it.id == SettingsRowId.WallpaperSkinLook }
        assertEquals(SettingKind.Choice, mode.kind)
        assertEquals(SettingKind.Choice, look.kind)
        assertEquals(SettingsSectionId.Appearance, mode.section)
        assertEquals("Dark", mode.detail)
        assertEquals("Phosphor", look.detail)
        assertTrue(mode.description.isNotBlank() && look.description.isNotBlank())
    }

    @Test fun theRowsAreHiddenWithoutDynamicColour() {
        val rows = settingsRootRows("Midnight", false, true, false, true)
        assertTrue(rows.none { it.id == SettingsRowId.WallpaperSkinMode || it.id == SettingsRowId.WallpaperSkinLook })
    }

    @Test fun newSettingsAreInTheBackupSpec() {
        assertNotNull(BackupSpecs.settings.key("wallpaper_skin_mode"))
        assertNotNull(BackupSpecs.settings.key("wallpaper_skin_look"))
    }
}
