package com.brightblu.parc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.brightblu.parc.core.data.backup.*
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.ring.module.FrostKey
import com.brightblu.parc.ring.module.FrostRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** T171: one opacity per hub; the arc hub frosts like the stats hub and follows its own value. */
class HubOpacityTest {
    private val skins get() = File("../core/data/src/main/assets/skins").listFiles()!!
        .filter { it.extension == "json" }.map { SkinJson.decode(it.readText()) }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }

    @Test fun clampKeepsRangeAndStep() {
        assertEquals(40, HubOpacity.clamp(0))
        assertEquals(40, HubOpacity.clamp(-20))
        assertEquals(100, HubOpacity.clamp(250))
        assertEquals(60, HubOpacity.clamp(60))
        assertEquals(65, HubOpacity.clamp(64))
        assertEquals(Metrics.panelOpacitySteps, HubOpacity.steps)
        assertEquals(85, HubOpacity.Default)
        assertEquals(85, LookSettings().panelOpacityPercent)
    }


    @Test fun storeRoundTripsEachSettingIndependently() = runTest {
        val memory = MemoryStore()
        val store = HubAppearanceStore(memory)
        assertNull(store.appearance.first().arcOpacity)
        store.update { it.copy(arcOpacity = 10) } // below the glass minimum: clamped on read
        assertEquals(20, store.appearance.first().arcOpacity)
        assertNull(store.appearance.first().statsOpacity)
        store.update { it.copy(statsOpacity = 95) }
        val read = HubAppearanceStore(memory).appearance.first()
        assertEquals(20, read.arcOpacity)
        assertEquals(95, read.statsOpacity)
        store.update { it.copy(arcOpacity = 70) }
        assertEquals(95, store.appearance.first().statsOpacity)
    }

    @Test fun backupCarriesBothValuesAndAnOldBackupFallsBackToTheOldSingleValue() = runTest {
        val memory = MemoryStore()
        val store = HubAppearanceStore(memory)
        val full = FullBackup(FakeLayoutRepository(), listOf(BackupStore(AppBackup.HubSpec, HubAppearanceBackupAccess(PreferencesStoreAccess(memory)))))
        val chosen = HubAppearance(arcOpacity = 50, statsOpacity = 90)
        store.update { chosen }
        val backup = full.export()
        store.update { HubAppearance() }
        full.apply(full.parse(backup))
        assertEquals(chosen, store.appearance.first())
        val old = """{"schema":"parc-backup","backupVersion":2,"layout":${LayoutJson.encode(ParcLayout())},"settings":{}}"""
        store.update { chosen }
        full.apply(full.parse(old))
        assertNull(store.appearance.first().arcOpacity)
        assertNull(store.appearance.first().statsOpacity)
    }

    @Test fun eachSliderOnlyChangesItsOwnHub() {
        for (skin in skins) for (glass in listOf(true, false)) {
            val base = HubAppearance(glass = glass, arcOpacity = 85, statsOpacity = 85)
            val arcMoved = base.copy(arcOpacity = 40)
            assertEquals("${skin.id} stats unchanged", base.statsSkin(skin, 85), arcMoved.statsSkin(skin, 85))
            val statsMoved = base.copy(statsOpacity = 40)
            assertEquals("${skin.id} arc unchanged", base.arcSkin(skin, 85), statsMoved.arcSkin(skin, 85))
        }
    }

    @Test fun opacityIsTheCardAlphaInGlassAndFlatInEverySkin() {
        for (skin in skins) {
            val flat = HubAppearance(glass = false)
            val a30 = flat.arcSkin(skin, 100).colors.moduleSurface ushr 24
            val a60 = flat.arcSkin(skin, 60).colors.moduleSurface ushr 24
            val a100 = flat.arcSkin(skin, 100).colors.moduleSurface ushr 24
            assertEquals(255L, a100)
            assertTrue("${skin.id} flat 60 is see-through", a60 in 150L..156L)
            assertTrue(a30 >= a60)
            val glass = HubAppearance(glass = true)
            val g100 = glass.arcSkin(skin, 100).colors.moduleSurface ushr 24
            val g60 = glass.arcSkin(skin, 60).colors.moduleSurface ushr 24
            assertEquals(255L, g100)
            assertTrue("${skin.id} glass 60 is below 100", g60 < g100)
        }
    }

    @Test fun inkKeepsFourPointFiveToOneAtTheLowestValueOverTheHazeAndFlatWithTheFloor() {
        for (skin in skins) for (colour in HubColour.entries) for (glass in listOf(true, false)) {
            val appearance = HubAppearance(colour, colour, glass)
            for (card in listOf(appearance.arcSkin(skin, HubOpacity.Min, keepReadable = true), appearance.statsSkin(skin, HubOpacity.Min, keepReadable = true))) {
                val tint = card.colors.moduleSurface
                val ink = card.colors.moduleInk.toInt()
                val alpha = (tint ushr 24) / 255f
                if (glass) {
                    // T172: against the mean of the haze stage, not its extremes.
                    val behind = Backdrop.haze(skin.colors.bg.toInt(), skin.colors.lineActive.toInt()).mean
                    assertTrue("${skin.id} $colour glass", WallpaperReadability.contrast(ink, WallpaperReadability.composite(tint.toInt(), behind, alpha)) >= 4.5)
                }
                else for (behind in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
                    assertTrue("${skin.id} $colour flat", WallpaperReadability.contrast(ink, WallpaperReadability.composite(tint.toInt(), behind, alpha)) >= 4.5)
            }
        }
    }

    @Test fun userTintStartsAtTheOpacityAndOnlyRisesForFourPointFive() {
        val surface = 0xFF1F5A3A.toInt(); val ink = 0xFFF1F4EC.toInt(); val white = 0xFFFFFFFF.toInt(); val dark = 0xFF202020.toInt()
        // Old rule: 7:1 over a white wallpaper pushes a dark green card to solid. New rule: 4.5:1 leaves room.
        assertTrue(GlassBackdrop.tintAlpha(surface, ink, dark, white) >= 245)
        val user = GlassBackdrop.tintAlpha(surface, ink, dark, white, start = 102, minContrast = Metrics.hubCardMinContrast)
        assertTrue("alpha $user", user in 102..215)
        assertEquals(204, GlassBackdrop.tintAlpha(surface, ink, dark, dark, start = 204, minContrast = Metrics.hubCardMinContrast))
    }

    @Test fun arcHubTakesTheFrostedPathWheneverGlassIsOn() {
        for (skin in skins) {
            assertEquals(ModuleStyle.frosted, HubAppearance(glass = true).arcSkin(skin, 85).shape.moduleStyle)
            assertEquals(ModuleStyle.solid, HubAppearance(glass = false).arcSkin(skin, 85).shape.moduleStyle)
            assertEquals(ModuleStyle.frosted, HubAppearance(glass = true).statsSkin(skin, 85).shape.moduleStyle)
        }
        // The frost cache key tells a user tint from the T153 one, and follows the card alpha.
        fun key(userTint: Boolean, tint: Int) = FrostRequest(1080, 2400, 0, 0, 100, 100, 10, true, tint, 0, 0, 7, 3, 0xFFFFFF, userTint).key
        assertNotEquals(key(true, 0x99112233.toInt()), key(false, 0x99112233.toInt()))
        assertNotEquals(key(true, 0x99112233.toInt()), key(true, 0x4D112233))
        assertEquals(FrostKey::class, key(true, 0).let { it::class })
    }


}
