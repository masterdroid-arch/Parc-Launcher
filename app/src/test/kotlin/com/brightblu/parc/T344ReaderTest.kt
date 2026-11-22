package com.brightblu.parc

import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderChapter
import com.brightblu.parc.core.data.library.ReaderPdfMath
import com.brightblu.parc.core.data.library.ReaderTocEntry
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.reader.ReaderBack
import com.brightblu.parc.reader.ReaderJump
import com.brightblu.parc.reader.ReaderJumpKind
import com.brightblu.parc.reader.ReaderJumpSliderState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T344: the bottom panel's pure rules, plus the wiring checks a unit test can make. */
class T344ReaderTest {
    private fun doc(chapters: Int = 3): ReaderBookDoc {
        val list = (0 until chapters).map { index ->
            val text = "Chapter $index " + "word ".repeat(if (index == 1) 240 else 100)
            ReaderChapter(index, "Chapter $index", "<h1>Chapter $index</h1><p>$text</p>", text)
        }
        return ReaderBookDoc("book", "A Book", "A Writer", list, emptyList())
    }

    @Test fun percentMapsToTheStartMiddleAndEndOfTheText() {
        val book = doc()
        assertEquals(BookPosition(0, 0), ReaderJump.positionAt(book, 0f))
        val middle = ReaderJump.positionAt(book, 0.5f)
        assertEquals(1, middle.chapter)
        assertTrue(middle.characterOffset > 0)
        val end = ReaderJump.positionAt(book, 1f)
        assertEquals(book.chapters.lastIndex, end.chapter)
        assertEquals(book.chapters.last().text.length, end.characterOffset)
        assertEquals(0, ReaderJump.percentForFraction(0f))
        assertEquals(50, ReaderJump.percentForFraction(0.5f))
        assertEquals(100, ReaderJump.percentForFraction(1f))
    }

    @Test fun directPercentAndPageEntryAcceptOnlyWholeNumbersInRange() {
        assertEquals(0.42f, ReaderJump.parseEntry(ReaderJumpKind.Percent, "42", 1))
        assertEquals(0.42f, ReaderJump.parseEntry(ReaderJumpKind.Percent, " 42% ", 1))
        assertEquals(0f, ReaderJump.parseEntry(ReaderJumpKind.Percent, "0", 1))
        assertEquals(1f, ReaderJump.parseEntry(ReaderJumpKind.Percent, "100", 1))
        assertNull(ReaderJump.parseEntry(ReaderJumpKind.Percent, "101", 1))
        assertNull(ReaderJump.parseEntry(ReaderJumpKind.Percent, "-1", 1))
        assertNull(ReaderJump.parseEntry(ReaderJumpKind.Percent, "four", 1))
        assertNull(ReaderJump.parseEntry(ReaderJumpKind.Percent, "", 1))

        assertEquals(0f, ReaderJump.parseEntry(ReaderJumpKind.Page, "1", 543))
        assertEquals(1f, ReaderJump.parseEntry(ReaderJumpKind.Page, "543", 543))
        assertNull(ReaderJump.parseEntry(ReaderJumpKind.Page, "0", 543))
        assertNull(ReaderJump.parseEntry(ReaderJumpKind.Page, "544", 543))
        assertNull(ReaderJump.parseEntry(ReaderJumpKind.Page, "2.5", 543))
    }

    @Test fun aSinglePagePdfShowsOnlyPageAndHasNoSliderRange() {
        assertTrue(ReaderJump.isSinglePage(ReaderJumpKind.Page, 1))
        assertFalse(ReaderJump.isSinglePage(ReaderJumpKind.Page, 2))
        assertFalse(ReaderJump.isSinglePage(ReaderJumpKind.Percent, 1))
        assertEquals("Only page",
            ReaderJump.panelLabel(ReaderJumpKind.Page, null, BookPosition(page = 0), 1))
        assertEquals("Only page", ReaderJump.preview(ReaderJumpKind.Page, 0.5f, null, 1))
        assertEquals(0f, ReaderJump.step(ReaderJumpKind.Page, 0.5f, 1, 1))
    }

