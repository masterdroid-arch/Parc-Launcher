package com.brightblu.parc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFolder
import com.brightblu.parc.core.data.library.BookFolderRules
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.CoverArt
import com.brightblu.parc.core.data.library.LibraryShelfRules
import com.brightblu.parc.core.data.library.LibraryStore
import com.brightblu.parc.core.data.library.ReaderBookMarks
import com.brightblu.parc.core.data.library.ReaderBookmark
import com.brightblu.parc.model.Metrics
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T346: the Library shelf's search, Continue line, percentages, removal text and folder grants. */
class T346LibraryTest {
    private fun book(
        id: String,
        title: String = "Title $id",
        author: String = "",
        uri: String = "/storage/emulated/0/Download/$id.epub",
        format: BookFormat = BookFormat.EPUB,
        opened: Long? = null,
        progress: Double = 0.0,
        finished: Boolean = false,
        reachedEnd: Boolean = false,
    ) = Book(id, uri, title, author, format, 1_000, 1_000, opened, progress,
        finished = finished, finishAsked = finished, reachedEnd = reachedEnd)

    @Test
    fun shelfSearchMatchesTitleAuthorAndFileNameWithoutCase() {
        val long = book("a", title = "Um Defeito de Cor", author = "Ana Maria", uri = "/books/defeito.epub")
        val quiet = book("b", title = "The Quiet Coast", author = "M. R. Vale", uri = "/books/Quiet-Coast.epub")
        val books = listOf(long, quiet)

        assertEquals(books, LibraryShelfRules.search(books, ""))
        assertEquals(books, LibraryShelfRules.search(books, "   "))
        assertEquals(listOf(long), LibraryShelfRules.search(books, "defeito"))
        assertEquals(listOf(long), LibraryShelfRules.search(books, "ANA"))
        assertEquals(listOf(quiet), LibraryShelfRules.search(books, "quiet"))
        assertEquals(listOf(quiet), LibraryShelfRules.search(books, " quiet "))
        assertTrue(LibraryShelfRules.search(books, "nothing here").isEmpty())
    }

    @Test
    fun continueIsTheLastOpenedBookThatStillHasAPlaceAfterTheCover() {
        val unopened = book("unopened", progress = 0.0)
        val finished = book("finished", opened = 50, progress = 1.0, finished = true)
        val lastPage = book("last", opened = 40, progress = 1.0)
        val reached = book("reached", opened = 35, progress = 0.5, reachedEnd = true)
        val reading = book("reading", opened = 30, progress = 0.42)
        val older = book("older", opened = 20, progress = 0.2)
        val books = listOf(unopened, finished, lastPage, reached, older, reading)

        assertEquals("reading", LibraryFormat.continueBook(books)?.id)
        assertNull(LibraryFormat.continueBook(listOf(unopened, finished, lastPage, reached)))
        assertNull(LibraryFormat.continueBook(emptyList()))
    }

    @Test
    fun oneLineUnderACoverSaysContinueWithAPercentageOrTheLastPage() {
        val continued = book("a", author = "Ada", opened = 10, progress = 0.42)
        assertEquals("Continue · 42%", LibraryFormat.shelfSubline(continued, isContinue = true, missing = false))
        assertEquals("File not found", LibraryFormat.shelfSubline(continued, isContinue = false, missing = true))
        assertEquals("Finished", LibraryFormat.shelfSubline(
            book("b", opened = 10, progress = 0.42, finished = true), isContinue = false, missing = false))
        assertEquals("Last page", LibraryFormat.shelfSubline(
            book("c", opened = 10, progress = 1.0), isContinue = false, missing = false))
        assertEquals("42% · Ada", LibraryFormat.shelfSubline(continued, isContinue = false, missing = false))
        assertEquals("42% · PDF", LibraryFormat.shelfSubline(
            book("d", format = BookFormat.PDF, progress = 0.42), isContinue = false, missing = false))
        assertEquals("by Ada", LibraryFormat.shelfSubline(
            book("e", author = "Ada"), isContinue = false, missing = false))
        assertEquals("EPUB · 1.0 KB", LibraryFormat.shelfSubline(
            book("f"), isContinue = false, missing = false))
    }

    @Test
    fun detailsCarryTheWholeTitleTheFileAndTheReachedLastPage() {
        val longTitle = "The Cartographer's Guide to Quiet Afternoons and Every Small Weather"
        val reached = book("a", title = longTitle, author = "Ada",
            uri = "content://com.android.externalstorage.documents/tree/primary%3ADownload/" +
                "document/primary%3ADownload%2FMy%20Book.epub",
            progress = 1.0, reachedEnd = true)
        val details = LibraryFormat.details(reached, missing = false, zone = ZoneOffset.UTC)
        assertEquals(longTitle, details.first { it.first == "Title" }.second)
        assertEquals("My Book.epub", details.first { it.first == "File" }.second)
        assertEquals("Reached last page", details.first { it.first == "Status" }.second)
        assertEquals("100% read", details.first { it.first == "Progress" }.second)

        val missing = LibraryFormat.details(reached, missing = true, zone = ZoneOffset.UTC)
        assertEquals("File not found", missing.first { it.first == "Status" }.second)
    }

