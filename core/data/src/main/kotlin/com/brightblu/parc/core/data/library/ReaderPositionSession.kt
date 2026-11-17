package com.brightblu.parc.core.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A pause is a durability boundary, independent of the composition's cancellable save jobs. */
class ReaderPositionSession(private val write: suspend (BookPosition, Double) -> Unit) {
    private data class Snapshot(val position: BookPosition, val progress: Double)
    @Volatile private var latest: Snapshot? = null
    private val lock = Mutex()

    fun update(position: BookPosition, progress: Double) {
        latest = Snapshot(position, progress)
    }

    suspend fun persist() = withContext(Dispatchers.IO) {
        // Both acquiring and releasing the lock must run off Main: pause may be blocking Main
        // while a previous write finishes. Never wait for a Main continuation to release it.
        lock.withLock {
            // An older queued page-turn must never overwrite the pause save.
            latest?.let { write(it.position, it.progress) }
        }
    }

    fun flush() = runBlocking(Dispatchers.IO) { persist() }
}

/** File paths and file: open-with URIs identify the same library row. Content URIs stay opaque. */
object ReaderBookIdentity {
    fun location(uri: String): String = if (uri.startsWith("file:")) {
        runCatching { java.io.File(java.net.URI(uri)).absolutePath }.getOrDefault(uri)
    } else uri

    fun find(book: Book, books: List<Book>): Book? = books.firstOrNull {
        it.id == book.id || location(it.uri) == location(book.uri)
    }
}