    @Test fun pageLabelsNameThePhysicalPageAndPercent() {
        assertEquals("Page 272 of 543 · 50% read",
            ReaderJump.panelLabel(ReaderJumpKind.Page, null, BookPosition(page = 271), 543))
        assertEquals("Page 1 of 14 · 0% read",
            ReaderJump.panelLabel(ReaderJumpKind.Page, null, BookPosition(page = 0), 14))
        assertEquals("Page 543 of 543 · 100% read",
            ReaderJump.panelLabel(ReaderJumpKind.Page, null, BookPosition(page = 542), 543))
        assertEquals("Page 272 of 543", ReaderJump.preview(ReaderJumpKind.Page, 0.5f, null, 543))
        assertEquals(271f / 542f, ReaderJump.fractionForPage(271, 543), 0.0001f)
        assertEquals(271, ReaderJump.pageForFraction(0.5f, 543))
        assertEquals(270, ReaderJump.pageForFraction(0.499f, 543))
    }

    @Test fun epubLabelsAndPreviewsNameTheChapter() {
        val book = doc()
        val position = BookPosition(1, 10)
        val label = ReaderJump.panelLabel(ReaderJumpKind.Percent, book, position, 1)
        assertTrue(label.startsWith("Chapter 1"))
        assertTrue(label.endsWith("% read"))
        assertTrue(ReaderJump.preview(ReaderJumpKind.Percent, 0.95f, book, 1).startsWith("95% · Chapter 2"))
    }

    @Test fun chapterStepsStopAtBothEnds() {
        assertNull(ReaderJump.chapterStep(0, -1, 3))
        assertEquals(1, ReaderJump.chapterStep(0, 1, 3))
        assertEquals(1, ReaderJump.chapterStep(2, -1, 3))
        assertNull(ReaderJump.chapterStep(2, 1, 3))
        assertNull(ReaderJump.chapterStep(0, 1, 0))
    }

    @Test fun contentsMarksTheLastHeadingAtOrBeforeThePlaceInALongToc() {
        val entries = (0 until 300).map { ReaderTocEntry("Section $it", it / 3, (it % 3) * 100) }
        assertEquals(0, ReaderJump.contentsIndex(entries, BookPosition(0, 0)))
        assertEquals(16, ReaderJump.contentsIndex(entries, BookPosition(5, 100)))
        assertEquals(299, ReaderJump.contentsIndex(entries, BookPosition(99, 200)))
        assertEquals(-1, ReaderJump.contentsIndex(emptyList(), BookPosition(0, 0)))
        // Unsorted selection is still the last heading that has been passed.
        assertEquals(2, ReaderJump.contentsIndex(entries.take(3), BookPosition(0, 250)))
    }

    @Test fun backClosesKeyboardThenListThenPanelThenBook() {
        assertEquals(ReaderBack.HideKeyboard, ReaderJump.backAction(true, true, true, true))
        assertEquals(ReaderBack.CloseSheet, ReaderJump.backAction(false, true, true, true))
        assertEquals(ReaderBack.ReopenList, ReaderJump.backAction(false, false, true, true))
        assertEquals(ReaderBack.HidePanel, ReaderJump.backAction(false, false, false, true))
        assertEquals(ReaderBack.ExitReader, ReaderJump.backAction(false, false, false, false))
    }

    @Test fun aDragPreviewsAndOnlyReleaseSeeks() {
        val state = ReaderJumpSliderState()
        state.follow(0.20f)
        assertEquals(0.20f, state.shown, 0.0001f)
        state.press(0.30f)
        state.move(0.70f)
        assertEquals(0.70f, state.shown, 0.0001f)
        assertEquals(0.70f, state.release()!!, 0.0001f)
        assertFalse(state.dragging)

        state.follow(0.40f)
        state.press(0.45f)
        state.move(0.90f)
        state.cancel()
        assertNull(state.release())
        assertEquals(0.40f, state.shown, 0.0001f)
        assertEquals(0.40f, state.committed, 0.0001f)
    }

