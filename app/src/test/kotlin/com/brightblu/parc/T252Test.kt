package com.brightblu.parc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.FoundBook
import com.brightblu.parc.core.data.library.LibraryAutoAdd
import com.brightblu.parc.core.data.library.LibraryShelf
import com.brightblu.parc.core.data.library.LibraryShelfRules
import com.brightblu.parc.core.data.library.LibraryShelvesDocument
import com.brightblu.parc.core.data.library.LibraryShelvesJson
import com.brightblu.parc.core.data.library.LibraryStore
import com.brightblu.parc.core.data.library.ShelfRule
import com.brightblu.parc.core.data.library.ShelfRuleKind
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T252: shelf rules, the first-use Comics shelf and auto-add from the folders the user chose. */
class T252Test {
    private fun book(uri: String, title: String = "Title", format: BookFormat = BookFormat.EPUB) =
        Book(uri.hashCode().toString(), uri, title, "", format, 10, 0)

    private fun found(uri: String, name: String = "a.epub", modified: Long = 1) =
        FoundBook(uri, name, "Folder", 10, modified, BookFormat.EPUB, "/root")

    @Test
    fun eachRuleKindMatchesItsOwnThing() {
        val cbz = book("/sdcard/Comics/north.cbz", "North", BookFormat.CBZ)
        val epub = book("/sdcard/Books/rain.epub", "A Short History of Rain")
        val tree = book("content://auth/tree/primary%3AReading/document/primary%3AReading%2Fmoon.epub", "Moon")
        assertTrue(LibraryShelfRules.matches(ShelfRule(ShelfRuleKind.Type, "CBZ"), cbz))
        assertFalse(LibraryShelfRules.matches(ShelfRule(ShelfRuleKind.Type, "CBZ"), epub))
        assertTrue(LibraryShelfRules.matches(ShelfRule(ShelfRuleKind.Folder, "/sdcard/Comics"), cbz))
        assertFalse(LibraryShelfRules.matches(ShelfRule(ShelfRuleKind.Folder, "/sdcard/Com"), cbz))
        assertTrue(LibraryShelfRules.matches(ShelfRule(ShelfRuleKind.Folder,
            "content://auth/tree/primary%3AReading"), tree))
        assertTrue(LibraryShelfRules.matches(ShelfRule(ShelfRuleKind.Name, "rain"), epub))
        assertTrue(LibraryShelfRules.matches(ShelfRule(ShelfRuleKind.Name, "MOON"), tree))
        assertFalse(LibraryShelfRules.matches(ShelfRule(ShelfRuleKind.Name, "rain"), cbz))
    }

    @Test
    fun rulesAreCleanedLimitedAndNeverDuplicated() {
        val doc = LibraryShelvesDocument(listOf(LibraryShelf("shelf-1", "Mine")))
        assertNull(LibraryShelfRules.addRule(doc, "shelf-1", ShelfRule(ShelfRuleKind.Type, "DOCX")))
        assertNull(LibraryShelfRules.addRule(doc, "shelf-1", ShelfRule(ShelfRuleKind.Name, "   ")))
        assertNull(LibraryShelfRules.addRule(doc, "missing", ShelfRule(ShelfRuleKind.Type, "pdf")))
        val one = requireNotNull(LibraryShelfRules.addRule(doc, "shelf-1", ShelfRule(ShelfRuleKind.Type, "pdf")))
        assertEquals(listOf(ShelfRule(ShelfRuleKind.Type, "PDF")), one.shelf("shelf-1")!!.rules)
        assertEquals(one, LibraryShelfRules.addRule(one, "shelf-1", ShelfRule(ShelfRuleKind.Type, "PDF")))
        assertTrue(LibraryShelfRules.removeRule(one, "shelf-1", 0).shelf("shelf-1")!!.rules.isEmpty())
        assertEquals(one, LibraryShelfRules.removeRule(one, "shelf-1", 5))
        assertEquals("Name has “night”", LibraryShelfRules.describe(ShelfRule(ShelfRuleKind.Name, "night")))
        assertEquals(1, LibraryShelfRules.matchCount(ShelfRule(ShelfRuleKind.Type, "EPUB"),
            listOf(book("/a.epub"), book("/b.pdf", format = BookFormat.PDF))))
    }

    @Test
    fun rulesAddToShelvesButNeverTakeABookOff() {
        val doc = LibraryShelvesDocument(listOf(LibraryShelf("shelf-1", "Comics",
            listOf(ShelfRule(ShelfRuleKind.Type, "CBZ")))))
        val cbz = book("/x.cbz", format = BookFormat.CBZ)
        assertEquals(listOf("shelf-1"), LibraryShelfRules.applyRules(doc, cbz).shelves)
        val hand = book("/y.epub").copy(shelves = listOf("shelf-9"))
        assertEquals(hand, LibraryShelfRules.applyRules(doc, hand))
    }

