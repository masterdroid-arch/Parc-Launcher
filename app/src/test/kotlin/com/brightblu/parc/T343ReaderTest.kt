package com.brightblu.parc

import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderChapter
import com.brightblu.parc.core.data.library.ReaderPageColor
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.reader.PdfCachePolicy
import com.brightblu.parc.reader.ReaderLayoutBudget
import com.brightblu.parc.reader.ReaderWebDocument
import com.brightblu.parc.reader.readerColors
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T343: bounded chapter layout, one text index, a settled checkpoint and a byte-bounded PDF cache. */
class T343ReaderTest {
    private fun doc(chapters: Int): ReaderBookDoc {
        val list = (0 until chapters).map { index ->
            ReaderChapter(index, "Chapter $index", "<h1>Chapter $index</h1><p>marker$index ${"word ".repeat(400)}</p>",
                "Chapter $index marker$index ${"word ".repeat(400)}")
        }
        return ReaderBookDoc("book", "A Long Book", "A Writer", list, emptyList())
    }

    @Test fun theOpeningWindowIsBoundedToTheSavedChapterAndItsNeighbour() {
        val html = ReaderWebDocument.build(doc(8), ReaderSettings(), readerColors(ReaderPageColor.Light, Skin.Midnight),
            360f, 640f, BookPosition(0, 0), 9)
        // Only the opening window is in the document; later chapters arrive through the bridge.
        assertTrue(html.contains("data-chapter=\"0\""))
        assertTrue(html.contains("data-chapter=\"1\""))
        assertFalse(html.contains("data-chapter=\"2\""))
        assertFalse(html.contains("marker2"))
        assertTrue(html.contains("Reader.chapter"))
        assertTrue(html.contains("function chapterData(ch)"))
    }

    @Test fun theWindowHelperNeverGrowsPastOneNeighbourEachSide() {
        assertEquals(0..1, ReaderLayoutBudget.window(0, 8))
        assertEquals(2..4, ReaderLayoutBudget.window(3, 8))
        assertEquals(6..7, ReaderLayoutBudget.window(7, 8))
        assertEquals(IntRange.EMPTY, ReaderLayoutBudget.window(0, 0))
    }

    @Test fun pageStartsUseMeasuredCharactersPerPageUntilAChunkIsLaidOut() {
        assertEquals(900.0, ReaderLayoutBudget.charsPerPage(0, 0), 0.001)
        assertEquals(450.0, ReaderLayoutBudget.charsPerPage(900, 2), 0.001)
        assertEquals(3, ReaderLayoutBudget.estimatePages(1350, 450.0))
        assertEquals(1, ReaderLayoutBudget.estimatePages(0, 450.0))
        // Chapter 0 has 2 pages, chapter 1 has 3; chunk 1 starts on page 2.
        assertEquals(2, ReaderLayoutBudget.pageStart(listOf(2, 3), 1, listOf(3), 0))
        assertEquals(3, ReaderLayoutBudget.pageStart(listOf(2, 3), 1, listOf(1, 2), 1))
    }

    @Test fun theDocumentIndexesEachChunkOnceAndKeepsCaretLookupsOffTheWholeBook() {
        val html = ReaderWebDocument.build(doc(4), ReaderSettings(), readerColors(ReaderPageColor.Light, Skin.Midnight),
            360f, 640f, BookPosition(1, 20), 2)
        assertTrue(html.contains("function indexPane(obj)"))
        assertTrue(html.contains("var arr=obj.index||indexPane(obj)"))
        assertTrue(html.contains(">>1"))
        assertTrue(html.contains("function caretAtPage()"))
        assertTrue(html.contains("var obj=currentChunk;if(!obj||!obj.pane)return null"))
        assertTrue(html.contains("MAXCHUNK"))
        assertTrue(html.contains("function attachWindow(ch,idx)"))
        // The caret scan is scoped to the attached chunk, not every chapter in the book.
        val caret = html.substringAfter("function caretAtPage()").substringBefore("function progress()")
        assertFalse(caret.contains("book.children"))
        // The chunk-relative node start must be added before the chunk start (T343 regression).
        assertTrue(caret.contains("offset:obj.start+e.s+lo"))
        assertFalse(caret.contains("offset:obj.start+lo}"))
    }

    @Test fun aSettledCheckpointIsSentOnlyAfterATurnFinishes() {
        val html = ReaderWebDocument.build(doc(3), ReaderSettings(), readerColors(ReaderPageColor.Light, Skin.Midnight),
            360f, 640f, BookPosition(1, 10), 5)
        assertTrue(html.contains("function stable()"))
        assertTrue(html.contains("Reader.settled(page,current.chapter,current.offset,progress(),5)"))
        val drag = html.substringAfter("function drag(value)").substringBefore("function settle(")
        assertFalse("a drag must not persist a checkpoint", drag.contains("stable()"))
        assertFalse("a drag must not persist a checkpoint", drag.contains("Reader.moved"))
        val settle = html.substringAfter("function settle(").substringBefore("function measure()")
        assertTrue("a finished turn notifies", settle.contains("notify()"))
        val notify = html.substringAfter("function notify()").substringBefore("function goTo(")
        assertTrue("the settled page is what gets published", notify.contains("stable()"))
    }

    @Test fun theBridgeFetchesChaptersAndAcceptsASettledPage() {
        val screen = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
        assertTrue(screen.contains("fun chapter(index: Int): String"))
        assertTrue(screen.contains("fun settled(page: Int, chapter: Int, offset: Int, progress: Float, generation: Int)"))
        assertTrue(screen.contains("onChapter = { index -> doc.chapter(index).html }"))
        assertTrue(screen.contains("onSettled = {"))
        // The document string is assembled on a background dispatcher, not in composition.
        assertTrue(screen.contains("produceState<String?>"))
        assertTrue(screen.contains("withContext(Dispatchers.Default)"))
    }

    @Test fun theDocumentCarriesTheChapterCountAndNotTheWholeBookText() {
        val html = ReaderWebDocument.build(doc(40), ReaderSettings(), readerColors(ReaderPageColor.Light, Skin.Midnight),
            360f, 640f, BookPosition(20, 0), 1)
        assertTrue(html.contains("var chapterChars=["))
        assertTrue(html.contains("data-chapter=\"20\""))
        assertTrue(html.contains("data-chapter=\"21\""))
        // Chapters far from the saved one are not serialised at all.
        assertFalse(html.contains("marker0 "))
        assertFalse(html.contains("marker39 "))
    }

    @Test fun thePdfCacheRendersTheSavedPageFirstAndStaysInsideTheByteBudget() {
        assertEquals(listOf(272, 271, 273, 270, 274), PdfCachePolicy.order(listOf(270, 273, 271, 274, 272), 272))
        val bytes = mapOf(0 to 10, 1 to 10, 2 to 10, 3 to 10)
        // Budget for three pages: the nearest three stay; the far fourth is dropped.
        assertEquals(listOf(1, 0, 2), PdfCachePolicy.fit(listOf(3, 0, 1, 2), bytes, 30, 2, 1))
        // The minimum pages survive even when the size is unknown or over budget.
        assertEquals(listOf(1, 0), PdfCachePolicy.fit(listOf(0, 1), bytes, 1, 2, 1))
        assertEquals(Metrics.pdfCacheMaxBytes > 0, true)
    }
}
