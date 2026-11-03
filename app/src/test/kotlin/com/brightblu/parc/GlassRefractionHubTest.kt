package com.brightblu.parc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.module.FrostPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** T173: the arc card frosts in every skin when glass is on, refraction is a switch, the opacity rows are easy to find. */
class GlassRefractionHubTest {
    private val skins get() = File("../core/data/src/main/assets/skins").listFiles()!!
        .filter { it.extension == "json" }.map { SkinJson.decode(it.readText()) }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }

    @Test fun arcHubTakesTheFrostPathInEverySkinWheneverGlassIsOn() {
        assertTrue(skins.size >= 4)
        for (skin in skins) for (readable in listOf(false, true)) {
            val glassOn = HubAppearance(glass = true)
            for (opacity in listOf(null, HubOpacity.DefaultGlass, HubOpacity.MinGlass, HubOpacity.Max)) {
                assertTrue("${skin.id} arc $opacity", FrostPath.frosted(glassOn.arcSkin(skin, opacity, readable)))
                assertTrue("${skin.id} stats $opacity", FrostPath.frosted(glassOn.statsSkin(skin, opacity, readable)))
            }
            val glassOff = HubAppearance(glass = false)
            assertFalse(FrostPath.frosted(glassOff.arcSkin(skin, HubOpacity.Default, readable)))
            assertFalse(FrostPath.frosted(glassOff.statsSkin(skin, HubOpacity.Default, readable)))
        }
    }

    @Test fun glassFollowsTheSkinUnlessTheSwitchWasUsed() {
        for (skin in skins) {
            val follows = skin.shape.moduleStyle == ModuleStyle.frosted
            assertEquals(skin.id, follows, FrostPath.frosted(HubAppearance().arcSkin(skin, 50)))
        }
    }

    @Test fun neverSolidWhileGlassIsOnEvenAtDefaults() {
        for (skin in skins) for (readable in listOf(false, true)) {
            val appearance = HubAppearance(glass = true)
            val glassDefault = HubOpacity.defaultFor(true)
            val arc = appearance.arcSkin(skin, glassDefault, readable).colors.moduleSurface
            val stats = appearance.statsSkin(skin, glassDefault, readable).colors.moduleSurface
            assertTrue("${skin.id} arc alpha ${(arc ushr 24) and 0xFF}", ((arc ushr 24) and 0xFF) <= 153)
            assertTrue("${skin.id} stats alpha ${(stats ushr 24) and 0xFF}", ((stats ushr 24) and 0xFF) <= 153)
        }
    }


    @Test fun refractionDefaultsOnAndRoundTrips() = runTest {
        assertTrue(HubAppearance().refraction)
        val store = HubAppearanceStore(MemoryStore())
        assertTrue(store.appearance.first().refraction)
        store.update { it.copy(refraction = false) }
        assertFalse(store.appearance.first().refraction)
        store.update { it.copy(refraction = true, glass = true) }
        assertEquals(HubAppearance(glass = true, refraction = true), store.appearance.first())
    }

    @Test fun oldBackupWithoutTheKeyRestoresToOn() {
        val old = HubAppearanceCodec.encode(HubAppearance(glass = true)) - HubAppearanceCodec.Refraction
        assertTrue(HubAppearanceCodec.decode(old).refraction)
        assertTrue(HubAppearanceCodec.Refraction in HubAppearanceCodec.keys)
        assertFalse(HubAppearanceCodec.decode(HubAppearanceCodec.encode(HubAppearance(refraction = false))).refraction)
    }



}