    @Test
    fun applyNamesAddsAndRemovalsAndSaysMarksAreKept() {
        assertEquals("Add", LibraryFind.applyLabel(0, 0))
        assertEquals("Add 3", LibraryFind.applyLabel(3, 0))
        assertEquals("Remove 1", LibraryFind.applyLabel(0, 1))
        assertEquals("Remove 6", LibraryFind.applyLabel(0, 6))
        assertEquals("Update", LibraryFind.applyLabel(2, 3))

        assertEquals("6 already in library, Remove 6 books · marks kept", LibraryFind.summaryLabel(6, 0, 6))
        assertEquals("3 new, 2 already in library, Remove 2 books · marks kept",
            LibraryFind.summaryLabel(5, 3, 2))
        assertEquals("4 already in library", LibraryFind.summaryLabel(4, 0, 0))
        assertEquals("Remove 2 books · marks kept", LibraryFind.barSummary(5, 0, 2))
        assertEquals("Remove 1 book · marks kept", LibraryFind.barSummary(1, 0, 1))
        assertEquals("3 new, 2 already in library, Remove 2 books · marks kept",
            LibraryFind.barSummary(5, 3, 2))
        assertEquals("4 already in library", LibraryFind.barSummary(4, 0, 0))
    }

    @Test
    fun aForgottenFolderKeepsItsGrantWhileALibraryBookStillLivesUnderIt() {
        val keep = BookFolder("content://tree/primary%3ADocuments", "Documents", tree = true)
        val release = BookFolder("content://tree/primary%3ADownload", "Download", tree = true)
        val plain = BookFolder("/storage/emulated/0/Books", "Shared storage/Books", tree = false)
        val retained = listOf(
            "content://tree/primary%3ADocuments/document/primary%3ADocuments%2Fkept.epub",
            "/storage/emulated/0/Books/plain.epub",
        )
        assertEquals(listOf(release.location),
            BookFolderRules.releaseableGrants(listOf(keep, release, plain), retained))
        assertEquals(emptyList<String>(),
            BookFolderRules.releaseableGrants(listOf(keep), retained))
        assertEquals(listOf(keep.location),
            BookFolderRules.releaseableGrants(listOf(keep), emptyList()))
    }

    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test
    fun deletingMarksIsSeparateAndReportsWhetherAnythingWasThere() = runTest {
        val store = LibraryStore(Memory(), StandardTestDispatcher(testScheduler))
        val held = book("held", opened = 10, progress = 0.4)
        store.add(held)
        store.updateMarks(held.id) {
            ReaderBookMarks(bookmarks = listOf(ReaderBookmark("mark-1", chapter = 2, characterOffset = 40)))
        }
        assertFalse(store.marks(held.id).first().isEmpty)

        assertTrue(store.deleteMarks(held.id))
        assertTrue(store.marks(held.id).first().isEmpty)
        assertFalse(store.deleteMarks(held.id))
        // Removing marks never removes the book row itself.
        assertEquals(listOf(held.id), store.books.first().map { it.id })
    }

    @Test
    fun aBlankLightPageIsNoCoverAndPrintedPixelsKeepOne() {
        val white = IntArray(1_000) { 0xFFFFFFFF.toInt() }
        assertTrue(CoverArt.isBlank(white))
        val withPrint = IntArray(1_000) { if (it % 100 == 0) 0xFF202020.toInt() else 0xFFFFFFFF.toInt() }
        assertFalse(CoverArt.isBlank(withPrint))
        assertFalse(CoverArt.isBlank(IntArray(0)))
        val dark = IntArray(100) { 0xFF101010.toInt() }
        assertFalse(CoverArt.isBlank(dark))
    }

    @Test
    fun theShelfAndFinderWireSearchUndoMarksAndFolderRelease() {
        val page = File("src/main/kotlin/com/brightblu/parc/LibraryPage.kt").readText()
        assertTrue(page.contains("LibraryShelfRules.search(ordered, query)"))
        assertTrue(page.contains("HubSearchField(query, \"Search title, author or file\""))
        assertTrue(page.contains("LibraryShelfSearchButton"))
        assertTrue(page.contains("LibraryFormat.shelfSubline"))
        assertTrue(page.contains("LibraryFormat.continueBook"))
        assertTrue(page.contains("RemoveBookDialog"))
        assertTrue(page.contains("LibraryAction(\"Undo\""))
        assertTrue(page.contains("Also delete its bookmarks and highlights"))
        assertTrue(page.contains("releasePersistableUriPermission"))
        assertTrue(page.contains("bookFindSelectionBarExtraDp"))
        assertTrue(page.contains("LibraryFind.applyLabel"))
        assertTrue(page.contains("LibraryFind.barSummary"))
        assertFalse(page.contains("Removed from library. The file was not deleted."))
        assertFalse(page.contains("style = hudValueStyle(skin), maxLines = 4"))

        val loader = File("../core/data/src/main/kotlin/com/brightblu/parc/core/data/library/BookMetadataLoader.kt")
            .readText()
        assertTrue(loader.contains("CoverArt.isBlank"))
        assertTrue(Metrics.bookFindSelectionBarExtraDp >= Metrics.minTouchDp)
        assertTrue(Metrics.bookFindSelectionBarExtraDp > 0f)
    }
}
