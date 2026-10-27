package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.Skin
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation of the shipped skin files (DESIGN §2.8). The test reads the real assets under
 * `src/main/assets/skins/` (Gradle runs unit tests with the module directory as the working
 * directory, as [com.brightblu.parc.core.data.LayoutSchemaTest] relies on), so a typo in an authored
 * file fails here before it can ship. Unknown tokens must be rejected, never half-applied.
 */
class SkinCatalogTest {

    private val midnightJson = File("src/main/assets/skins/midnight.json").readText()
    private val duskJson = File("src/main/assets/skins/dusk.json").readText()
    private val cyberJson = File("src/main/assets/skins/cyber.json").readText()

    private fun midnight() = SkinAsset("midnight.json", midnightJson)
    private fun dusk() = SkinAsset("dusk.json", duskJson)
    private fun cyber() = SkinAsset("cyber.json", cyberJson)

    /** Structural edit of a JSON object so the "broken" cases do not depend on whitespace. */
    private fun edit(json: String, transform: (MutableMap<String, JsonElement>) -> Unit): String {
        val map = Json.parseToJsonElement(json).jsonObject.toMutableMap()
        transform(map)
        return JsonObject(map).toString()
    }

    private fun withColor(json: String, token: String, value: JsonElement): String =
        edit(json) { root ->
            val colors = root.getValue("colors").jsonObject.toMutableMap()
            colors[token] = value
            root["colors"] = JsonObject(colors)
        }

    @Test
    fun `the shipped Midnight file decodes to the built-in Midnight constant`() {
        val catalog = SkinCatalogLoader.parse(listOf(midnight()))

        assertEquals(listOf(Skin.Midnight), catalog.skins)
        assertEquals(Skin.Midnight, catalog.default)
        assertEquals(emptyList<SkinRejection>(), catalog.rejections)
    }

    @Test
    fun `the shipped Dusk file decodes to the built-in Dusk constant`() {
        val catalog = SkinCatalogLoader.parse(listOf(dusk()))

        assertEquals(listOf(Skin.Midnight, Skin.Dusk), catalog.skins)
        assertEquals(Skin.Midnight, catalog.default)
        assertEquals(Skin.Dusk, catalog.byId("dusk"))
        assertTrue(catalog.rejections.isEmpty())
    }

    @Test
    fun `both shipped skins load together with the default first`() {
        val catalog = SkinCatalogLoader.parse(listOf(dusk(), midnight()))

        assertEquals(listOf("midnight", "dusk"), catalog.ids)
        assertEquals(Skin.Midnight, catalog.default)
        assertEquals(listOf(Skin.Midnight, Skin.Dusk), catalog.skins)
    }

    @Test
    fun `the shipped Cyber file decodes with every colour the card specifies`() {
        val catalog = SkinCatalogLoader.parse(listOf(cyber()))

        assertEquals(emptyList<SkinRejection>(), catalog.rejections)
        val skin = catalog.byId("cyber")
        assertEquals("cyber", skin.id)
        assertEquals("Cyber", skin.name)

        val colors = skin.colors
        assertEquals(0xFF000000L, colors.bg)
        assertEquals(0xFF07090DL, colors.surface)
        assertEquals(0xFF12323AL, colors.lineBase)
        assertEquals(0xFF1F6F80L, colors.lineActive)
        assertEquals(0xFF00E5FFL, colors.accent)
        assertEquals(0xFFD8F6FFL, colors.textPrimary)
        assertEquals(0xFF6FA3B3L, colors.textSecondary)
        // glow is the accent at 34 % (34 % of 255 rounds to 0x57).
        assertEquals(0x5700E5FFL, colors.glow)
        assertEquals(0xB8000000L, colors.scrim)
    }

    @Test
    fun `Cyber changes only colour tokens, never geometry or motion`() {
        val decoded = SkinCatalogLoader.parse(listOf(cyber())).byId("cyber")

        // The same as Midnight with Cyber's identity and colours: every non-colour token, so every
        // arc, slot and duration, is unchanged (DESIGN §2.8: a skin is data, not a layout).
        assertEquals(Skin.Midnight.copy(id = "cyber", name = "Cyber", colors = decoded.colors, style = Skin.Midnight.style.copy(terminalStats = true)), decoded)
    }

    @Test
    fun `every authored skin file reaches the catalog and the picker`() {
        // AndroidSkinAssets hands the catalog every *.json in the directory, so an authored file is
        // registered by existing; this pins the set and the order the picker renders.
        val directory = File("src/main/assets/skins")
        val names = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .map { it.name }
            .sorted()

        assertEquals(listOf("cyber.json", "dusk.json", "field-atlas-dark.json", "field-atlas-light.json", "midnight.json", "phosphor.json"), names)

        val catalog = SkinCatalogLoader.parse(
            names.map { SkinAsset(it, File(directory, it).readText()) },
        )

        assertEquals(listOf("midnight", "cyber", "dusk", "field-atlas-dark", "field-atlas-light", "phosphor"), catalog.ids)
        assertEquals(Skin.Midnight, catalog.default)
        assertEquals(Skin.Midnight, catalog.byId("midnight"))
        assertEquals(Skin.Dusk, catalog.byId("dusk"))
        assertTrue(catalog.rejections.isEmpty())
    }

