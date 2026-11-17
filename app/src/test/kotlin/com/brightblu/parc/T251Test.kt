package com.brightblu.parc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupStores
import com.brightblu.parc.core.data.backup.FullBackup
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.LibraryJson
import com.brightblu.parc.core.data.library.LibraryShelf
import com.brightblu.parc.core.data.library.LibraryShelfIds
import com.brightblu.parc.core.data.library.LibraryShelfRules
import com.brightblu.parc.core.data.library.LibraryShelfSort
import com.brightblu.parc.core.data.library.LibraryShelvesDocument
import com.brightblu.parc.core.data.library.LibraryShelvesJson
import com.brightblu.parc.core.data.library.LibraryStore
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T251: the Library's built-in and own shelves, finished marks, sorting and the one-time question. */
class T251Test {
    private fun book(
        id: String,
        title: String = "Title $id",
        author: String = "",
        added: Long = 0,
        opened: Long? = null,
        progress: Double = 0.0,
        finished: Boolean = false,
        asked: Boolean = false,
        reachedEnd: Boolean = false,
        shelves: List<String> = emptyList(),
    ) = Book(id, "content://library/$id", title, author, BookFormat.EPUB, 1_000, added, opened, progress,
        finished = finished, finishAsked = asked, reachedEnd = reachedEnd, shelves = shelves)

    @Test
    fun readingMeansOpenedAndNotFinishedAndFinishedIsIndependentOfProgress() {
        val never = book("never")
        val opened = book("opened", opened = 10, progress = 0.3)
        val finished = book("finished", opened = 20, progress = 0.4, finished = true)
        assertEquals(listOf("opened"), LibraryShelfRules.booksFor(LibraryShelfIds.Reading,
            listOf(never, opened, finished)).map { it.id })
        assertEquals(listOf("finished"), LibraryShelfRules.booksFor(LibraryShelfIds.Finished,
            listOf(never, opened, finished)).map { it.id })
        assertEquals(listOf("never", "opened", "finished"), LibraryShelfRules.booksFor(LibraryShelfIds.All,
            listOf(never, opened, finished)).map { it.id })
    }

    @Test
    fun anOwnShelfCanHoldSeveralBooksAndABookCanBeOnSeveralShelves() {
        val document = LibraryShelvesDocument(listOf(
            LibraryShelf("shelf-1", "Evenings"),
            LibraryShelf("shelf-2", "Short reads"),
        ))
        val one = book("one", shelves = listOf("shelf-1"))
        val two = book("two", shelves = listOf("shelf-1", "shelf-2"))
        val none = book("none")
        assertEquals(listOf("one", "two"), LibraryShelfRules.booksFor("shelf-1", listOf(one, two, none)).map { it.id })
        assertEquals(listOf("two"), LibraryShelfRules.booksFor("shelf-2", listOf(one, two, none)).map { it.id })
        val added = LibraryShelfRules.withShelf(none, "shelf-2", true)
        assertEquals(listOf("shelf-2"), added.shelves)
        assertEquals(none, LibraryShelfRules.withShelf(none, "shelf-2", false))
        assertEquals(none, LibraryShelfRules.withShelf(none, LibraryShelfIds.Reading, true))
        assertEquals(listOf("all", "reading", "finished", "shelf-1", "shelf-2"),
            LibraryShelfRules.tabs(document).map { it.id })
    }

    @Test
    fun everySortChoicePutsTheBooksInAPredictableOrder() {
        val a = book("a", title = "Beta", author = "Zed", added = 10, opened = 5)
        val b = book("b", title = "Alpha", author = "", added = 30)
        val c = book("c", title = "Gamma", author = "Ada", added = 20, opened = 15)
        val books = listOf(a, b, c)
        assertEquals(listOf("c", "a", "b"), LibraryShelfRules.sorted(books, LibraryShelfSort.Recent).map { it.id })
        assertEquals(listOf("b", "a", "c"), LibraryShelfRules.sorted(books, LibraryShelfSort.Title).map { it.id })
        assertEquals(listOf("c", "a", "b"), LibraryShelfRules.sorted(books, LibraryShelfSort.Author).map { it.id })
        assertEquals(listOf("b", "c", "a"), LibraryShelfRules.sorted(books, LibraryShelfSort.Added).map { it.id })
    }

