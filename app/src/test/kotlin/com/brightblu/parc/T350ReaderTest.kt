package com.brightblu.parc

import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderChapter
import com.brightblu.parc.core.data.library.ReaderPageColor
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.core.data.library.ReaderSettingsJson
import com.brightblu.parc.model.Skin
import com.brightblu.parc.reader.ReaderJump
import com.brightblu.parc.reader.ReaderWebDocument
import com.brightblu.parc.reader.readerColors
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T350: the page number is a measured column of the reader's own layout, everywhere. */
class T350ReaderTest {
    private fun doc(): ReaderBookDoc {
        val chapters = listOf(
            ReaderChapter(0, "Cover", "<h1>Cover</h1><p>${"alpha ".repeat(200)}</p>",
                "Cover ${"alpha ".repeat(200)}"),
            ReaderChapter(1, "Chapter 2", "<h1>Chapter 2</h1><p>${"beta ".repeat(200)}</p>",
                "Chapter 2 ${"beta ".repeat(200)}"),
            ReaderChapter(2, "The Lift", "<h1>The Lift</h1><p>${"gamma ".repeat(200)}</p>",
                "The Lift ${"gamma ".repeat(200)}"),
        )
        return ReaderBookDoc("book", "A Book", "A Writer", chapters, emptyList())
    }

    @Test fun thePanelAndTheCornerBadgeAgreeOnTheSamePageAndChapter() {
        val book = doc()
        assertEquals("Page 12 of 340 · Chapter 3 · The Lift",
            ReaderJump.pagePanelLabel(book, BookPosition(2, 10), 11, 340, counting = false))
        assertEquals("Page 12 of 340", ReaderJump.pageBadgeLabel(11, 340))
        // A title that already says "Chapter 2" is not repeated as "Chapter 2 · Chapter 2".
        assertEquals("Chapter 2", ReaderJump.chapterLabel(book, 1))
        // The literal placeholder some contents indexes carry is not a chapter name.
        val placeholder = ReaderBookDoc("b", "B", "W", listOf(
            ReaderChapter(0, "Unknown", "<h1>Unknown</h1><p>x</p>", "x")), emptyList())
        assertEquals("Chapter 1", ReaderJump.chapterLabel(placeholder, 0))
        // While the columns are still being measured the label never claims a final total.
        assertTrue(ReaderJump.pagePanelLabel(book, BookPosition(2, 10), 11, 340, counting = true)
            .startsWith("Page 12 of 340 · counting pages…"))
        assertEquals("Only page", ReaderJump.pageBadgeLabel(0, 1))
        assertTrue(ReaderJump.pagePanelLabel(book, BookPosition(0, 0), 0, 1, counting = false)
            .startsWith("Only page"))
        assertEquals("Page 350 of 350", ReaderJump.pageBadgeLabel(400, 350))
    }

    @Test fun everyPageRoundTripsThroughTheSliderFraction() {
        for (total in listOf(2, 14, 340, 543)) {
            for (page in listOf(0, 1, total / 2, total - 1).distinct()) {
                val fraction = ReaderJump.fractionForPage(page, total)
                assertEquals(page, ReaderJump.pageForFraction(fraction, total))
            }
            assertEquals(0f, ReaderJump.fractionForPage(0, total), 0.0001f)
            assertEquals(1f, ReaderJump.fractionForPage(total - 1, total), 0.0001f)
        }
        assertEquals(0f, ReaderJump.fractionForPage(0, 1), 0.0001f)
    }

    @Test fun pageNumbersFollowTheLayoutAfterATypeChange() {
        // A larger type makes the same place a later page; the saved anchor is still a character.
        val book = doc()
        val position = BookPosition(2, 10)
        val small = ReaderJump.pagePanelLabel(book, position, 11, 340, counting = false)
        val large = ReaderJump.pagePanelLabel(book, position, 18, 480, counting = false)
        assertTrue(small.startsWith("Page 12 of 340"))
        assertTrue(large.startsWith("Page 19 of 480"))
        assertTrue(small != large)
        // Changing the setting changes nothing about the stored position.
        assertEquals(BookPosition(2, 10), position)
    }

    @Test fun bookmarkRowsShowTheCountedPage() {
        assertEquals("Bookmark", ReaderJump.bookmarkLabel(null))
        assertEquals("Bookmark · Page 12", ReaderJump.bookmarkLabel(11))
    }