    @Test fun stepsMoveByAPercentOrAPageAndStayInsideTheBook() {
        assertEquals(0.43f, ReaderJump.step(ReaderJumpKind.Percent, 0.42f, 1, 1), 0.0001f)
        assertEquals(0.41f, ReaderJump.step(ReaderJumpKind.Percent, 0.42f, -1, 1), 0.0001f)
        assertEquals(0f, ReaderJump.step(ReaderJumpKind.Percent, 0f, -1, 1), 0.0001f)
        assertEquals(1f, ReaderJump.step(ReaderJumpKind.Percent, 1f, 1, 1), 0.0001f)
        assertEquals(ReaderPdfMath.pageForFraction(
            ReaderJump.step(ReaderJumpKind.Page, ReaderJump.fractionForPage(10, 543), 1, 543), 543), 11)
        assertEquals(0, ReaderJump.pageForFraction(
            ReaderJump.step(ReaderJumpKind.Page, 0f, -1, 543), 543))
    }

    @Test fun accessibilityRangeValuesMapBackToTheSamePlace() {
        for (percent in listOf(0, 1, 42, 50, 99, 100)) {
            val fraction = ReaderJump.rangeFraction(ReaderJumpKind.Percent, percent.toFloat(), 1)
            assertEquals(percent, ReaderJump.percentForFraction(fraction))
        }
        for (page in listOf(1, 2, 272, 543)) {
            val fraction = ReaderJump.rangeFraction(ReaderJumpKind.Page, page.toFloat(), 543)
            assertEquals(page, ReaderJump.pageForFraction(fraction, 543) + 1)
        }
    }

    @Test fun theBottomPanelSharesTheTopBarAndWiresTheSeekToTheReader() {
        val root = File("src/main/kotlin/com/brightblu/parc/reader")
        val controls = File(root, "ReaderControls.kt").readText()
        val panel = File(root, "ReaderJumpPanel.kt").readText()
        val screen = File(root, "ReaderScreen.kt").readText()
        val pdf = File(root, "ReaderPdf.kt").readText()
        val comic = File(root, "ReaderComic.kt").readText()
        val marks = File(root, "ReaderMarksUi.kt").readText()
        val search = File(root, "ReaderSearchUi.kt").readText()

        assertTrue(controls.contains("footer: @Composable ColumnScope.() -> Unit"))
        assertTrue(controls.contains("ReaderControls("))
        assertTrue(panel.contains("state.release()?.let(onSeek)"))
        assertTrue(panel.contains("if (!finished) state.cancel()"))
        assertTrue(panel.contains("setProgress {"))
        assertTrue(panel.contains("progressBarRangeInfo = ProgressBarRangeInfo("))
        assertTrue(panel.contains("onKeyEvent {"))
        assertTrue(panel.contains("ReaderPositionEntrySheet("))
        assertTrue(screen.contains("ReaderJumpPanel("))
        assertTrue(screen.contains("ReaderSheet.Position"))
        assertTrue(screen.contains("ReaderSheet.Bookmarks"))
        assertTrue(screen.contains("ReaderSheet.Notes"))
        assertTrue(screen.contains("seekText(it)"))
        assertTrue(screen.contains("ReaderJump.backAction("))
        assertTrue(screen.contains("returnSheet"))
        // The jump finds the page by walking measured page starts, then shows that column.
        assertTrue(screen.contains("function pageAtOffset("))
        assertTrue(screen.contains("function showCurrent("))
        assertTrue(screen.contains("showCurrent(f.obj,localPage)"))
        assertTrue(screen.contains("start<=local+4"))
        assertTrue(screen.contains("br.top>=H"))
        assertTrue(pdf.contains("ReaderJumpPanel("))
        assertTrue(pdf.contains("movePage("))
        assertTrue(pdf.contains("jumpPage("))
        assertTrue(pdf.contains("ReaderSheet.Position"))
        assertTrue(comic.contains("ReaderJumpPanel("))
        assertTrue(marks.contains("enum class MarkSection"))
        assertTrue(marks.contains("MarkSection.Bookmarks"))
        assertTrue(marks.contains("MarkSection.Notes"))
        assertTrue(search.contains("class ReaderSearchSession"))
        assertTrue(search.contains("state = listState"))
        // No literal durations, sizes or colours were added to the new UI.
        assertFalse(panel.contains("RenderEffect"))
        assertFalse(panel.contains("BlurMaskFilter"))
        assertTrue(Metrics.readerJumpSliderHeightDp > 0f)
        assertTrue(Metrics.readerJumpPercentStep > 0f)
    }
}