    @Test
    fun theLastPageQuestionIsAskedOnceAndFinishingKeepsThePlace() {
        assertFalse(LibraryShelfRules.shouldAskFinished(book("almost", progress = 0.99)))
        assertFalse(LibraryShelfRules.shouldAskFinished(book("last", progress = 1.0)))
        assertTrue(LibraryShelfRules.shouldAskFinished(book("reached", reachedEnd = true)))
        assertFalse(LibraryShelfRules.shouldAskFinished(book("asked", reachedEnd = true, asked = true)))
        assertFalse(LibraryShelfRules.shouldAskFinished(book("done", reachedEnd = true, finished = true)))
        assertEquals(book("reached", reachedEnd = true),
            LibraryShelfRules.markReachedEnd(book("reached")))
        val finished = LibraryShelfRules.markFinished(book("reached", progress = 0.4, reachedEnd = true), true)
        assertTrue(finished.finished && finished.finishAsked)
        assertEquals(0.4, finished.progress, 0.0)
        val unread = LibraryShelfRules.markFinished(finished, false)
        assertFalse(unread.finished)
        assertTrue(unread.finishAsked)
        assertEquals(0.4, unread.progress, 0.0)
    }

    @Test
    fun namesAreCleanAndUniqueAndOrderCanChangeWithoutLosingBooks() {
        val document = LibraryShelvesDocument(listOf(LibraryShelf("shelf-1", "Evenings")))
        assertEquals("Quiet evenings", LibraryShelfRules.checkName("  Quiet   evenings ", document).name)
        assertEquals("Give the shelf a name.", LibraryShelfRules.checkName("   ", document).error)
        assertEquals("A shelf with that name already exists.",
            LibraryShelfRules.checkName("evenings", document).error)
        assertEquals("That name belongs to a built-in shelf.",
            LibraryShelfRules.checkName("Reading", document).error)
        assertEquals("Keep the name to 48 characters.",
            LibraryShelfRules.checkName("x".repeat(49), document).error)

        val two = LibraryShelfRules.add(document, "Short reads")
        assertNotNull(two)
        assertEquals(listOf("shelf-1", "shelf-2"), two!!.shelves.map { it.id })
        assertEquals(listOf("shelf-2", "shelf-1"),
            LibraryShelfRules.move(two, "shelf-2", -1).shelves.map { it.id })
        assertEquals(listOf("Evenings", "Stories"),
            LibraryShelfRules.rename(two, "shelf-2", "Stories")!!.shelves.map { it.name })
        assertEquals(listOf("shelf-1"), LibraryShelfRules.without(two, "shelf-2").shelves.map { it.id })

        val held = book("held", shelves = listOf("shelf-1", "shelf-2"))
        assertEquals(listOf("shelf-1"), LibraryShelfRules.withoutShelf(held, "shelf-2").shelves)
    }

