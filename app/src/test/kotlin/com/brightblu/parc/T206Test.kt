package com.brightblu.parc

import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.FoundBook
import com.brightblu.parc.model.Metrics
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T206: how the Library page is registered, how the shelf sizes itself and what Find books does. */
class T206Test {
    private fun book(id: String, title: String, author: String = "", format: BookFormat = BookFormat.EPUB,
        size: Long = 1_000, added: Long = 1_000, opened: Long? = null, progress: Double = 0.0) =
        Book(id, "content://library/$id", title, author, format, size, added, opened, progress)

    private fun found(name: String, format: BookFormat = BookFormat.EPUB, folder: String = "Shared storage/Books",
        size: Long = 1_000, modified: Long = 1_000) =
        FoundBook("content://found/${name}", name, folder, size, modified, format)

    @Test fun theLibraryPageIsHiddenForOldAndNewUsersAndHasNoTiles() {
        assertTrue(LibraryPage.Id in HubCatalog.pages)
        assertTrue(LibraryPage.Id in HubCatalog.defaultHiddenPages)
        assertTrue(LibraryPage.Id in HubCatalog.newUserHiddenPages)
        assertTrue(LibraryPage.Id in HubCatalog.tilelessPages)
        assertTrue(LibraryPage.Id in HubCatalog.m13Pages)
        assertTrue(HubCatalog.tiles(LibraryPage.Id).isEmpty())
        assertFalse(LibraryPage.Id in HubConfig().pages)
        assertNotNull(hubPageOptions[LibraryPage.Id])
        assertNotNull(hubTemplates[LibraryPage.Id])
        assertEquals("Library", hubPageName(LibraryPage.Id))
    }

    @Test fun anOldSavedConfigGainsTheLibraryHiddenAndCanShowItAgain() {
        val old = "clock:true::;system:true:battery:;network:true:traffic:;apps:true:new apps:;sound:true:media:;notes:false::;comms:true::"
        val config = HubConfig.decode(old)
        assertTrue(LibraryPage.Id in config.hiddenPages)
        assertFalse(LibraryPage.Id in config.pages)
        val shown = config.showPage(LibraryPage.Id, true)
        assertTrue(LibraryPage.Id in HubConfig.decode(shown.encode()).pages)
    }

    @Test fun theDemoHubHasAMadeUpLibrary() {
        assertTrue(LibraryPage.Id in DemoHubPages)
        assertTrue(DemoLibraryBooks.isNotEmpty())
        assertTrue(DemoLibraryBooks.all { it.uri.startsWith("demo://") })
        assertTrue(DemoLibraryBooks.map { it.format }.containsAll(listOf(BookFormat.EPUB, BookFormat.PDF, BookFormat.TXT, BookFormat.CBZ)))
    }

    @Test fun theShelfGivesTheMostRecentlyOpenedBookFirst() {
        val never = book("c", "C")
        val older = book("b", "B", opened = 20)
        val newest = book("a", "A", opened = 30)
        assertEquals(listOf("a", "b", "c"), LibraryFormat.order(listOf(never, older, newest)).map { it.id })
        assertEquals(listOf("c", "b", "a"), LibraryFormat.order(listOf(book("a", "A", added = 1), book("b", "B", added = 2), book("c", "C", added = 3))).map { it.id })
    }

    @Test fun coversKeepTheReaderShapeAndThePanelFitsThem() {
        val compact = LibraryLayout.plan(HubSize.Compact, 120f, 328f)
        val standard = LibraryLayout.plan(HubSize.Standard, 260f, 328f)
        val tall = LibraryLayout.plan(HubSize.Tall, 420f, 328f)
        assertEquals(1, compact.rows)
        assertEquals(1, standard.rows)
        assertEquals(2, tall.rows)
        listOf(compact, standard, tall).forEach {
            assertEquals(Metrics.bookShelfAspect, it.coverWidthDp / it.coverHeightDp, 0.001f)
            assertTrue("$it fits", it.coverHeightDp * it.rows + it.labelHeightDp * it.rows + Metrics.bookShelfGapDp * (it.rows - 1) <= 420f)
            assertTrue(it.columns >= 1)
        }
        assertTrue(tall.coverHeightDp <= Metrics.bookShelfCoverMaxDp)
        assertEquals(3, standard.columns)
    }

