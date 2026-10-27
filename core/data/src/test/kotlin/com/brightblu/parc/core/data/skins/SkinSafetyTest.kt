package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinSafety
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** OPUS6 (audit F7): unreadable skins cannot lock the user out of Settings. */
class SkinSafetyTest {
    @Test fun everyBuiltInSkinIsReadable() {
        val assets = File("src/main/assets/skins").listFiles { f -> f.name.endsWith(".json") }!!
            .map { SkinAsset(it.name, it.readText()) }
        val catalog = SkinCatalogLoader.parse(assets)
        assertTrue(catalog.skins.isNotEmpty())
        catalog.skins.forEach { assertTrue("${it.id} ${SkinSafety.textContrast(it)}", SkinSafety.readable(it)) }
        assertTrue(SkinSafety.readable(Skin.Midnight))
    }

    @Test fun textTheSameColourAsTheBackgroundIsRefusedAndSettingsFallBack() {
        val bad = Skin.Midnight.copy(id = "custom_bad", colors = Skin.Midnight.colors.copy(textPrimary = Skin.Midnight.colors.bg))
        assertFalse(SkinSafety.readable(bad))
        assertEquals(Skin.Midnight, SkinSafety.forSafetyScreens(bad))
        val dim = Skin.Midnight.copy(colors = Skin.Midnight.colors.copy(textSecondary = Skin.Midnight.colors.surface))
        assertFalse("dim text counts too", SkinSafety.readable(dim))
        val fine = Skin.Midnight.copy(id = "custom_ok")
        assertSame(fine, SkinSafety.forSafetyScreens(fine))
    }
}
