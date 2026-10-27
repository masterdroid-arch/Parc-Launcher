package com.brightblu.parc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.brightblu.parc.core.data.backup.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class T111Test {
    @Test fun migrationPreservesOldLookAndAddsDefaults() {
        val old = LookJson.decode("""{"iconSizeDp":48,"wallpaper":"System","wallpaperDimPercent":60}""")
        assertEquals(48, old.iconSizeDp)
        assertEquals(60, old.wallpaperDimPercent)
        assertEquals(85, old.panelOpacityPercent)
        assertTrue(old.wallpaperReadability)
        assertEquals(old, LookJson.decode(LookJson.encode(old)))
    }
    @Test fun luminanceAndColourEstimateUseLinearSrgb() {
        assertEquals(0.0, WallpaperReadability.luminance(0xff000000.toInt()), .00001)
        assertEquals(1.0, WallpaperReadability.luminance(0xffffffff.toInt()), .00001)
        assertEquals(.2126, WallpaperReadability.luminance(0xffff0000.toInt()), .00001)
        assertNull(WallpaperReadability.estimate(emptyList(), false))
        assertEquals(.5, WallpaperReadability.estimate(listOf(0xff000000.toInt(), 0xffffffff.toInt()), false)!!, .00001)
        assertEquals(.5, WallpaperReadability.estimate(listOf(0xff000000.toInt()), true)!!, .00001)
    }
    @Test fun everySkinKeepsPrimaryAndSecondaryContrastAcrossWallpaperExtremes() {
        val tones = List(13) { i -> com.brightblu.parc.core.data.skins.ColorMath.mix(0xffffffffL, 0xff000000L, i / 12.0) }
        val palette = com.brightblu.parc.core.data.skins.DynamicPalette.orNull(tones, tones, tones, tones)!!
        val skins = listOf(Skin.Midnight, FieldAtlas.Dark, FieldAtlas.Light, Phosphor.Skin) +
            listOf(false, true).map { com.brightblu.parc.core.data.skins.WallpaperSkin.build(palette, it, Skin.Midnight) }
        for (skin in skins) {
            val surface = skin.colors.surface.toInt()
            val texts = listOf(skin.colors.textPrimary.toInt(), skin.colors.textSecondary.toInt())
            for (opacity in Metrics.panelOpacitySteps) {
                val alpha = WallpaperReadability.safeAlpha(surface, texts, opacity / 100f)
                for (grey in 0..255) {
                    val background = 0xff000000.toInt() or (grey shl 16) or (grey shl 8) or grey
                    texts.forEach { assertTrue("${skin.id}: $opacity $grey", WallpaperReadability.contrast(it,
                        WallpaperReadability.composite(surface, background, alpha)) >= 4.5) }
                }
            }
        }
    }
    @Test fun controlsHaveRealKindsAndState() {
        val look = LookSettings(wallpaper = WallpaperStyle.System, panelOpacityPercent = 40, wallpaperReadability = false)
        val rows = settingsRootRows("Midnight", false, true, false, true, look = look)
        assertEquals("40 %", rows.single { it.id == SettingsRowId.PanelOpacity }.detail)
        assertEquals(false, rows.single { it.id == SettingsRowId.WallpaperReadability }.checked)
        assertEquals(com.brightblu.parc.feature.settings.ui.SettingKind.Choice, settingsRowSpec(SettingsRowId.PanelOpacity).kind)
        assertEquals(com.brightblu.parc.feature.settings.ui.SettingKind.Toggle, settingsRowSpec(SettingsRowId.WallpaperReadability).kind)
    }
    @Test fun backupRoundTripIncludesBothSettings() = runTest {
        class Store : DataStore<Preferences> {
            val state = MutableStateFlow<Preferences>(emptyPreferences())
            override val data: Flow<Preferences> = state
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(state.value).also { state.value = it }
        }
        assertEquals(BackupSpecs.look, AppBackup.LookSpec)
        val source = Store()
        val key = stringPreferencesKey("look_v1")
        val expected = LookSettings(panelOpacityPercent = 65, wallpaperReadability = false)
        source.updateData { it.toMutablePreferences().apply { this[key] = LookJson.encode(expected) } }
        val saved = PreferencesStoreAccess(source).read(BackupSpecs.look)
        val target = Store()
        PreferencesStoreAccess(target).write(BackupSpecs.look, saved)
        assertEquals(LookJson.migrate(expected), LookJson.decode(target.data.first()[key]!!))
    }
}