    @Test fun tallKeepsOneReadableRowWhenTwoWouldBeTiny() {
        val short = LibraryLayout.plan(HubSize.Tall, 180f, 328f)
        assertEquals(1, short.rows)
        assertTrue(short.coverHeightDp >= Metrics.bookShelfCoverMinDp)
    }

    @Test fun findBooksCountMatchesTheButton() {
        assertEquals("3 new, 2 already in library", LibraryFind.summaryLabel(5, 3))
        assertEquals("2 already in library", LibraryFind.summaryLabel(2, 0))
        assertEquals("Nothing selected", LibraryFind.summaryLabel(0, 0))
    }

    @Test fun aWiderPanelAddsColumnsInsteadOfStretchingTheCover() {
        val phone = LibraryLayout.plan(HubSize.Standard, 260f, 328f)
        val tablet = LibraryLayout.plan(HubSize.Standard, 260f, 640f)
        assertTrue(tablet.columns > phone.columns)
        assertEquals(phone.coverWidthDp, tablet.coverWidthDp, 0.001f)
        assertEquals(phone.coverHeightDp, tablet.coverHeightDp, 0.001f)
    }

    @Test fun largerTextKeepsTheLabelsInsideThePanel() {
        val normal = LibraryLayout.plan(HubSize.Tall, 420f, 328f, 1f)
        val large = LibraryLayout.plan(HubSize.Tall, 420f, 328f, 1.3f)
        assertTrue(large.labelHeightDp > normal.labelHeightDp)
        assertTrue(large.coverHeightDp <= normal.coverHeightDp)
        assertTrue(large.coverHeightDp * large.rows + large.labelHeightDp * large.rows + Metrics.bookShelfGapDp * (large.rows - 1) <= 420f)
    }

    @Test fun findBooksFiltersByTypeAndSearchesNameAndFolder() {
        val files = listOf(
            found("Alpha.pdf", BookFormat.PDF, "Shared storage/Books"),
            found("Beta.epub", BookFormat.EPUB, "Shared storage/Novels"),
            found("Gamma.txt", BookFormat.TXT, "Download"),
            found("Delta.cbz", BookFormat.CBZ, "Comics"),
        )
        assertEquals(listOf("Alpha.pdf"), LibraryFind.rows(files, emptyList(), setOf(BookFormat.PDF), "", LibrarySort.Newest).map { it.found.name })
        assertEquals(listOf("Beta.epub"), LibraryFind.rows(files, emptyList(), LibraryFind.types.toSet(), "NOVELS", LibrarySort.Newest).map { it.found.name })
        assertEquals(listOf("Alpha.pdf"), LibraryFind.rows(files, emptyList(), LibraryFind.types.toSet(), "alpha", LibrarySort.Newest).map { it.found.name })
        assertTrue(LibraryFind.rows(files, emptyList(), emptySet(), "", LibrarySort.Newest).isEmpty())
    }

    @Test fun findBooksSortsByNewestNameAndSize() {
        val files = listOf(
            found("Alpha.pdf", BookFormat.PDF, size = 30, modified = 10),
            found("Charlie.epub", BookFormat.EPUB, size = 10, modified = 30),
            found("Bravo.txt", BookFormat.TXT, size = 20, modified = 20),
        )
        assertEquals(listOf("Charlie.epub", "Bravo.txt", "Alpha.pdf"),
            LibraryFind.rows(files, emptyList(), LibraryFind.types.toSet(), "", LibrarySort.Newest).map { it.found.name })
        assertEquals(listOf("Alpha.pdf", "Bravo.txt", "Charlie.epub"),
            LibraryFind.rows(files, emptyList(), LibraryFind.types.toSet(), "", LibrarySort.Name).map { it.found.name })
        assertEquals(listOf("Alpha.pdf", "Bravo.txt", "Charlie.epub"),
            LibraryFind.rows(files, emptyList(), LibraryFind.types.toSet(), "", LibrarySort.Size).map { it.found.name })
    }

