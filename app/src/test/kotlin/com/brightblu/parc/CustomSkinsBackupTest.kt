package com.brightblu.parc

import com.brightblu.parc.core.data.skins.CustomSkinStore
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomSkinsBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun customSkinsRoundTripThroughTheBackupAccess() = runBlocking {
        val source = CustomSkinStore(tmp.newFolder("a"))
        source.save(Skin.Dusk.copy(id = "custom_dawn", name = "Dawn"))
        source.save(Skin.Midnight.copy(id = "custom_noir", name = "Noir"))
        val values = CustomSkinsStoreAccess(source).read(AppBackup.CustomSkinsSpec)
        val target = CustomSkinStore(tmp.newFolder("b"))
        CustomSkinsStoreAccess(target).write(AppBackup.CustomSkinsSpec, values)
        assertEquals(listOf("Dawn", "Noir"), target.skins.value.map { it.name })
        assertEquals(source.skins.value.map { it.colors }, target.skins.value.map { it.colors })
    }

    @Test fun v2TokensSurviveBackupAndLegacyBackupStillRestores() = runBlocking {
        val original = Skin.Dusk.copy(id = "custom_v2", name = "V2",
            colors = Skin.Dusk.colors.copy(moduleSurface = 0xFFE4E4E4, moduleGuide = 0xFFBEBEBE, moduleInk = 0xFF111111),
            shape = com.brightblu.parc.model.ShapeTokens(moduleStyle = com.brightblu.parc.model.ModuleStyle.frosted,
                tileCornerDp = 25f, moduleCornerDp = 39f),
            typography = com.brightblu.parc.model.TypographyTokens(numeralFamily = "Doto",
                bigDot = com.brightblu.parc.model.TypeToken(92f, 700, 0f)))
        val source = CustomSkinStore(tmp.newFolder("v2source"))
        assertTrue(source.save(original))
        fun backup(store: CustomSkinStore) = com.brightblu.parc.core.data.backup.FullBackup(
            com.brightblu.parc.model.fakes.FakeLayoutRepository(),
            listOf(com.brightblu.parc.core.data.backup.BackupStore(AppBackup.CustomSkinsSpec, CustomSkinsStoreAccess(store))))
        val document = backup(source).export()
        val target = CustomSkinStore(tmp.newFolder("v2target"))
        val restore = backup(target)
        assertEquals(0, restore.apply(restore.parse(document)).skipped)
        assertEquals(listOf(original), target.skins.value)
        val legacy = java.io.File("../core/data/src/main/assets/skins/dusk.json").readText()
            .replace("\"dusk\"", "\"custom_legacy\"").replace("\"Dusk\"", "\"Legacy\"")
        val legacyDocument = com.brightblu.parc.core.data.backup.BackupCodec.encode(
            com.brightblu.parc.model.ParcLayout(), mapOf("custom_skins" to mapOf("skins" to setOf(legacy))))
        assertEquals(0, restore.apply(restore.parse(legacyDocument)).skipped)
        assertTrue(target.skins.value.contains(Skin.Dusk.copy(id = "custom_legacy", name = "Legacy")))
    }

    @Test fun nothingToBackUpIsAnEmptyMap() = runBlocking {
        assertTrue(CustomSkinsStoreAccess(CustomSkinStore(tmp.newFolder("c"))).read(AppBackup.CustomSkinsSpec).isEmpty())
    }

    @Test fun restoreIgnoresBuiltInIdsAndGarbage() = runBlocking {
        val target = CustomSkinStore(tmp.newFolder("d"))
        CustomSkinsStoreAccess(target).write(AppBackup.CustomSkinsSpec,
            mapOf("skins" to setOf(SkinJson.encode(Skin.Midnight), "not json", SkinJson.encode(Skin.Dusk.copy(id = "custom_ok", name = "Ok")))))
        assertEquals(listOf("custom_ok"), target.skins.value.map { it.id })
    }
}
