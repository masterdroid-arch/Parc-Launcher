package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.model.SkinJson
import java.io.File
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class T191Test {
    @Test fun commonplaceAbsorbsLightlyAndKeepsItsSavedPassages() {
        assertTrue(CommonplacePage.Id in HubCatalog.pages)
        assertEquals("Commonplace", hubPageName("quotes"))
        assertTrue(CommonplacePage.Id in HubCatalog.defaultHiddenPages)
        assertTrue(CommonplacePage.Id in HubCatalog.newUserHiddenPages)
        assertTrue(CommonplacePage.Id in HubCatalog.tilelessPages)
        assertTrue(CommonplacePage.Id in HubCatalog.m13Pages)
        assertTrue(HubCatalog.tiles(CommonplacePage.Id).isEmpty())
        assertTrue(CommonplacePage.Id in DemoHubPages)
        assertNotNull(hubPageOptions[CommonplacePage.Id])
        assertNotNull(hubTemplates[CommonplacePage.Id])
        val old = HubConfig.decode("clock:true::;power:true::;notes:true::;quotes:true::")
        assertTrue(CommonplacePage.Id in old.pages)
        assertTrue("quotes" in old.orderedPages || CommonplacePage.Id in old.orderedPages)
        assertTrue(CommonplacePage.Id in HubConfig.decode(old.showPage("quotes", true).encode()).pages)
        // A saved quote library still decodes through the same backup section.
        assertTrue(BackupSpecs.quotes in BackupSpecs.all)
    }
    @Test fun backupIncludesPersonalTextHiddenIdsAndRotation() {
        assertTrue(BackupSpecs.quotes in BackupSpecs.all)
        assertEquals(setOf(QuotesJson.OwnKey, QuotesJson.HiddenKey, QuotesJson.OrderKey), BackupSpecs.quotes.keys.map { it.name }.toSet())
    }
    @Test fun starterAssetContainsTwentyFiveUniqueValidOriginalEnglishPassages() {
        val quotes = QuotesJson.quotes(File("src/main/assets/starter_quotes.json").readText())
        assertEquals(25, quotes.size)
        assertEquals(25, quotes.map { it.text }.toSet().size)
        assertTrue(quotes.all { it.id.startsWith("starter-") && QuoteRules.valid(it) && it.work.isNotBlank() && it.author.isNotBlank() })
        val deaths = mapOf("Henry David Thoreau" to 1862, "Ralph Waldo Emerson" to 1882, "Walt Whitman" to 1892)
        assertTrue(quotes.all { deaths.getValue(it.author) < 1955 && it.translator.isEmpty() })
        assertFalse(quotes.any { "Huxley" in it.author })
        assertTrue(quotes.any { '\n' in it.text })
    }
    @Test fun fivePaperSurfacesAreOpaqueDimAndHaveReadableInkIncludingSmallCredits() {
        fun luminance(argb: Long): Double = listOf(16 to 0.2126, 8 to 0.7152, 0 to 0.0722).sumOf { (shift, weight) ->
            val c = ((argb shr shift) and 255).toDouble() / 255
            (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)) * weight
        }
        listOf("midnight", "dusk", "moss", "graphite", "glass").forEach { id ->
            val skin = SkinJson.decode(File("../core/data/src/main/assets/skins/$id.json").readText())
            val paper = luminance(skin.colors.paper)
            val ink = luminance(skin.colors.ink)
            assertEquals(id, 255L, skin.colors.paper shr 24)
            assertEquals(id, 255L, skin.colors.ink shr 24)
            assertTrue("$id dim paper", paper < 0.5)
            assertTrue("$id readable ink", (paper + 0.05) / (ink + 0.05) >= 7.0)
        }
    }
    @Test fun olderCustomSkinJsonGetsTheNewReadingTokensWithoutMigration() {
        val raw = File("../core/data/src/main/assets/skins/cyber.json").readText()
        val skin = SkinJson.decode(raw)
        assertEquals(0xFFB5AA96L, skin.colors.paper)
        assertEquals(0xFF242018L, skin.colors.ink)
    }
    @Test fun plainTextShareFilterIsOnTheExistingSingleTaskHost() {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val activities = document.getElementsByTagName("activity")
        val host = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == ".MainActivity" }
        val filters = host.getElementsByTagName("intent-filter")
        val send = (0 until filters.length).map { filters.item(it) as org.w3c.dom.Element }.single { filter ->
            val actions = filter.getElementsByTagName("action")
            (0 until actions.length).any { (actions.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") == "android.intent.action.SEND" }
        }
        assertEquals("text/plain", (send.getElementsByTagName("data").item(0) as org.w3c.dom.Element).getAttributeNS(android, "mimeType"))
        assertEquals("singleTask", host.getAttributeNS(android, "launchMode"))
    }
}
