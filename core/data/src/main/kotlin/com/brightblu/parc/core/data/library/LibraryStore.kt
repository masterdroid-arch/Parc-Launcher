package com.brightblu.parc.core.data.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal val Context.parcLibraryStore by preferencesDataStore(
    name = "parc_library",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("parc_library"),
)

/** No scan, metadata extraction or background work starts when this store is constructed. */
class LibraryStore(
    private val store: DataStore<Preferences>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.parcLibraryStore)

    val books: Flow<List<Book>> = store.data.map { LibraryJson.decode(it[Key]) }.flowOn(io)

    /** T251: the user's own shelves, in their chosen order. The built-ins are never stored. */
    val shelves: Flow<LibraryShelvesDocument> = store.data
        .map { LibraryShelvesJson.decode(it[ShelvesKey]) }.flowOn(io)

    /** T236: bookmarks and highlights live beside the library list, per stable book id. */
    val marksDocument: Flow<ReaderMarksDocument> = store.data
        .map { ReaderMarksJson.decode(it[MarksKey]) }.flowOn(io)

    fun marks(bookId: String): Flow<ReaderBookMarks> = marksDocument.map { it.forBook(bookId) }

    /** T252: a new book also lands on every shelf whose rules it matches. */
    suspend fun add(book: Book) = withContext(io) {
        store.edit { prefs ->
            val old = LibraryJson.decode(prefs[Key])
            if (old.any { it.id == book.id || it.uri == book.uri }) return@edit
            val shelves = LibraryShelvesJson.decode(prefs[ShelvesKey])
            prefs[Key] = LibraryJson.encode(old + LibraryShelfRules.applyRules(shelves, book))
        }
        Unit
    }

    /** T252: first use only. Offers the Comics shelf once, then sorts what is already in the library. */
    suspend fun ensureDefaultShelves(): Boolean = withContext(io) {
        var changed = false
        store.edit { prefs ->
            val current = LibraryShelvesJson.decode(prefs[ShelvesKey])
            if (current.seeded) return@edit
            val next = LibraryShelfRules.seedDefaults(current)
            prefs[ShelvesKey] = LibraryShelvesJson.encode(next)
            prefs[Key] = LibraryJson.encode(LibraryShelfRules.applyRules(next, LibraryJson.decode(prefs[Key])))
            changed = true
        }
        changed
    }

    /** T252: add a rule and put every matching book on the shelf in the same edit. */
    suspend fun addRule(shelfId: String, rule: ShelfRule): Boolean = withContext(io) {
        var changed = false
        store.edit { prefs ->
            val current = LibraryShelvesJson.decode(prefs[ShelvesKey])
            val next = LibraryShelfRules.addRule(current, shelfId, rule) ?: return@edit
            if (next == current) return@edit
            prefs[ShelvesKey] = LibraryShelvesJson.encode(next)
            prefs[Key] = LibraryJson.encode(LibraryShelfRules.applyRules(next, LibraryJson.decode(prefs[Key])))
            changed = true
        }
        changed
    }

    /** Removing a rule leaves the books already on the shelf where they are. */
    suspend fun removeRule(shelfId: String, index: Int): Boolean = withContext(io) {
        var changed = false
        store.edit { prefs ->
            val current = LibraryShelvesJson.decode(prefs[ShelvesKey])
            val next = LibraryShelfRules.removeRule(current, shelfId, index)
            if (next != current) {
                prefs[ShelvesKey] = LibraryShelvesJson.encode(next)
                changed = true
            }
        }
        changed
    }

    /** Re-run every shelf's rules over the whole library; returns how many books gained a shelf. */
    suspend fun sortByRules(): Int = withContext(io) {
        var gained = 0
        store.edit { prefs ->
            val shelves = LibraryShelvesJson.decode(prefs[ShelvesKey])
            val books = LibraryJson.decode(prefs[Key])
            val next = LibraryShelfRules.applyRules(shelves, books)
            gained = next.indices.count { next[it] != books[it] }
            if (gained > 0) prefs[Key] = LibraryJson.encode(next)
        }
        gained
    }

    /** Removes only the library row. Never deletes the source file or revokes its grant. */
    suspend fun relocate(id: String, uri: String) = updateBook(id) { it.copy(uri = uri) }

    suspend fun remove(id: String) = mutate { it.filterNot { book -> book.id == id } }

    suspend fun savePosition(id: String, position: BookPosition, progress: Double, openedAt: Long) {
        require(progress.isFinite() && progress in 0.0..1.0 && openedAt >= 0)
        mutate { books -> books.map {
            if (it.id == id) it.copy(position = position, progress = progress, lastOpenedAt = openedAt) else it
        } }
    }

    /** T251: create an own shelf. Null when the name is blank, repeated or at the shelf limit. */
    suspend fun createShelf(name: String): LibraryShelf? = withContext(io) {
        var added: LibraryShelf? = null
        store.edit { prefs ->
            val current = LibraryShelvesJson.decode(prefs[ShelvesKey])
            val next = LibraryShelfRules.add(current, name) ?: return@edit
            added = next.shelves.lastOrNull()
            prefs[ShelvesKey] = LibraryShelvesJson.encode(next)
        }
        added
    }

    suspend fun renameShelf(id: String, name: String): Boolean = withContext(io) {
        var changed = false
        store.edit { prefs ->
            val current = LibraryShelvesJson.decode(prefs[ShelvesKey])
            val next = LibraryShelfRules.rename(current, id, name) ?: return@edit
            if (next != current) {
                prefs[ShelvesKey] = LibraryShelvesJson.encode(next)
                changed = true
            }
        }
        changed
    }

    suspend fun moveShelf(id: String, delta: Int): Boolean = withContext(io) {
        var changed = false
        store.edit { prefs ->
            val current = LibraryShelvesJson.decode(prefs[ShelvesKey])
            val next = LibraryShelfRules.move(current, id, delta)
            if (next != current) {
                prefs[ShelvesKey] = LibraryShelvesJson.encode(next)
                changed = true
            }
        }
        changed
    }

    /**
     * T251: delete the shelf definition and remove its id from every book in the same atomic edit.
     * The books, their places and their files are untouched.
     */
    suspend fun deleteShelf(id: String): Boolean = withContext(io) {
        var changed = false
        store.edit { prefs ->
            val current = LibraryShelvesJson.decode(prefs[ShelvesKey])
            if (current.shelf(id) == null) return@edit
            prefs[ShelvesKey] = LibraryShelvesJson.encode(LibraryShelfRules.without(current, id))
            val books = LibraryJson.decode(prefs[Key]).map { LibraryShelfRules.withoutShelf(it, id) }
            prefs[Key] = LibraryJson.encode(books)
            changed = true
        }
        changed
    }

    /** Add or remove one own shelf on one book. A missing shelf is ignored. */
    suspend fun setOnShelf(bookId: String, shelfId: String, on: Boolean): Boolean = withContext(io) {
        var changed = false
        store.edit { prefs ->
            if (LibraryShelvesJson.decode(prefs[ShelvesKey]).shelf(shelfId) == null) return@edit
            val books = LibraryJson.decode(prefs[Key])
            val next = books.map {
                if (it.id == bookId) LibraryShelfRules.withShelf(it, shelfId, on) else it
            }
            if (next != books) {
                prefs[Key] = LibraryJson.encode(next)
                changed = true
            }
        }
        changed
    }

    /** Mark finished or unread without changing the saved reading place. */
    suspend fun setFinished(id: String, finished: Boolean): Boolean = updateBook(id) {
        if (it.finished == finished && it.finishAsked) it else LibraryShelfRules.markFinished(it, finished)
    }

    /** Settle the one-time last-page question, whether the answer was yes or not now. */
    suspend fun markFinishAsked(id: String): Boolean = updateBook(id) { LibraryShelfRules.markFinishAsked(it) }

    /**
     * T346: delete one book's bookmarks and highlights, only when the user asks for this on removal.
     * Returns whether anything was there to delete; the book row and the source file are untouched.
     */
    suspend fun deleteMarks(bookId: String): Boolean = withContext(io) {
        var removed = false
        store.edit { prefs ->
            val document = ReaderMarksJson.decode(prefs[MarksKey])
            if (document.books.containsKey(bookId)) {
                prefs[MarksKey] = ReaderMarksJson.encode(document.withBook(bookId, ReaderBookMarks()))
                removed = true
            }
        }
        removed
    }

    /** T251: the reader reached the last page; the Library page can then ask its one-time question. */
    suspend fun markReachedEnd(id: String): Boolean = updateBook(id) { LibraryShelfRules.markReachedEnd(it) }

    /** Missing or revoked files stay in the list, with a label and the same remove action. */
    suspend fun entries(canRead: suspend (String) -> Boolean): List<LibraryEntry> = withContext(io) {
        books.first().map { LibraryEntry(it, !canRead(it.uri)) }
    }

    /** Atomically change one book's marks and return the stored result. */
    suspend fun updateMarks(bookId: String, change: (ReaderBookMarks) -> ReaderBookMarks): ReaderBookMarks =
        withContext(io) {
            var result = ReaderBookMarks()
            store.edit { prefs ->
                val document = ReaderMarksJson.decode(prefs[MarksKey])
                result = change(document.forBook(bookId))
                prefs[MarksKey] = ReaderMarksJson.encode(document.withBook(bookId, result))
            }
            result
        }

    private suspend fun mutate(change: (List<Book>) -> List<Book>) = withContext(io) {
        store.edit { it[Key] = LibraryJson.encode(change(LibraryJson.decode(it[Key]))) }
        Unit
    }

    private suspend fun updateBook(id: String, change: (Book) -> Book): Boolean = withContext(io) {
        var changed = false
        store.edit { prefs ->
            val books = LibraryJson.decode(prefs[Key])
            val next = books.map { if (it.id == id) change(it) else it }
            if (next != books) {
                prefs[Key] = LibraryJson.encode(next)
                changed = true
            }
        }
        changed
    }

    companion object {
        const val KeyName = "books_v1"
        const val MarksKeyName = "marks_v1"
        // T251: own shelf definitions; book membership and finished marks travel in books_v1.
        const val ShelvesKeyName = "shelves_v1"
        private val Key = stringPreferencesKey(KeyName)
        private val MarksKey = stringPreferencesKey(MarksKeyName)
        private val ShelvesKey = stringPreferencesKey(ShelvesKeyName)
    }
}