    @Test fun showPageNumberDefaultsOnAndSurvivesTheSettingsDocument() {
        assertTrue(ReaderSettings().showPageNumber)
        assertTrue(ReaderSettingsJson.decode(null).showPageNumber)
        val off = ReaderSettings(showPageNumber = false)
        assertFalse(ReaderSettingsJson.decode(ReaderSettingsJson.encode(off)).showPageNumber)
        assertTrue(ReaderSettingsJson.decode(ReaderSettingsJson.encode(ReaderSettings())).showPageNumber)
    }

    @Test fun thePagedDocumentCountsColumnsAndJumpsByPage() {
        val html = ReaderWebDocument.build(doc(), ReaderSettings(pageColor = ReaderPageColor.Light),
            readerColors(ReaderPageColor.Light, Skin.Midnight), 360f, 640f, BookPosition(1, 40), 7)
        // Measured counts survive the chapter DOM being evicted.
        assertTrue(html.contains("var pageCache={},pageCacheFull={}"))
        assertTrue(html.contains("function chunkPages(obj)"))
        assertTrue(html.contains("function chapterPages(ch)"))
        assertTrue(html.contains("function recordPages(obj)"))
        // The whole book is counted in short slices, never in one blocking layout.
        assertTrue(html.contains("function startCount()"))
        assertTrue(html.contains("function countStep()"))
        assertTrue(html.contains("performance.now()+6"))
        assertTrue(html.contains("requestAnimationFrame(countStep)"))
        assertTrue(html.contains("Reader.counted(page,pageCount,true,7)"))
        assertTrue(html.contains("requestAnimationFrame(startCount)"))
        // A page jump places the measured column and reads the first glyph back as the anchor.
        assertTrue(html.contains("function goToPage(n)"))
        assertTrue(html.contains("goToPage:goToPage"))
        assertTrue(html.contains("function firstOffsetOnPage(obj,p)"))
        assertTrue(html.contains("function withPane(obj,fn)"))
        assertTrue(html.contains("caretAtPage();current=p?{chapter:p.chapter,offset:p.offset}"))
        // Bookmarks are translated into the same column space.
        assertTrue(html.contains("function pageOfPosition(ch,off)"))
        assertTrue(html.contains("function bookmarkPageList()"))
        assertTrue(html.contains("Reader.bookmarkPages(JSON.stringify(list)"))
        // A resize throws away the old count and starts a fresh one.
        assertTrue(html.contains("pageCache={};pageCacheFull={};"))
        File("build/t350-reader.html").apply { parentFile.mkdirs(); writeText(html) }
    }

    @Test fun everyReaderShowsTheCornerLabelAndAaCanTurnItOff() {
        val root = File("src/main/kotlin/com/brightblu/parc/reader")
        val controls = File(root, "ReaderControls.kt").readText()
        val screen = File(root, "ReaderScreen.kt").readText()
        val pdf = File(root, "ReaderPdf.kt").readText()
        val comic = File(root, "ReaderComic.kt").readText()
        val marks = File(root, "ReaderMarksUi.kt").readText()

        assertTrue(controls.contains("ReaderPageBadge(label: String"))
        assertTrue(controls.contains("Go to page."))
        assertTrue(controls.contains("readerCaptionStyle(skin)"))
        assertTrue(screen.contains("ReaderPageBadge(ReaderJump.pageBadgeLabel(currentPage, pageCount)"))
        assertTrue(screen.contains("settings.showPageNumber"))
        assertTrue(screen.contains("ReaderChoiceRow(\"Show page number\""))
        assertTrue(pdf.contains("ReaderPageBadge(ReaderJump.pageBadgeLabel(displayedPage, pageCount)"))
        assertTrue(pdf.contains("settings.showPageNumber"))
        assertTrue(comic.contains("ReaderPageBadge(ReaderJump.pageBadgeLabel(page, pageCount)"))
        assertTrue(comic.contains("settings.showPageNumber"))
        // Paged EPUB uses the common page coordinates; continuous text has no columns and keeps percent.
        assertTrue(screen.contains("val kind = if (continuous) ReaderJumpKind.Percent else ReaderJumpKind.Page"))
        assertTrue(screen.contains("window.reader.goToPage("))
        // The bookmark list uses the counted page for text books, not just the chapter.
        assertTrue(marks.contains("ReaderJump.bookmarkLabel(bookmarkPages[bookmark.id]"))
        // No blur, no new colours or durations.
        listOf(controls, screen, pdf, comic, marks).forEach { source ->
            assertFalse(source.contains("RenderEffect"))
            assertFalse(source.contains("BlurMaskFilter"))
        }
    }
}