    @Test
    fun `an unknown colour token is rejected and the file skipped`() {
        val broken = midnightJson.replace("\"surface\"", "\"surfaceGlow\"")

        val catalog = SkinCatalogLoader.parse(listOf(SkinAsset("midnight.json", broken), dusk()))

        assertEquals(listOf("midnight", "dusk"), catalog.ids)
        assertEquals(1, catalog.rejections.size)
        assertEquals("midnight.json", catalog.rejections.single().asset)
    }

    @Test
    fun `an unknown top-level token is rejected`() {
        val broken = edit(midnightJson) { it["glowRadiusDpExtra"] = JsonPrimitive(48) }

        val catalog = SkinCatalogLoader.parse(listOf(SkinAsset("midnight.json", broken)))

        assertEquals(listOf(Skin.Midnight), catalog.skins)
        assertEquals(1, catalog.rejections.size)
    }

    @Test
    fun `a missing required colour token is rejected`() {
        val broken = edit(midnightJson) { root ->
            val colors = root.getValue("colors").jsonObject.toMutableMap()
            colors.remove("accent")
            root["colors"] = JsonObject(colors)
        }

        val catalog = SkinCatalogLoader.parse(listOf(SkinAsset("midnight.json", broken)))

        assertEquals(listOf(Skin.Midnight), catalog.skins)
        assertEquals(1, catalog.rejections.size)
    }

    @Test
    fun `an out-of-range motion scale is rejected`() {
        val broken = edit(midnightJson) { it["motionScale"] = JsonPrimitive(5.0) }

        val catalog = SkinCatalogLoader.parse(listOf(SkinAsset("midnight.json", broken)))

        assertEquals(listOf(Skin.Midnight), catalog.skins)
        assertEquals(1, catalog.rejections.size)
    }

    @Test
    fun `negative glowRadiusDp is rejected`() {
        val broken = edit(midnightJson) { it["glowRadiusDp"] = JsonPrimitive(-4.0) }

        val catalog = SkinCatalogLoader.parse(listOf(SkinAsset("midnight.json", broken)))

        assertEquals(listOf(Skin.Midnight), catalog.skins)
        assertEquals(1, catalog.rejections.size)
    }

    @Test
    fun `a file whose id does not match its name is rejected`() {
        val catalog = SkinCatalogLoader.parse(listOf(SkinAsset("dusk.json", midnightJson)))

        assertEquals(listOf(Skin.Midnight), catalog.skins)
        assertEquals("dusk.json", catalog.rejections.single().asset)
    }

    @Test
    fun `a duplicate id is rejected the second time`() {
        val catalog = SkinCatalogLoader.parse(listOf(midnight(), midnight()))

        assertEquals(listOf(Skin.Midnight), catalog.skins)
        assertEquals(1, catalog.rejections.size)
        assertTrue(catalog.rejections.single().reason.contains("duplicate"))
    }

    @Test
    fun `an empty asset list still yields the built-in default`() {
        val catalog = SkinCatalogLoader.parse(emptyList())

        assertEquals(listOf(Skin.Midnight), catalog.skins)
        assertSame(Skin.Midnight, catalog.default)
        assertTrue(catalog.rejections.isEmpty())
    }

    @Test
    fun `byId resolves a known id, falls back for an unknown or blank id, and never throws`() {
        val catalog = SkinCatalogLoader.parse(listOf(midnight(), dusk()))

        assertEquals(Skin.Dusk, catalog.byId("dusk"))
        assertEquals(Skin.Midnight, catalog.byId("neon"))
        assertEquals(Skin.Midnight, catalog.byId(""))
    }

    @Test
    fun `a rejection carries the asset name and a human reason`() {
        val broken = withColor(midnightJson, "accent", JsonPrimitive("blue"))

        val catalog = SkinCatalogLoader.parse(listOf(SkinAsset("midnight.json", broken)))
        val rejection = catalog.rejections.single()

        assertEquals("midnight.json", rejection.asset)
        assertTrue(rejection.reason.isNotBlank())
        assertTrue(catalog.ids.contains("midnight"))
    }

    @Test
    fun `a skin file declares only tokens, never slot or arc geometry`() {
        val tokenFields = setOf(
            "id", "name", "colors", "iconStyle", "glowRadiusDp",
            "lineWidths", "motionScale", "breatheAmount",
        )

        listOf(midnightJson, duskJson, cyberJson).forEach { json ->
            assertEquals(if (json == cyberJson) tokenFields + "style" else tokenFields, Json.parseToJsonElement(json).jsonObject.keys)
        }
    }
}
