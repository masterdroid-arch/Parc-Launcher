package com.brightblu.parc.core.data.skins

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.ParcSettings
import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.PreferencesStoreAccess
import com.brightblu.parc.model.Skin
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T96: token mapping from a fake palette, contrast repair, missing palette, persistence and backup. */
class WallpaperSkinTest {

    /** 13 shades from near white (shade 0) to black (shade 1000) through [mid]. */
    private fun ramp(mid: Long): List<Long> = List(13) { i ->
        val t = i / 12.0
        if (t < 0.5) ColorMath.mix(0xFFFFFFFFL, mid, t * 2) else ColorMath.mix(mid, 0xFF000000L, (t - 0.5) * 2)
    }

    private val palette = DynamicPalette.orNull(ramp(0xFF3A7BD5), ramp(0xFF6B8E7F), ramp(0xFF7A7A85), ramp(0xFF7D7F8C))!!

    @Test
    fun `dark variant maps tokens from the dark end of the palette`() {
        val c = WallpaperSkin.colors(palette, dark = true)
        assertEquals(palette.neutral1[12], c.bg)
        assertEquals(palette.neutral2[10], c.lineBase)
        assertEquals(palette.neutral2[8], c.lineActive)
        assertTrue(ColorMath.luminance(c.bg) < 0.02)
        assertTrue(ColorMath.luminance(c.textPrimary) > ColorMath.luminance(c.bg))
        assertEquals(0x47L, c.glow ushr 24)
        assertEquals(c.accent and 0xFFFFFFL, c.glow and 0xFFFFFFL)
        assertEquals(0xB8L, c.scrim ushr 24)
    }

    @Test
    fun `light variant maps tokens from the light end of the palette`() {
        val c = WallpaperSkin.colors(palette, dark = false)
        assertEquals(palette.neutral1[1], c.bg)
        assertEquals(palette.neutral1[2], c.surface)
        assertEquals(palette.neutral2[5], c.lineBase)
        assertTrue(ColorMath.luminance(c.bg) > 0.8)
        assertTrue(ColorMath.luminance(c.textPrimary) < ColorMath.luminance(c.bg))
    }

    @Test
    fun `every text like token reaches 4_5 to 1 on background and panels in both variants`() {
        for (dark in listOf(true, false)) {
            val c = WallpaperSkin.colors(palette, dark)
            for (fg in listOf(c.textPrimary, c.textSecondary, c.accent, c.accent2, c.pulse, c.danger, c.warn)) {
                assertTrue(ColorMath.contrast(fg, c.bg) >= ColorMath.TextContrast)
                assertTrue(ColorMath.contrast(fg, c.surface) >= ColorMath.TextContrast)
            }
        }
    }

    @Test
    fun `a low contrast palette is repaired`() {
        // Accent and secondary neutral tones all sit close to the background.
        val dim = DynamicPalette.orNull(ramp(0xFF101820), ramp(0xFF101820), ramp(0xFF404048), ramp(0xFF202028))!!
        val raw = dim.accent1[4]
        assertTrue(ColorMath.contrast(raw, dim.neutral1[12]) < ColorMath.TextContrast)
        val c = WallpaperSkin.colors(dim, dark = true)
        assertTrue(ColorMath.contrast(c.accent, c.bg) >= ColorMath.TextContrast)
        assertTrue(ColorMath.contrast(c.textSecondary, c.surface) >= ColorMath.TextContrast)
        val light = WallpaperSkin.colors(dim, dark = false)
        assertTrue(ColorMath.contrast(light.accent, light.bg) >= ColorMath.TextContrast)
    }

    @Test
    fun `ensureContrast leaves a passing colour alone and ends at the pure target when it must`() {
        assertEquals(0xFFFFFFFFL, ColorMath.ensureContrast(0xFFFFFFFFL, listOf(0xFF000000L), 4.5, 0xFFFFFFFFL))
        val grey = 0xFF777777L
        assertEquals(0xFFFFFFFFL, ColorMath.ensureContrast(grey, listOf(0xFF777777L), 21.0, 0xFFFFFFFFL))
    }

    @Test
    fun `palette needs all 13 shades in every tone`() {
        assertNull(DynamicPalette.orNull(ramp(1).take(12), ramp(1), ramp(1), ramp(1)))
        assertNotNull(DynamicPalette.orNull(ramp(1), ramp(1), ramp(1), ramp(1)))
    }

    @Test
    fun `build keeps the look of the base skin and replaces only colours id and name`() {
        val base = SkinCatalogLoader.parse(listOf(
            SkinAsset("midnight.json", File("src/main/assets/skins/midnight.json").readText()),
            SkinAsset("phosphor.json", File("src/main/assets/skins/phosphor.json").readText()),
        )).skins
        val skin = WallpaperSkin.create(DynamicEnvironment(palette, systemNight = true), WallpaperMode.System, WallpaperLook.Phosphor, base)!!
        val phosphor = base.single { it.id == "phosphor" }
        assertEquals(WallpaperSkin.Id, skin.id)
        assertEquals(WallpaperSkin.Name, skin.name)
        assertEquals(phosphor.style, skin.style)
        assertEquals(phosphor.shape, skin.shape)
        assertEquals(WallpaperSkin.colors(palette, true), skin.colors)
    }

