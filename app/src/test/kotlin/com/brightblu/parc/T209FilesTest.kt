package com.brightblu.parc

import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.FoundBook
import com.brightblu.parc.core.data.library.ReaderOpenFormat
import com.brightblu.parc.core.data.library.stableBookId
import com.brightblu.parc.feature.search.SearchModeIds
import com.brightblu.parc.feature.search.SearchModes
import com.brightblu.parc.reader.ReaderOpenWith
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T209: open-with registration, the Files mode rules and the no-automatic-add contract. */
class T209FilesTest {
    private fun found(name: String, format: BookFormat = BookFormat.PDF, modified: Long = 1_000) =
        FoundBook("content://files/$name", name, "Shared storage/Books", 1_000, modified, format)

    @Test fun theReaderIsRegisteredForViewOfContentAndFileUris() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val block = Regex("""<activity[^>]*android:name="\.reader\.ReaderActivity".*?</activity>""",
            RegexOption.DOT_MATCHES_ALL).find(manifest)
        assertNotNull("ReaderActivity must be declared", block)
        val text = block!!.value
        assertTrue(text.contains("android:exported=\"true\""))
        assertTrue(text.contains("android:taskAffinity=\"\""))
        assertTrue(text.contains("android.intent.action.VIEW"))
        assertTrue(text.contains("android.intent.category.DEFAULT"))
        listOf("application/epub+zip", "application/pdf", "text/plain").forEach { mime ->
            val pattern = "android:scheme=\"(content|file)\" android:mimeType=\"" +
                Regex.escape(mime) + "\""
            assertEquals("both content and file for $mime", 2, Regex(pattern).findAll(text).count())
        }
    }

    @Test fun filesAreListedByTypeAndNameNewestFirst() {
        val files = listOf(
            found("Alpha.pdf", BookFormat.PDF, modified = 10),
            found("Beta.epub", BookFormat.EPUB, modified = 30),
            found("Gamma.txt", BookFormat.TXT, modified = 20),
            found("Delta.cbz", BookFormat.CBZ, modified = 40),
            found("Zeta.pdf", BookFormat.PDF, modified = 20),
        )
        assertEquals(listOf("Beta.epub", "Gamma.txt", "Zeta.pdf", "Alpha.pdf"),
            FilesSearchRules.rows(files, "").map { it.name })
        assertEquals(listOf("Alpha.pdf"), FilesSearchRules.rows(files, " ALPHA ").map { it.name })
        assertEquals(listOf("Zeta.pdf", "Alpha.pdf"), FilesSearchRules.rows(files, ".pdf").map { it.name })
        assertTrue(FilesSearchRules.rows(files, "Books").isEmpty())
        assertTrue(FilesSearchRules.rows(files, "nothing").isEmpty())
        assertEquals("1 file · newest first", FilesSearchRules.countLine(1, false))
        assertEquals("4 files · newest first", FilesSearchRules.countLine(4, false))
        assertEquals("Finding files…", FilesSearchRules.countLine(0, true))
    }

    @Test fun filesIsTheFifthModeAndUsesTheT194Contract() {
        val mode = FilesSearchMode(null)
        assertEquals(SearchModeIds.Files, mode.id)
        assertEquals("Files", mode.label)
        assertEquals("f ", mode.prefix)
        assertEquals("Find a file by name", mode.hint)
        assertEquals(listOf(SearchModeIds.Note, SearchModeIds.Calculate, SearchModeIds.Files),
            SearchModes.standard(listOf(mode)).map { it.id })
        assertTrue(SearchModeIds.Files in SearchModeIds.Order)
        assertEquals(SearchModeIds.Order.last(), SearchModeIds.Files)
    }

    @Test fun allFilesAccessNeedsBothTheBuildAndThePhoneGrant() {
        assertTrue(FilesSearchAccess.granted(buildAllows = true, systemManager = true))
        assertFalse(FilesSearchAccess.granted(buildAllows = false, systemManager = true))
        assertFalse(FilesSearchAccess.granted(buildAllows = true, systemManager = false))
        assertFalse(FilesSearchAccess.granted(buildAllows = false, systemManager = false))
    }

    @Test fun openingWithFilesNeverCreatesALibraryRowByItself() {
        val file = found("Novel.EPUB", BookFormat.EPUB)
        val book = ReaderOpenWith.book(file)
        assertEquals(stableBookId(file.uri), book.id)
        assertEquals("Novel", book.title)
        assertEquals(0L, book.addedAt)
        assertFalse(ReaderOpenWith.inLibrary(file, emptyList()))
        assertTrue(ReaderOpenWith.inLibrary(file, listOf(book.copy(id = "some-id"))))
        assertEquals(ReaderOpenFormat.supported, setOf(BookFormat.EPUB, BookFormat.PDF, BookFormat.TXT))
    }

    @Test fun theReaderActivityRoutesViewIntentsThroughOpenWith() {
        val source = File("src/main/kotlin/com/brightblu/parc/reader/ReaderActivity.kt").readText()
        assertTrue(source.contains("Intent.ACTION_VIEW"))
        assertTrue(source.contains("ReaderOpenWith.request"))
        assertTrue(source.contains("onNewIntent"))
        val contract = File("src/main/kotlin/com/brightblu/parc/reader/ReaderContract.kt").readText()
        assertTrue(contract.contains("unadded = true"))
        assertTrue(contract.contains("openHash = hash"))
    }
}
