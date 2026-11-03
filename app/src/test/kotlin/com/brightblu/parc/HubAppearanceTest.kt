package com.brightblu.parc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.brightblu.parc.core.data.backup.*
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HubAppearanceTest {
    private val skins get() = File("../core/data/src/main/assets/skins").listFiles()!!
        .filter { it.extension == "json" }.map { SkinJson.decode(it.readText()) }

    @Test fun swatchesHaveSevenToOneInkOnBothSurfacesInEverySkin() {
        for (skin in skins) for (colour in HubColour.entries.filter { it.argb != null }) {
            val choice = HubAppearance(colour, colour, false)
            val arc = choice.arcSkin(skin).colors
            val stats = choice.statsSkin(skin).colors
            assertTrue("${skin.id} $colour arc", WallpaperReadability.contrast(arc.moduleInk.toInt(), arc.moduleSurface.toInt()) >= 7)
            assertTrue("${skin.id} $colour stats", WallpaperReadability.contrast(stats.textPrimary.toInt(), stats.surface.toInt()) >= 7)
            assertEquals(stats.textPrimary, stats.textSecondary)
            if (colour == HubColour.Sand) assertTrue(WallpaperReadability.contrast(arc.moduleInk.toInt(), 0xFFFFFFFF.toInt()) > 7)
        }
    }

    @Test fun frostMaintainsSevenToOneAcrossTheCachedStage() {
        for (skin in skins) for (colour in HubColour.entries.filter { it.argb != null }) {
            val appearance = HubAppearance(colour, colour, true)
            for (card in listOf(appearance.arcSkin(skin), appearance.statsSkin(skin))) {
                val tint = card.colors.moduleSurface
                for (behind in listOf(skin.colors.bg, skin.colors.lineActive)) {
                    val pixel = WallpaperReadability.composite(tint.toInt(), behind.toInt(), (tint ushr 24) / 255f)
                    assertTrue("${skin.id} $colour frost", WallpaperReadability.contrast(card.colors.moduleInk.toInt(), pixel) >= 7)
                }
            }
        }
    }

    @Test fun defaultsAndOverridesKeepGeometryAndOnlyChangeTheIntendedSurface() {
        for (skin in skins) {
            val defaults = HubAppearance()
            assertEquals(skin.shape.moduleStyle == ModuleStyle.frosted, defaults.glassEnabled(skin))
            val arc = defaults.arcSkin(skin)
            assertEquals(skin.shape, arc.shape)
            assertEquals(skin.typography, arc.typography)
            assertEquals(skin.colors.moduleSurface and 0xFFFFFF, arc.colors.moduleSurface and 0xFFFFFF)
            val chosen = HubAppearance(arc = HubColour.Slate)
            assertEquals(defaults.statsSkin(skin), chosen.statsSkin(skin))
            assertEquals(ModuleStyle.solid, defaults.copy(glass = false).arcSkin(skin).shape.moduleStyle)
            assertEquals(ModuleStyle.frosted, defaults.copy(glass = true).statsSkin(skin).shape.moduleStyle)
            assertTrue((defaults.copy(glass = true).arcSkin(skin).colors.moduleSurface ushr 24) >= Metrics.hubGlassTintAlpha)
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }

    @Test fun storeRoundTripsAndUpdatesOnlyOneChoice() = runTest {
        val data = MemoryStore()
        val store = HubAppearanceStore(data)
        assertEquals(HubAppearance(), store.appearance.first())
        store.update { it.copy(arc = HubColour.Sand, stats = HubColour.Plum, glass = true) }
        val reopened = HubAppearanceStore(data)
        assertEquals(HubAppearance(HubColour.Sand, HubColour.Plum, true), reopened.appearance.first())
        reopened.update { it.copy(glass = false) }
        assertEquals(HubColour.Sand, store.appearance.first().arc)
        assertEquals(false, store.appearance.first().glass)
    }

    @Test fun backupRoundTripsAndLegacyBackupResetsPreviouslyChosenColoursAndGlass() = runTest {
        val memory = MemoryStore()
        val store = HubAppearanceStore(memory)
        val access = HubAppearanceBackupAccess(PreferencesStoreAccess(memory))
        val full = FullBackup(FakeLayoutRepository(), listOf(BackupStore(AppBackup.HubSpec, access)))
        val chosen = HubAppearance(HubColour.Teal, HubColour.Sand, false)
        store.update { chosen }
        val backup = full.export()
        store.update { HubAppearance() }
        full.apply(full.parse(backup))
        assertEquals(chosen, store.appearance.first())
        // A valid old v2 envelope contains no hub settings at all.
        val old = """{"schema":"parc-backup","backupVersion":2,"layout":${LayoutJson.encode(ParcLayout())},"settings":{}}"""
        full.apply(full.parse(old))
        assertEquals(HubAppearance(), store.appearance.first())
        store.update { chosen }
        full.apply(full.parse(LayoutJson.encode(ParcLayout())))
        assertEquals(HubAppearance(), store.appearance.first())
    }

}
