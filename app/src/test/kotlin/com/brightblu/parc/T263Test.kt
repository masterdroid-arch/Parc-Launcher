package com.brightblu.parc

import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.ReaderBookMarks
import com.brightblu.parc.core.data.library.ReaderHighlight
import com.brightblu.parc.core.data.library.ReaderMarksDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T263: page merges, old ids, and the one searchable list of kept passages. */
class T263Test {
    @Test fun catalogHasTheFourMergedPagesAndTodayLeads() {
        assertEquals(TodayPage.Id, HubCatalog.pages.first())
        listOf(DevicePage.Id, ControlsPage.Id, CommonplacePage.Id).forEach { assertTrue(it in HubCatalog.pages) }
        listOf("clock", "system", "power", "network", "sound", QuickToggleCatalog.PageId, QuotesPage.Id).forEach {
            assertFalse("$it must be merged away", it in HubCatalog.pages)
        }
    }

    @Test fun oldPageIdsResolveToTheMergedPage() {
        assertEquals(TodayPage.Id, HubCatalog.canonicalPage("clock"))
        assertEquals(DevicePage.Id, HubCatalog.canonicalPage("system"))
        assertEquals(DevicePage.Id, HubCatalog.canonicalPage("power"))
        assertEquals(DevicePage.Id, HubCatalog.canonicalPage("network"))
        assertEquals(ControlsPage.Id, HubCatalog.canonicalPage("sound"))
        assertEquals(ControlsPage.Id, HubCatalog.canonicalPage(QuickToggleCatalog.PageId))
        assertEquals(CommonplacePage.Id, HubCatalog.canonicalPage(QuotesPage.Id))
        assertEquals("apps", HubCatalog.canonicalPage("apps"))
        assertEquals("Device", hubPageName("system"))
        assertEquals("Controls", hubPageName("sound"))
        assertEquals("Commonplace", hubPageName("quotes"))
    }

    @Test fun deviceSectionsAreFixedAndNoTileIsRepeated() {
        assertEquals(listOf("Power", "Network", "Storage", "Thermal"), HubCatalog.deviceSections.keys.toList())
        assertEquals(HubCatalog.deviceTiles.size, HubCatalog.deviceTiles.distinct().size)
        HubCatalog.deviceTiles.forEach { assertNotNull(HubCatalog.deviceSection(it)) }
        assertTrue("storage" in HubCatalog.deviceSections.getValue("Storage"))
        assertTrue("thermal" in HubCatalog.deviceSections.getValue("Thermal"))
        assertTrue("traffic" in HubCatalog.deviceSections.getValue("Network"))
        assertTrue("battery" in HubCatalog.deviceSections.getValue("Power"))
    }

    @Test fun controlsSectionsHoldVolumeAndQuickTogglesOnce() {
        assertEquals(listOf("Volume", "Quick toggles"), HubCatalog.controlsSections.keys.toList())
        assertEquals(HubCatalog.controlsTiles.size, HubCatalog.controlsTiles.distinct().size)
        assertTrue(HubCatalog.soundTiles.all { it in HubCatalog.controlsTiles })
        assertTrue(QuickToggleCatalog.tiles.all { it in HubCatalog.controlsTiles })
        assertEquals("Quick toggles", HubCatalog.controlsSection("torch"))
        assertEquals("Volume", HubCatalog.controlsSection("ring"))
    }

    @Test fun oldOrderVisibilityAndTilesLandOnTheMergedPages() {
        val old = "clock:true::;system:false:ram:ram;power:true:charging:;network:true:traffic:;" +
            "sound:true:media:;quotes:true::;apps:true:new apps:"
        val config = HubConfig.decode(old)
        assertEquals(listOf(TodayPage.Id, DevicePage.Id, ControlsPage.Id, CommonplacePage.Id, "apps"), config.pages.take(5))
        assertTrue(DevicePage.Id in config.pages) // Power was visible even though System was hidden.
        assertTrue("ram" in config.hiddenTiles[DevicePage.Id].orEmpty())
        assertTrue("traffic" in config.tiles(DevicePage.Id))
        assertTrue("media" in config.tiles(ControlsPage.Id))
        assertEquals(HubCatalog.deviceTiles.toSet(), config.tiles(DevicePage.Id).toSet())
        assertEquals(HubCatalog.controlsTiles.toSet(), config.tiles(ControlsPage.Id).toSet())
        assertEquals(config.encode(), HubConfig.decode(config.encode()).encode())
    }

