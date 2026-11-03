package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkinV2ExchangeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val skin = Skin.Dusk.copy(id = "custom_v2", name = "V2",
        colors = Skin.Dusk.colors.copy(moduleSurface = 0xFFE4E4E4, moduleGuide = 0xFFBEBEBE, moduleInk = 0xFF111111),
        shape = ShapeTokens(moduleStyle = ModuleStyle.frosted, tileCornerDp = 23f, moduleCornerDp = 37f),
        typography = TypographyTokens(numeralFamily = "Doto", clockDot = TypeToken(60f, 600, 1f),
            countDot = TypeToken(32f, 700, 0f), bigDot = TypeToken(96f, 800, 0f)))

    @Test fun exportImportAndDiskReloadPreserveEveryNewToken() {
        val imported = SkinExchange.import(SkinExchange.export(skin), Skin.Midnight, emptySet()) as SkinImportResult.Imported
        assertEquals(skin, imported.skin)
        val dir = tmp.newFolder()
        assertTrue(CustomSkinStore(dir).save(imported.skin))
        assertEquals(listOf(skin), CustomSkinStore(dir).skins.value)
    }

    @Test fun legacyImportDerivesModulePaletteEvenWhenBaseHasExplicitModuleColors() {
        val legacy = File("src/main/assets/skins/midnight.json").readText()
        val imported = (SkinExchange.import(legacy, skin, emptySet()) as SkinImportResult.Imported).skin
        assertEquals(Skin.Midnight.copy(id = imported.id), imported)
    }
}
