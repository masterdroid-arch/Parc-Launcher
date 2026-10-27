package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.FieldAtlas
import com.brightblu.parc.model.SkinJson
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FieldAtlasAssetTest {
    @Test fun shippedAssetsMatchReviewedTokensAndEnterCatalog() {
        val assets = listOf(FieldAtlas.Dark, FieldAtlas.Light, com.brightblu.parc.model.Phosphor.Skin).map { expected ->
            val source = File("src/main/assets/skins/${expected.id}.json").readText()
            assertEquals(expected, SkinJson.decode(source))
            SkinAsset("${expected.id}.json", source)
        }
        val catalog = SkinCatalogLoader.parse(assets)
        assertTrue(catalog.rejections.isEmpty())
        assertEquals(com.brightblu.parc.model.Phosphor.Skin, catalog.byId("phosphor"))
        assertEquals(FieldAtlas.Light, catalog.byId(FieldAtlas.Light.id))
        assertEquals(FieldAtlas.Dark, catalog.byId(FieldAtlas.Dark.id))
    }
}
