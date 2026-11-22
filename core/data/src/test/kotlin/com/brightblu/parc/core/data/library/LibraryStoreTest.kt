package com.brightblu.parc.core.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupStores
import com.brightblu.parc.core.data.backup.FullBackup
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LibraryStoreTest {
    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val book = Book("id", "content://documents/book", "A book", "A writer", BookFormat.EPUB,
        1234, 10, 20, 0.4, BookPosition(2, 400, 5, 0.25))

    @Test fun everyFieldAndFormatRoundTrips() {
        val books = BookFormat.entries.map { book.copy(id = it.name, uri = "/${it.name}", format = it) }
        assertEquals(books, LibraryJson.decode(LibraryJson.encode(books)))
    }

    @Test fun oldEmptyUnknownAndMalformedData() {
        for (text in listOf(null, "", "{}", "[]", "broken", "{\"books\":null}")) {
            assertTrue(LibraryJson.decode(text).isEmpty())
        }
        val old = """{"id":"old","uri":"/old.txt","title":"Old","format":"TXT","future":42}"""
        val decoded = LibraryJson.decode("""{"books":[$old,{},$old,{"id":"bad","format":"FUTURE"}]}""")
        assertEquals(listOf(Book("old", "/old.txt", "Old", format = BookFormat.TXT)), decoded)
        assertEquals(decoded, LibraryJson.decode("[$old]"))
    }

    @Test fun invalidProgressAndPositionAreRejected() {
        for (progress in listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(runCatching { book.copy(progress = progress) }.isFailure)
        }
        assertTrue(runCatching { BookPosition(characterOffset = -1) }.isFailure)
        assertTrue(runCatching { BookPosition(pageOffset = Double.NaN) }.isFailure)
        assertTrue(LibraryJson.decode(LibraryJson.encode(listOf(book)).replace("\"progress\":0.4", "\"progress\":2.0")).isEmpty())
    }

    @Test fun addDoesNotResetPositionAndRemoveOnlyRemovesSelectedRow() = runTest {
        val memory = Memory()
        val store = LibraryStore(memory, StandardTestDispatcher(testScheduler))
        store.add(book)
        store.add(book.copy(progress = 0.0))
        store.add(book.copy(id = "duplicate-uri"))
        assertEquals(listOf(book), store.books.first())
        val position = BookPosition(3, 700)
        store.savePosition(book.id, position, 0.7, 100)
        assertEquals(book.copy(position = position, progress = 0.7, lastOpenedAt = 100),
            LibraryStore(memory).books.first().single())
        store.savePosition("not-added", BookPosition(), 0.0, 200)
        assertEquals(1, store.books.first().size)
        store.remove("unknown")
        store.remove(book.id)
        assertTrue(store.books.first().isEmpty())
    }

    @Test fun locatingAnEpubChangesOnlyItsUri() = runTest {
        val store = LibraryStore(Memory(), StandardTestDispatcher(testScheduler))
        store.add(book)
        val mark = ReaderBookmark("saved", chapter = book.position.chapter,
            characterOffset = book.position.characterOffset, snippet = "Passage", createdAt = 1)
        store.updateMarks(book.id) { it.copy(bookmarks = listOf(mark)) }
        val uri = "content://documents/relocated"
        store.relocate(book.id, uri)
        assertEquals(listOf(book.copy(uri = uri)), store.books.first())
        assertEquals(listOf(mark), store.marks(book.id).first().bookmarks)
    }

    @Test fun missingFileIsKeptFlaggedAndCanBeRemoved() = runTest {
        val store = LibraryStore(Memory(), StandardTestDispatcher(testScheduler))
        store.add(book)
        val missing = store.entries { false }.single()
        assertEquals(book, missing.book)
        assertTrue(missing.fileNotFound)
        assertEquals("File not found", missing.statusLabel)
        assertEquals("Remove from library", missing.removeActionLabel)
        assertEquals(listOf(book), store.books.first())
        assertNull(store.entries { true }.single().statusLabel)
        store.remove(missing.book.id)
        assertTrue(store.books.first().isEmpty())
    }

    @Test fun backupRoundTripContainsListAndPositionButNoCovers() = runTest {
        val source = Memory()
        val store = LibraryStore(source, StandardTestDispatcher(testScheduler))
        store.add(book)
        val export = FullBackup(FakeLayoutRepository(), listOf(BackupStores.of(BackupSpecs.library, source))).export()
        assertFalse(export.contains("cover"))
        val target = Memory()
        val backup = FullBackup(FakeLayoutRepository(), listOf(BackupStores.of(BackupSpecs.library, target)))
        assertEquals(1, backup.apply(backup.parse(export)).settings)
        assertEquals(listOf(book), LibraryStore(target).books.first())
        assertEquals(listOf(LibraryStore.KeyName, LibraryStore.MarksKeyName, LibraryStore.ShelvesKeyName),
            BackupSpecs.library.keys.map { it.name })
        assertTrue(BackupSpecs.library in BackupSpecs.all)
        // A pre-library backup leaves the current list alone.
        val old = FullBackup(FakeLayoutRepository(), emptyList()).export()
        backup.apply(backup.parse(old))
        assertEquals(listOf(book), LibraryStore(target).books.first())
    }

    @Test fun marksAreStoredPerBookAndTravelInTheLibraryBackupSection() = runTest {
        val memory = Memory()
        val store = LibraryStore(memory, StandardTestDispatcher(testScheduler))
        store.add(book)
        store.updateMarks(book.id) {
            it.copy(bookmarks = listOf(ReaderBookmark("b", chapter = 1, characterOffset = 2, snippet = "a place")),
                highlights = listOf(ReaderHighlight("h", 1, 3, 9, text = "some words")))
        }
        assertEquals(1, store.marks(book.id).first().bookmarks.size)
        assertTrue(store.marks("other").first().isEmpty)
        val export = FullBackup(FakeLayoutRepository(), listOf(BackupStores.of(BackupSpecs.library, memory))).export()
        val target = Memory()
        FullBackup(FakeLayoutRepository(), listOf(BackupStores.of(BackupSpecs.library, target)))
            .apply(FullBackup(FakeLayoutRepository(), listOf(BackupStores.of(BackupSpecs.library, target))).parse(export))
        assertEquals(store.marks(book.id).first(), LibraryStore(target).marks(book.id).first())
        store.updateMarks(book.id) { ReaderBookMarks() }
        assertTrue(store.marks(book.id).first().isEmpty)
    }

    @Test fun fiveHundredBooksFitBackupWithoutTruncatingPositions() {
        val books = (0..599).map { book.copy(id = "id$it", uri = "content://provider/$it", position = BookPosition(it, it * 20)) }
        val json = LibraryJson.encode(books)
        assertTrue(json.length <= BackupSpecs.library.keys.first { it.name == LibraryStore.KeyName }.maxLength)
        assertEquals(books, LibraryJson.decode(json))
    }
}