    @Test
    fun `missing look source falls back to Midnight`() {
        assertEquals(Skin.Midnight, WallpaperSkin.baseFor(WallpaperLook.FieldAtlas, listOf(Skin.Midnight)))
        assertEquals(Skin.Midnight, WallpaperSkin.baseFor(WallpaperLook.Phosphor, emptyList()))
    }

    @Test
    fun `mode chooses the variant and system follows night`() {
        assertTrue(WallpaperSkin.isDark(WallpaperMode.System, true))
        assertFalse(WallpaperSkin.isDark(WallpaperMode.System, false))
        assertTrue(WallpaperSkin.isDark(WallpaperMode.Dark, false))
        assertFalse(WallpaperSkin.isDark(WallpaperMode.Light, true))
    }

    @Test
    fun `no palette means no skin`() {
        assertNull(WallpaperSkin.create(DynamicEnvironment(null, true), WallpaperMode.System, WallpaperLook.Midnight, listOf(Skin.Midnight)))
    }

    @Test
    fun `source rebuilds on refresh and on setting changes, and is hidden without a palette`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val settings = MutableStateFlow(ParcSettings())
        var env = DynamicEnvironment(palette, systemNight = true)
        val source = WallpaperSkinSource(env, settings, listOf(Skin.Midnight), scope)
        val dark = source.skin.value!!
        assertTrue(source.supported)

        env = DynamicEnvironment(palette, systemNight = false)
        source.refresh(env)
        assertEquals(WallpaperSkin.colors(palette, false), source.skin.value!!.colors)

        settings.value = ParcSettings(wallpaperMode = WallpaperMode.Dark)
        assertEquals(dark.colors, source.skin.value!!.colors)

        env = DynamicEnvironment(null, false)
        source.refresh(env)
        assertNull(source.skin.value)
        assertFalse(source.supported)
        scope.cancel()
    }

    @Test
    fun `repository offers the wallpaper skin only when present and resolves it by id`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val catalog = SkinCatalogLoader.parse(listOf(SkinAsset("midnight.json", File("src/main/assets/skins/midnight.json").readText())))
        val wallpaper = MutableStateFlow<Skin?>(null)
        val settings = MutableStateFlow(ParcSettings(skinId = WallpaperSkin.Id))
        val repository = SkinRepository(catalog, settings, scope, dynamic = wallpaper)

        assertEquals(listOf("midnight"), repository.available.map { it.id })
        assertEquals(Skin.Midnight, repository.active.value) // not exposed: falls back
        val built = WallpaperSkin.build(palette, true, Skin.Midnight)
        wallpaper.value = built
        assertEquals(listOf("midnight", WallpaperSkin.Id), repository.available.map { it.id })
        assertEquals(built, repository.active.value)
        scope.cancel()
    }

    private class FakeStore : DataStore<Preferences> {
        private val state = MutableStateFlow(preferencesOf())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun `mode and look persist, default sanely and survive a backup round trip`() = runTest {
        val store = FakeStore()
        val prefs = ParcPrefs(store)
        assertEquals(WallpaperMode.System, prefs.settings.first().wallpaperMode)
        assertEquals(WallpaperLook.Midnight, prefs.settings.first().wallpaperLook)

        prefs.setWallpaperMode(WallpaperMode.Light)
        prefs.setWallpaperLook(WallpaperLook.FieldAtlas)
        assertEquals(WallpaperMode.Light, ParcPrefs(store).settings.first().wallpaperMode)
        assertEquals(WallpaperLook.FieldAtlas, ParcPrefs(store).settings.first().wallpaperLook)

        val access = PreferencesStoreAccess(store)
        val saved = access.read(BackupSpecs.settings)
        assertEquals("Light", saved["wallpaper_skin_mode"])
        assertEquals("FieldAtlas", saved["wallpaper_skin_look"])

        val restored = FakeStore()
        PreferencesStoreAccess(restored).write(BackupSpecs.settings, saved + mapOf("wallpaper_skin_look" to "Neon"))
        val snap = ParcPrefs(restored).settings.first()
        assertEquals(WallpaperMode.Light, snap.wallpaperMode)
        assertEquals(WallpaperLook.Midnight, snap.wallpaperLook) // unknown value -> default
    }

    @Test
    fun `backup spec names match the stored keys`() {
        assertEquals("wallpaper_skin_mode", ParcPrefs.KeyWallpaperMode.name)
        assertEquals("wallpaper_skin_look", ParcPrefs.KeyWallpaperLook.name)
        assertNotNull(BackupSpecs.settings.key(ParcPrefs.KeyWallpaperMode.name))
        assertNotNull(BackupSpecs.settings.key(ParcPrefs.KeyWallpaperLook.name))
    }
}
