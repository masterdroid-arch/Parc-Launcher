package com.brightblu.parc.core.data.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPositionSessionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 10000) fun epubSurvivesCancelledUiJobsAndDiskReopen() = diskRoundTrip(BookFormat.EPUB, BookPosition(3, 1234))
    @Test(timeout = 10000) fun textSurvivesCancelledUiJobsAndDiskReopen() = diskRoundTrip(BookFormat.TXT, BookPosition(0, 7890))
    @Test(timeout = 10000) fun pdfSurvivesCancelledUiJobsAndDiskReopen() = diskRoundTrip(BookFormat.PDF, BookPosition(page = 42, pageOffset = 0.375))

    private fun diskRoundTrip(format: BookFormat, position: BookPosition) = runBlocking {
        val file = File(temp.root, "${format.name}.preferences_pb")
        val storeJob = SupervisorJob()
        val store = LibraryStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + storeJob), produceFile = { file }))
        val book = Book("stable-id", "/books/book.${format.name}", "Book", format = format)
        store.add(book)
        val session = ReaderPositionSession { place, progress -> store.savePosition(book.id, place, progress, 200) }
        session.update(BookPosition(), 0.0)
        session.persist() // A completed page turn is already durable.
        assertEquals(BookPosition(), store.books.first().single().position)
        val doc = if (format == BookFormat.EPUB) ReaderBookDoc(book.id, book.title, "",
            (0..3).map { ReaderChapter(it, "Chapter $it", "<p>${"x".repeat(2000)}</p>", "x".repeat(2000)) }, emptyList())
        else ReaderBookDoc.plainText(book.id, book.title, "", "x".repeat(12000))
        val progress = if (format == BookFormat.PDF) ReaderPdfMath.progress(position, 100)
            else ReaderPositionMath.progress(doc, position)
        session.update(position, progress)
        val uiJob = SupervisorJob()
        val uiDispatcher = StandardTestDispatcher()
        val ui = CoroutineScope(uiDispatcher + uiJob)
        ui.launch { session.persist() } // Never scheduled: simulate disposal before the save runs.
        uiJob.cancel()
        uiDispatcher.scheduler.runCurrent()
        uiJob.join()
        session.flush() // The same synchronous boundary used by ON_PAUSE and close.
        storeJob.cancelAndJoin()

        // No in-memory preferences, flow, session or document cache is reused after the kill.
        val reopenedJob = SupervisorJob()
        try {
            val reopened = LibraryStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + reopenedJob), produceFile = { file }))
            val restored = reopened.books.first().single()
            assertEquals(position, restored.position)
            assertEquals(progress, restored.progress, 0.0)
            if (format == BookFormat.PDF) {
                val heights = List(100) { 1536f }
                val tops = List(100) { it * 1544f }
                val scroll = ReaderPdfMath.scrollFor(tops, heights, restored.position.page, restored.position.pageOffset)
                assertEquals(position, ReaderPdfMath.positionAt(tops, heights, scroll))
            } else {
                assertEquals(position, ReaderPositionMath.clamp(doc, restored.position))
            }
            assertEquals(200L, restored.lastOpenedAt)
            assertEquals(restored, ReaderBookIdentity.find(book, listOf(restored)))
        } finally { reopenedJob.cancelAndJoin() }
    }

    @Test(timeout = 10000) fun anInFlightOlderTurnCannotOverwriteThePauseCheckpoint() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<BookPosition>()
        val session = ReaderPositionSession { place, _ ->
            if (writes.isEmpty()) { entered.complete(Unit); release.await() }
            writes += place
        }
        session.update(BookPosition(0, 10), 0.1)
        val old = launch { session.persist() }
        entered.await()
        val latest = BookPosition(0, 200)
        session.update(latest, 0.2)
        val pause = async(Dispatchers.IO) { session.flush() }
        release.complete(Unit)
        old.join()
        pause.await()
        session.persist() // Even an old queued callback reads the latest checkpoint.
        assertEquals(listOf(BookPosition(0, 10), latest, latest), writes)
    }

    @Test(timeout = 10000) fun libraryIdentityUsesStoredIdForFileUrisAndContentUris() {
        val stored = Book("imported-id", "/books/A book.epub", "Book", format = BookFormat.EPUB,
            position = BookPosition(2, 500))
        val opened = stored.copy(id = "open-with-id", uri = "file:///books/A%20book.epub", position = BookPosition())
        assertEquals(stored, ReaderBookIdentity.find(opened, listOf(stored)))
        val content = stored.copy(uri = "content://provider/42")
        assertEquals(content, ReaderBookIdentity.find(content.copy(id = "other"), listOf(content)))
        assertNull(ReaderBookIdentity.find(opened.copy(uri = "/books/another.epub"), listOf(content)))
    }
}