    @Test fun aDeviceHiddenByAllThreeOldPagesStaysHidden() {
        val config = HubConfig.decode("system:false:ram:;power:false:charging:;network:false:traffic:")
        assertFalse(DevicePage.Id in config.pages)
        assertTrue(DevicePage.Id in config.orderedPages)
    }

    @Test fun oldBackupRememberedPageAndSizeLandOnTheMergedPage() {
        val config = HubConfig.decode("clock:true::;system:true:battery:;power:true:charging:;network:true:traffic:;sound:true:media:;quotes:true::;~sizes:clock=tall,power=compact")
        assertEquals(TodayPage.Id, restoredHubPage("clock", config))
        assertEquals(DevicePage.Id, restoredHubPage("system", config))
        assertEquals(DevicePage.Id, restoredHubPage("power", config))
        assertEquals(DevicePage.Id, restoredHubPage("network", config))
        assertEquals(ControlsPage.Id, restoredHubPage("sound", config))
        assertEquals(CommonplacePage.Id, restoredHubPage("quotes", config))
        assertEquals(HubSize.Tall, config.sizeFor(TodayPage.Id))
        assertEquals(HubSize.Compact, config.sizeFor(DevicePage.Id))
    }

    @Test fun mergedPageVisibilityAndTilesFollowTheExistingDocument() {
        val config = HubConfig().showPage(DevicePage.Id, false).showPage(ControlsPage.Id, true)
            .showTile(DevicePage.Id, "traffic", false).move(DevicePage.Id, "thermal", -99)
        val restored = HubConfig.decode(config.encode())
        assertFalse(DevicePage.Id in restored.pages)
        assertTrue(ControlsPage.Id in restored.pages)
        assertTrue("traffic" in restored.hiddenTiles[DevicePage.Id].orEmpty())
        assertEquals("thermal", restored.tiles(DevicePage.Id).first())
    }

    @Test fun commonplaceKeepsQuotesBookHighlightsAndSharedTextInOneList() {
        val quotes = listOf(
            Quote("starter-one", "A quiet first passage", "Writer", "Book"),
            Quote("own-shared-one", "Text shared from another app"),
        )
        val book = Book("book-1", "content://book", "A Sample Book", "A Writer", BookFormat.EPUB)
        val marks = ReaderMarksDocument(mapOf("book-1" to ReaderBookMarks(highlights = listOf(
            ReaderHighlight("h1", 2, 3, 12, text = "A highlighted line", note = "Keep this", createdAt = 5),
        ))))
        val items = CommonplaceRules.items(quotes, listOf(book), marks)
        assertEquals(3, items.size)
        assertEquals(listOf(CommonplaceKind.Quote, CommonplaceKind.SharedText, CommonplaceKind.Highlight), items.map { it.kind })
        assertEquals("A Sample Book · A Writer", items.last().source)
        assertEquals("Keep this", items.last().note)
        assertTrue(items.last().caption.startsWith("Book highlight"))
    }

    @Test fun commonplaceSearchCoversTextSourceAndKindAndHasQuietEmptyStates() {
        val items = listOf(
            CommonplaceItem("q", CommonplaceKind.Quote, "The light on the table", "A Writer · A Book"),
            CommonplaceItem("h", CommonplaceKind.Highlight, "A highlighted line", "A Sample Book", note = "Keep this"),
        )
        assertEquals(listOf("q"), CommonplaceRules.results(items, "table").map { it.id })
        assertEquals(listOf("h"), CommonplaceRules.results(items, "sample").map { it.id })
        assertEquals(listOf("h"), CommonplaceRules.results(items, "book highlight").map { it.id })
        assertEquals(items, CommonplaceRules.results(items, "  "))
        assertTrue(CommonplaceRules.results(items, "missing").isEmpty())
        assertTrue(CommonplaceRules.emptyMessage(emptyList(), "")!!.startsWith("Nothing kept yet"))
        assertTrue(CommonplaceRules.emptyMessage(items, "missing")!!.startsWith("No passages match"))
    }

    @Test fun demoShowsOnlyMergedPagesAndInventedPassages() {
        assertTrue(DevicePage.Id in DemoHubPages)
        assertTrue(ControlsPage.Id in DemoHubPages)
        assertTrue(CommonplacePage.Id in DemoHubPages)
        listOf("clock", "system", "power", "network", "sound", QuickToggleCatalog.PageId, QuotesPage.Id).forEach {
            assertFalse("$it must not be a demo page", it in DemoHubPages)
        }
    }
}
