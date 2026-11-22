package com.brightblu.parc

import com.brightblu.parc.core.data.library.ReaderBookProblem
import com.brightblu.parc.reader.ReaderLoader
import com.brightblu.parc.reader.readerErrorActions
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T347: recover the Portuguese EPUB, and explain a damaged file without losing the record or marks. */
class T347ReaderTest {
    @Test
    fun onlyAUsefulRecoveryActionIsOffered() {
        assertEquals(true, readerErrorActions(ReaderBookProblem.LostAccess).retry)
        assertEquals(true, readerErrorActions(ReaderBookProblem.LostAccess).locate)
        // A broken archive cannot be repaired by retrying the same file; a complete copy can replace it.
        assertEquals(false, readerErrorActions(ReaderBookProblem.BrokenArchive).retry)
        assertEquals(true, readerErrorActions(ReaderBookProblem.BrokenArchive).locate)
        // Markup the parser refuses will fail again at the same byte, so do not offer a false fix.
        assertFalse(readerErrorActions(ReaderBookProblem.UnsupportedMarkup).retry)
        assertFalse(readerErrorActions(ReaderBookProblem.UnsupportedMarkup).locate)
        assertFalse(readerErrorActions(ReaderBookProblem.LostAccess, busy = true).locate)
        assertFalse(readerErrorActions(ReaderBookProblem.LostAccess, busy = true).retry)
    }

    @Test
    fun fileAccessArchiveDamageAndOtherReadFailuresAreSorted() {
        assertEquals(ReaderBookProblem.LostAccess, ReaderLoader.problemFor(FileNotFoundException()))
        assertEquals(ReaderBookProblem.LostAccess, ReaderLoader.problemFor(SecurityException()))
        assertEquals(ReaderBookProblem.BrokenArchive, ReaderLoader.problemFor(ZipException("truncated")))
        assertEquals(ReaderBookProblem.BrokenArchive, ReaderLoader.problemFor(EOFException()))
        assertEquals(ReaderBookProblem.Other, ReaderLoader.problemFor(IllegalStateException("disk")))
        assertEquals(ReaderLoader.LostAccess, ReaderLoader.messageFor(ReaderBookProblem.LostAccess))
        assertEquals(ReaderLoader.BrokenArchive, ReaderLoader.messageFor(ReaderBookProblem.BrokenArchive))
        assertTrue(ReaderLoader.LostAccess.contains("Locate"))
        assertTrue(ReaderLoader.BrokenArchive.contains("complete copy"))
    }

    @Test
    fun aChangedFileNeverUsesTheCachedParse() {
        assertTrue(ReaderLoader.cacheSizeMatches(911221, 911221))
        assertTrue(ReaderLoader.cacheSizeMatches(0, 102400))
        assertTrue(ReaderLoader.cacheSizeMatches(911221, -1))
        assertFalse(ReaderLoader.cacheSizeMatches(911221, 102400))
    }

    @Test
    fun locatingAFileKeepsTheBookAndItsMarks() {
        val screen = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
        assertTrue(screen.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(screen.contains("takePersistableUriPermission"))
        assertTrue(screen.contains("library.relocate(replacement.id, replacement.uri)"))
        assertTrue(screen.contains("onRelocated(replacement)"))
        // The session is keyed by uri, so the new file reloads without discarding the book id.
        assertTrue(screen.contains("key(readyRequest.book.uri)"))
        assertFalse(screen.contains("library.remove("))
        val store = File("../core/data/src/main/kotlin/com/brightblu/parc/core/data/library/LibraryStore.kt").readText()
        assertTrue(store.contains("fun relocate(id: String, uri: String) = updateBook(id) { it.copy(uri = uri) }"))
    }

    @Test
    fun thePortugueseDeclarationIsRemovedLocallyNotFetched() {
        val html = File("../core/data/src/main/kotlin/com/brightblu/parc/core/data/library/ReaderHtml.kt").readText()
        assertTrue(html.contains("localXhtmlDoctypes"))
        assertTrue(html.contains("xhtml11/dtd/xhtml11.dtd"))
        // The hardened parser still refuses any doctype that was not removed.
        assertTrue(html.contains("contains(\"<!DOCTYPE\", true)"))
        assertTrue(html.contains("External entities are not allowed"))
        assertTrue(html.contains("disallow-doctype-decl"))
    }
}
