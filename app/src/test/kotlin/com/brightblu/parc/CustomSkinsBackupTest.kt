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