    @Test fun applyAddsTickedFilesAndRemovesUntickedShownOnesOnly() {
        val inLibrary = book("one", "One")
        val outside = book("outside", "Outside")
        val files = listOf(
            found("One.epub").copy(uri = inLibrary.uri),
            found("Two.epub"),
            found("Three.pdf", BookFormat.PDF),
        )
        val rows = LibraryFind.rows(files, listOf(inLibrary, outside), LibraryFind.types.toSet(), "", LibrarySort.Newest)
        assertTrue(rows.first { it.found.name == "One.epub" }.inLibrary)
        val plan = LibraryFind.plan(files, listOf(inLibrary, outside), LibraryFind.types.toSet(), "", LibrarySort.Newest,
            setOf(files[1].uri))
        assertEquals(listOf("Two.epub"), plan.add.map { it.name })
        assertEquals(listOf("one"), plan.remove.map { it.id })
        assertEquals(2, plan.changeCount)
        // A library book that this search does not show is never removed.
        assertFalse(plan.remove.any { it.id == outside.id })
    }

    @Test fun theCountLineSaysHowManyWereFoundAndHowManyAreInTheLibrary() {
        assertEquals("500 found · 12 in the library", LibraryFormat.countLine(500, 500, 12))
        assertEquals("20 of 500 shown · 12 in the library", LibraryFormat.countLine(500, 20, 12))
    }

    @Test fun valuesReadAsWordsWithLabels() {
        assertEquals("0 B", LibraryFormat.size(0))
        assertEquals("1.0 KB", LibraryFormat.size(1_000))
        assertEquals("1.5 MB", LibraryFormat.size(1_500_000))
        assertEquals("2.0 GB", LibraryFormat.size(2_000_000_000))
        assertEquals("42% read", LibraryFormat.progressLabel(book("a", "A", progress = 0.42)))
        assertEquals("Continue", LibraryFormat.secondaryLine(book("a", "A"), isContinue = true, missing = false))
        assertEquals("File not found", LibraryFormat.secondaryLine(book("a", "A"), isContinue = false, missing = true))
        assertEquals("by Ada", LibraryFormat.secondaryLine(book("a", "A", author = "Ada"), isContinue = false, missing = false))
        assertTrue(LibraryFormat.cellDescription(book("a", "A", author = "Ada", progress = 0.5), true, false)
            .contains("50% read. Continue"))
        assertEquals("10 Nov 2026", LibraryFormat.date(1_794_268_800_000, ZoneOffset.UTC))
        val details = LibraryFormat.details(book("a", "A", author = "Ada", progress = 0.5), true, ZoneOffset.UTC)
        assertTrue(details.any { it.first == "Title" && it.second == "A" })
        assertTrue(details.any { it.first == "Author" && it.second == "Ada" })
        assertTrue(details.any { it.first == "Status" && it.second == "File not found" })
        assertEquals(listOf("PDF", "EPUB", "Text", "Comics"), LibraryFind.types.map(LibraryFind::typeLabel))
        assertEquals(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10))
    }

    @Test fun thePageOptionsCarryTheFindBooksAction() {
        assertEquals(SettingsPage.HubPages, hubPageOptions.getValue(LibraryPage.Id).fullSettings)
        assertTrue(hubPageOptions.getValue(LibraryPage.Id).tap.contains("Find books"))
    }
}
