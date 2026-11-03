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


    @Test fun newSettingsAreInTheBackupSpec() {
        assertNotNull(BackupSpecs.settings.key("wallpaper_skin_mode"))
        assertNotNull(BackupSpecs.settings.key("wallpaper_skin_look"))
    }
}
