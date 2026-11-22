package com.brightblu.parc

import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderChapter
import com.brightblu.parc.core.data.library.ReaderPageColor
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.model.Skin
import com.brightblu.parc.reader.ReaderMotion
import com.brightblu.parc.reader.ReaderWebDocument
import com.brightblu.parc.reader.readerColors
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T341: the saved passage is on the first readable frame; page one is never revealed on resume. */
class T341ReaderTest {
    private fun doc(): ReaderBookDoc {
        val chapters = listOf(
            ReaderChapter(0, "Front", "<h1>Front</h1><p>${"alpha ".repeat(200)}</p>",
                "Front ${"alpha ".repeat(200)}"),
            ReaderChapter(1, "Acknowledgments", "<h1>Acknowledgments</h1><p>${"beta ".repeat(200)}</p>",
                "Acknowledgments ${"beta ".repeat(200)}"),
        )
        return ReaderBookDoc("science", "Data Science from Scratch", "Joel Grus", chapters, emptyList())
    }

    @Test fun coverNeverFadesBeforeThePositionedFrameIsReady() {
        // Entering is not readiness: the exact defect was a cover that faded on the enter spring
        // (or a 1.5 s hold) while the WebView still showed the beginning of the book.
        assertFalse(ReaderMotion.coverMayFade(entered = true, contentReady = false, closing = false))
        assertFalse(ReaderMotion.coverMayFade(entered = false, contentReady = true, closing = false))
        assertFalse(ReaderMotion.coverMayFade(entered = true, contentReady = true, closing = true))
        assertTrue(ReaderMotion.coverMayFade(entered = true, contentReady = true, closing = false))
    }

    @Test fun theDocumentPositionsTheSavedAnchorBeforeItReportsReady() {
        val html = ReaderWebDocument.build(doc(), ReaderSettings(),
            readerColors(ReaderPageColor.Light, Skin.Midnight), 360f, 640f, BookPosition(1, 40), 7)
        // The saved chapter and character are the document's start, not zero.
        assertTrue(html.contains("var start={chapter:1,offset:40}"))
        assertTrue(html.contains("function tryInitialize(attempt)"))
        assertTrue(html.contains("requestAnimationFrame(function(){tryInitialize(attempt+1);})"))
        // Ready comes only after the saved character's column has been laid out on the page.
        // `initialized` must be set before goTo, or goTo's own guard silently leaves page one up.
        assertTrue(html.contains("function tryInitialize(attempt){if(readySent)return;initialized=true;measure();goTo("))
        assertTrue(html.indexOf("readySent=true") < html.indexOf("Reader.ready(pageCount,page,7)"))
        val anchorCheck = html.indexOf("anchor.right<=0||anchor.left>=W")
        val ready = html.indexOf("Reader.ready(pageCount,page,7)")
        assertTrue("anchor check missing", anchorCheck > 0)
        assertTrue("ready before the anchor check", ready > anchorCheck)
        assertFalse("ready must carry the laid-out page", html.contains("Reader.ready(pageCount,7)"))
    }

    @Test fun theReadyCallbackWaitsForADrawAndCarriesTheLaidOutPage() {
        val screen = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
        assertTrue(screen.contains("fun ready(pages: Int, page: Int, generation: Int)"))
        assertTrue(screen.contains("postVisualStateCallback"))
        assertTrue(screen.contains("webView.postOnAnimation"))
        assertTrue(screen.contains("currentPage = page.coerceAtLeast(0)"))
        // No checkpoint can precede the confirmed draw of this generation.
        val draw = screen.indexOf("drawnGeneration.set(version)")
        val checkpoint = screen.indexOf("checkpoint.value(position)", draw)
        assertTrue("draw confirmation missing", draw > 0)
        assertTrue("checkpoint before the draw", checkpoint > draw)
        // The old moved message is still accepted only for the drawn generation.
        assertTrue(screen.contains("if (version == generation.get() && version == drawnGeneration.get())"))
    }

    @Test fun theShellHoldsTheCoverUntilReadinessAndNotOnATimer() {
        val ui = File("src/main/kotlin/com/brightblu/parc/reader/ReaderMotionUi.kt").readText()
        val screen = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
        assertTrue(ui.contains("ReaderMotion.coverMayFade(entered, contentReady, closing)"))
        assertFalse("a timed cover release must not return", ui.contains("readerCoverHoldMaxMs"))
        // T352: a returning book shows its real shelf cover during the transition, not a title card.
        assertFalse(ui.contains("request.initialPosition == BookPosition()"))
        assertTrue(ui.contains("BookMetadataLoader(context).displayCover(request.book"))
        assertFalse("the shell must not paint a title over a cover",
            ui.contains("if (!contentReady) ReaderLoading(request.book.title, skin)"))
        assertTrue("readiness comes from the session inside the shell", screen.contains("contentReady = contentReady,"))
        // A rebuild (type size) keeps the quiet title even after the shell cover is gone.
        assertTrue(screen.contains("if (!layoutReady) ReaderLoading(book.title, skin)"))
    }

    @Test fun pdfAndComicReportReadyFromThePageTheyActuallyDraw() {
        val pdf = File("src/main/kotlin/com/brightblu/parc/reader/ReaderPdf.kt").readText()
        assertFalse("bitmap presence is not a drawn frame", pdf.contains("firstPageReady"))
        assertTrue(pdf.contains("if (restored && !firstPageDrawn)"))
        assertTrue(pdf.contains("if (restored && !firstPageDrawn && page == currentPage)"))
        assertTrue(pdf.contains("firstPageDrawn = true"))
        val comic = File("src/main/kotlin/com/brightblu/parc/reader/ReaderComic.kt").readText()
        assertTrue(comic.contains("if (isCurrent && !drawn)"))
        assertTrue(comic.contains("onFirstDraw()"))
    }
}