    @Test
    fun firstUseOffersTheComicsShelfOnceAndDeletingItSticks() = runTest {
        val seeded = LibraryShelfRules.seedDefaults(LibraryShelvesDocument())
        assertTrue(seeded.seeded)
        assertEquals(listOf("Comics"), seeded.shelves.map { it.name })
        assertEquals(listOf(ShelfRule(ShelfRuleKind.Type, "CBZ")), seeded.shelves.single().rules)
        assertEquals(seeded, LibraryShelfRules.seedDefaults(seeded))
        // A shelf already named Comics is left alone but the offer still counts as made.
        val taken = LibraryShelfRules.seedDefaults(LibraryShelvesDocument(listOf(LibraryShelf("shelf-1", "comics"))))
        assertEquals(1, taken.shelves.size)
        assertTrue(taken.seeded)
        assertEquals(seeded, LibraryShelvesJson.decode(LibraryShelvesJson.encode(seeded)))

        val memory = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val store = LibraryStore(memory, StandardTestDispatcher(testScheduler))
        store.add(book("/old.cbz", format = BookFormat.CBZ))
        assertTrue(store.ensureDefaultShelves())
        val comics = store.shelves.first().shelves.single()
        assertEquals(listOf(comics.id), store.books.first().single().shelves)
        assertFalse(store.ensureDefaultShelves())
        store.deleteShelf(comics.id)
        store.ensureDefaultShelves()
        assertTrue(store.shelves.first().shelves.isEmpty())
        // A new book lands on a shelf through its rules.
        val shelf = requireNotNull(store.createShelf("Rain"))
        assertTrue(store.addRule(shelf.id, ShelfRule(ShelfRuleKind.Name, "rain")))
        store.add(book("/new.epub", "Rain Diary"))
        store.add(book("/other.epub", "Other"))
        assertEquals(listOf("Rain Diary"), store.books.first().filter { shelf.id in it.shelves }.map { it.title })
        // Adding a rule sorts what is already there; sorting again changes nothing.
        assertTrue(store.addRule(shelf.id, ShelfRule(ShelfRuleKind.Name, "other")))
        assertEquals(2, store.books.first().count { shelf.id in it.shelves })
        assertEquals(0, store.sortByRules())
    }

    @Test
    fun autoAddFirstRecordsThenAddsOnlyNewFilesAndNeverRemovedOnes() {
        val a = found("/r/a.epub")
        val b = found("/r/b.epub")
        val first = LibraryAutoAdd.plan(listOf(a, b), null, emptyList())
        assertTrue(first.first)
        assertTrue(first.add.isEmpty())
        assertEquals(setOf(LibraryAutoAdd.key(a.uri), LibraryAutoAdd.key(b.uri)), first.seen)

        val c = found("/r/c.epub", modified = 9)
        val second = LibraryAutoAdd.plan(listOf(a, b, c), first.seen, emptyList())
        assertEquals(listOf(c.uri), second.add.map { it.uri })
        // a was seen but is not in the library (the user removed it): it is not added again.
        val third = LibraryAutoAdd.plan(listOf(a, b, c), second.seen, listOf(book(c.uri)))
        assertTrue(third.add.isEmpty())
    }

    @Test
    fun autoAddTakesAtMostATwentyFilesPerPass() {
        val files = (1..45).map { found("/r/$it.epub", modified = it.toLong()) }
        val pass1 = LibraryAutoAdd.plan(files, emptySet(), emptyList())
        assertEquals(LibraryAutoAdd.MaxPerPass, pass1.add.size)
        val library = pass1.add.map { book(it.uri) }
        val pass2 = LibraryAutoAdd.plan(files, pass1.seen, library)
        assertEquals(LibraryAutoAdd.MaxPerPass, pass2.add.size)
        assertTrue(pass1.add.map { it.uri }.intersect(pass2.add.map { it.uri }.toSet()).isEmpty())
    }

    @Test
    fun oldAndMalformedShelfDocumentsStillRead() {
        val old = LibraryShelvesJson.decode("""{"shelves":[{"id":"shelf-1","name":"Old"}]}""")
        assertEquals("Old", old.shelves.single().name)
        assertTrue(old.shelves.single().rules.isEmpty())
        assertFalse(old.seeded)
        val dirty = LibraryShelvesJson.decode("""{"seeded":true,"shelves":[{"id":"shelf-1","name":"A","rules":[
            {"kind":"Type","value":"exe"},{"kind":"Name","value":"ok"},{"kind":"Name","value":"ok"}]}]}""")
        assertTrue(dirty.seeded)
        assertEquals(listOf(ShelfRule(ShelfRuleKind.Name, "ok")), dirty.shelves.single().rules)
    }

    @Test
    fun theManagerShowsRulesAndTheLibraryOnlyLooksInChosenFolders() {
        val page = File("src/main/kotlin/com/brightblu/parc/LibraryPage.kt").readText()
        assertTrue(page.contains("ShelfRulesDialog"))
        assertTrue(page.contains("Sort now"))
        assertTrue(page.contains("Matches \$count books"))
        assertTrue(page.contains("autoAddNewBooks"))
        // The scan goes through the chosen-folder list and nothing else.
        val auto = page.substringAfter("private suspend fun autoAddNewBooks").substringBefore("@Composable")
        assertTrue(auto.contains("BookFolderStore"))
        assertFalse(auto.contains("scanFolders()"))
        assertFalse(auto.contains("roots()"))
    }
}
