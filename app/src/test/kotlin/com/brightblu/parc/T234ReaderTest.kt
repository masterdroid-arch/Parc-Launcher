package com.brightblu.parc

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.brightblu.parc.core.data.library.*
import com.brightblu.parc.model.Skin
import com.brightblu.parc.reader.ReaderOpenPositionsStore
import com.brightblu.parc.reader.ReaderWebDocument
import com.brightblu.parc.reader.readerColors
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class T234ReaderTest {
    /** apply changes memory only; a simulated process death discards it. commit reaches disk. */
    private class DiskPreferences {
        private val disk = linkedMapOf<String, Any?>()
        var commits = 0
        fun newProcess(): Context = object : ContextWrapper(null) {
            private val memory = FakeSharedPreferences().also { it.data.putAll(disk) }
            private val preferences = object : SharedPreferences by memory {
                override fun edit(): SharedPreferences.Editor {
                    val editor = memory.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun apply() { editor.apply() }
                        override fun commit(): Boolean {
                            editor.commit()
                            disk.clear()
                            disk.putAll(memory.data)
                            commits++
                            return true
                        }
                    }
                }
            }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
    }

    @Test fun openWithPositionsForEveryFormatAreCommittedBeforePauseReturns() {
        val disk = DiskPreferences()
        val positions = mapOf(BookFormat.EPUB to BookPosition(4, 912),
            BookFormat.TXT to BookPosition(0, 18123),
            BookFormat.PDF to BookPosition(page = 37, pageOffset = 0.456))
        positions.forEach { (format, position) ->
            val hash = ReaderOpenHash.of("book.${format.name}", 12345)
            val store = ReaderOpenPositionsStore(disk.newProcess()) { 1000L }
            val session = ReaderPositionSession { p, _ -> store.save(hash, p) }
            session.update(position, 0.5)
            session.flush()
            assertEquals(position, ReaderOpenPositionsStore(disk.newProcess()) { 1001L }.position(hash))
        }
        assertEquals(3, disk.commits)
    }

    @Test fun savedPositionUpdatesShelfProgressAndContinueForEveryFormat() = runBlocking {
        val memory = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val library = LibraryStore(memory)
        val other = Book("other", "/other", "Other", format = BookFormat.TXT, lastOpenedAt = 50)
        library.add(other)
        for (format in listOf(BookFormat.EPUB, BookFormat.TXT, BookFormat.PDF)) {
            val book = Book(format.name, "/${format.name}", "Book", format = format)
            library.add(book)
            val position = if (format == BookFormat.PDF) BookPosition(page = 4, pageOffset = .5)
                else BookPosition(characterOffset = 50)
            val session = ReaderPositionSession { place, progress ->
                library.savePosition(book.id, place, progress, 100 + listOf(BookFormat.EPUB, BookFormat.TXT, BookFormat.PDF).indexOf(format).toLong())
            }
            session.update(position, .5)
            session.flush()
            val continued = LibraryFormat.order(LibraryStore(memory).books.first()).first()
            assertEquals(book.id, continued.id)
            assertEquals(position, continued.position)
            assertEquals("50% read", LibraryFormat.progressLabel(continued))
            assertEquals("Continue", LibraryFormat.secondaryLine(continued, true, false))
        }
    }

    @Test fun restoreWaitsForImagesAndFontsAndCannotBeResetByAnOpeningTimer() {
        val doc = ReaderBookDoc.plainText("id", "Title", "", "x".repeat(2000))
        val html = ReaderWebDocument.build(doc, ReaderSettings(),
            readerColors(ReaderPageColor.Light, Skin.Midnight), 360f, 640f, BookPosition(0, 1000), 7)
        assertTrue(html.contains("var start={chapter:0,offset:1000}"))
        assertTrue(html.contains("document.fonts.ready"))
        assertTrue(html.contains("addEventListener('load',loaded,{once:true})"))
        assertTrue(html.contains("if(initialized)return"))
        assertFalse(html.contains("setTimeout"))
        // T236 adds caretRangeFromPoint for a user's long-press selection; page-start mapping still
        // uses the first visible glyph, so the T234 rule is unchanged where it applies.
        assertTrue(html.contains("function caretAtPage()"))
        assertTrue(html.contains("Reader.moved(page,current.chapter,current.offset,progress(),7)"))
        // Used by the standalone JS regression harness too; no Android WebView is claimed here.
        File("build/t234-reader.html").apply { parentFile.mkdirs(); writeText(html) }
    }

    @Test fun lifecycleAndDisplayPathsKeepTheDurabilityAndWholeCoverRules() {
        val root = File("src/main/kotlin/com/brightblu/parc")
        val host = File(root, "reader/ReaderScreen.kt").readText()
        assertTrue(host.contains("ReaderBookIdentity.find(request.book, library.books.first())"))
        assertTrue(host.contains("initialSettings = settingsStore.settings.first()"))
        assertTrue(host.substringAfter("Lifecycle.Event.ON_PAUSE").substringBefore("owner.lifecycle")
            .contains("flushPosition()"))
        assertTrue(host.contains("version != generation.get()"))
        val pdf = File(root, "reader/ReaderPdf.kt").readText()
        assertTrue(pdf.substringAfter("scroll = (scroll - delta.y / zoom)")
            .substringBefore("change.consume()").contains("reportCurrentPosition()"))
        val shelf = File(root, "LibraryPage.kt").readText().substringAfter("private fun RealBookCover")
            .substringBefore("internal fun TypographicBookCover")
        assertTrue(shelf.contains("displayCover(book, width, height)"))
        assertTrue(shelf.contains("ContentScale.Fit"))
        assertFalse(shelf.contains("ContentScale.Crop"))
        val motion = File(root, "reader/ReaderMotionUi.kt").readText()
        assertFalse(motion.contains("BookCoverCache"))
        assertFalse(motion.contains("setCrop"))
    }
}
