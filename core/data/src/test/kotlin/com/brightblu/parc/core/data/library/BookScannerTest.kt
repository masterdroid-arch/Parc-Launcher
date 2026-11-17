package com.brightblu.parc.core.data.library

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookScannerTest {
    @get:Rule val temp = TemporaryFolder(File("build/t205-test-files").apply { mkdirs() })

    @Test fun typeFilterIsCaseInsensitiveAndChecksOnlyTheFinalExtension() {
        for (format in BookFormat.entries) {
            assertTrue(BookFormat.accepts("A Book.${format.name}", setOf(format)))
            assertFalse(BookFormat.accepts("A Book.${format.name}.bak", setOf(format)))
        }
        assertFalse(BookFormat.accepts("pdf", BookFormat.entries.toSet()))
        assertFalse(BookFormat.accepts("book.pdf", emptySet()))
        assertFalse(BookFormat.accepts("book.zip", setOf(BookFormat.CBZ)))
        assertEquals(listOf("application/pdf"), BookFormat.mimeTypes(setOf(BookFormat.PDF)).toList())
        assertTrue("application/x-cbz" in BookFormat.mimeTypes(setOf(BookFormat.CBZ)))
    }

    @Test fun deniedAccessUsesPickerWithoutTouchingStorage() = runTest {
        var visited = false
        val scanner = BookScanner({ visited = true; error("Should not scan") }, { false }, StandardTestDispatcher(testScheduler))
        assertEquals(BookScanResult.PickerRequired, scanner.find(setOf(BookFormat.PDF)))
        assertFalse(visited)
        assertEquals(BookScanResult.Files(emptyList()), scanner.find(emptySet()))
    }

    @Test fun scansAllChosenFilesRecursivelyWithLabelsAndNoLimit() = runTest {
        val root = temp.newFolder("Books").canonicalFile
        val nested = File(root, "Novels").apply { mkdir() }
        repeat(520) { File(nested, "Book$it.PDF").writeText("pdf") }
        File(root, "Text.txt").writeText("text")
        File(root, "Ignore.epub").writeText("epub")
        var calls = 0
        val scanner = BookScanner({ calls++; listOf(root, nested) }, { true }, StandardTestDispatcher(testScheduler))
        assertEquals(0, calls)
        val results = scanner.find(setOf(BookFormat.PDF, BookFormat.TXT)) as BookScanResult.Files
        assertEquals(521, results.files.size)
        assertEquals(0, results.unreadableFolders)
        val pdf = results.files.first { it.format == BookFormat.PDF }
        assertTrue(pdf.folderLabel.endsWith("Novels"))
        assertEquals(3L, pdf.sizeBytes)
        assertTrue(pdf.modifiedAt > 0)
        assertTrue(File(pdf.uri).isFile)
    }

    @Test fun symlinkLoopsAreNotFollowedAndUnreadableRootsAreReported() = runTest {
        val root = temp.newFolder("Shared").canonicalFile
        Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        File(root, "Book.pdf").writeText("pdf")
        val scanner = BookScanner({ listOf(root, File(root, "missing")) }, { true }, StandardTestDispatcher(testScheduler))
        val result = scanner.find(setOf(BookFormat.PDF)) as BookScanResult.Files
        assertEquals(1, result.files.size)
        assertEquals(1, result.unreadableFolders)
    }

    @Test fun coverCacheCapsDiskBytesAndRejectsOversizeEntries() {
        val directory = temp.newFolder("covers")
        val cache = BookCoverCache(directory, maxBytes = 10, maxEntryBytes = 6)
        assertTrue(cache.put("one", byteArrayOf(1, 2, 3, 4, 5, 6)))
        directory.listFiles()!!.single().setLastModified(1)
        assertTrue(cache.put("two", byteArrayOf(6, 5, 4, 3, 2, 1)))
        assertNull(cache.get("one"))
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), cache.get("two"))
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 10)
        assertFalse(cache.put("huge", ByteArray(7)))
        cache.remove("two")
        assertNull(cache.get("two"))
    }

    @Test fun idsCannotEscapeCacheAndRemainStable() {
        val directory = temp.newFolder("covers")
        val cache = BookCoverCache(directory)
        assertTrue(cache.put("../../outside", byteArrayOf(1)))
        assertEquals(1, directory.listFiles()!!.size)
        assertEquals(stableBookId("x"), stableBookId("x"))
        assertNotEquals(stableBookId("x"), stableBookId("y"))
    }
}