    @Test
    fun shelfAndBookJsonReadOldAndMalformedDataAndRoundTripTheNewFields() {
        val document = LibraryShelvesDocument(listOf(
            LibraryShelf("shelf-1", "Evenings"),
            LibraryShelf("shelf-2", "Short reads"),
        ))
        assertEquals(document, LibraryShelvesJson.decode(LibraryShelvesJson.encode(document)))
        for (raw in listOf(null, "", "{}", "[]", "broken", "{\"shelves\":null}")) {
            assertTrue(LibraryShelvesJson.decode(raw).shelves.isEmpty())
        }
        val dirty = """{"shelves":[
            {"id":"shelf-1","name":"Evenings"},
            {"id":"shelf-1","name":"Duplicate id"},
            {"id":"shelf-2","name":"evenings"},
            {"id":"all","name":"Built in"},
            {"id":"shelf-3","name":"Kept"}
        ]}"""
        assertEquals(listOf("shelf-1", "shelf-3"), LibraryShelvesJson.decode(dirty).shelves.map { it.id })

        val old = """{"id":"old","uri":"/old.txt","title":"Old","format":"TXT"}"""
        val decoded = LibraryJson.decode("""{"books":[$old]}""").single()
        assertFalse(decoded.finished)
        assertFalse(decoded.finishAsked)
        assertTrue(decoded.shelves.isEmpty())

        val held = book("held", finished = true, asked = true, shelves = listOf("shelf-1"))
        assertEquals(held, LibraryJson.decode(LibraryJson.encode(listOf(held))).single())
    }

    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test
    fun storeChangesAreAtomicAndShelvesTravelInTheLibraryBackup() = runTest {
        val source = Memory()
        val store = LibraryStore(source, StandardTestDispatcher(testScheduler))
        val shelf = requireNotNull(store.createShelf("Evenings"))
        val held = book("held", opened = 10, progress = 0.4)
        store.add(held)
        assertTrue(store.setOnShelf(held.id, shelf.id, true))
        assertTrue(store.markReachedEnd(held.id))
        assertTrue(store.setFinished(held.id, true))
        val stored = store.books.first().single()
        assertTrue(stored.reachedEnd)
        assertTrue(stored.finished && stored.finishAsked)
        assertEquals(listOf(shelf.id), stored.shelves)
        assertEquals(shelf, store.shelves.first().shelf(shelf.id))

        val export = FullBackup(FakeLayoutRepository(), listOf(BackupStores.of(BackupSpecs.library, source))).export()
        val target = Memory()
        FullBackup(FakeLayoutRepository(), listOf(BackupStores.of(BackupSpecs.library, target)))
            .apply(FullBackup(FakeLayoutRepository(), listOf(BackupStores.of(BackupSpecs.library, target))).parse(export))
        val restored = LibraryStore(target, StandardTestDispatcher(testScheduler))
        assertEquals(shelf, restored.shelves.first().shelf(shelf.id))
        assertEquals(listOf(shelf.id), restored.books.first().single().shelves)

        // Deleting the shelf removes membership but keeps the book and its finished mark.
        assertTrue(store.deleteShelf(shelf.id))
        val after = store.books.first().single()
        assertEquals(held.id, after.id)
        assertTrue(after.finished)
        assertTrue(after.shelves.isEmpty())
        assertTrue(store.shelves.first().shelves.isEmpty())
        assertFalse(store.setOnShelf(held.id, shelf.id, true))
    }

    @Test
    fun thePageAndPrivacyTextCarryTheShelvesAndTheQuietActions() {
        val page = File("src/main/kotlin/com/brightblu/parc/LibraryPage.kt").readText()
        assertTrue(page.contains("LibraryShelfRules"))
        assertTrue(page.contains("LibraryShelfBar"))
        assertTrue(page.contains("Add to shelf"))
        assertTrue(page.contains("Mark as finished?"))
        assertTrue(page.contains("Mark finished"))
        assertTrue(page.contains("Mark unread"))
        assertTrue(page.contains("Delete \${shelf.name}?"))
        assertTrue(page.contains("LibraryShelfSort.entries"))
        assertTrue(page.contains("BookActionsDialog(book, shelves"))
        val reader = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
        assertTrue(reader.contains("onLastPage"))
        assertTrue(reader.contains("markReachedEnd"))
        assertTrue(DemoLibraryShelves.shelves.isNotEmpty())
        assertTrue(DemoLibraryBooks.any { it.shelves.isNotEmpty() })
        assertTrue(DemoLibraryBooks.any { it.finished })

        val privacy = File("../PRIVACY.md").readText()
        val library = privacy.substringAfter("## Library and reader").substringBefore("## Quick toggles page")
        assertTrue(library.contains("shelves", ignoreCase = true))
        assertTrue(library.contains("finished", ignoreCase = true))
    }
}
