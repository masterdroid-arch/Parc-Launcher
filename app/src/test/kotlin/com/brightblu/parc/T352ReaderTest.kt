package com.brightblu.parc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T352: opening a book shows its real shelf cover, held until the positioned first frame is
 * drawn, then fades to it. Page one is never revealed on the way to a saved place, and the
 * title card is only the fallback for a book with no cover image.
 */
class T352ReaderTest {
    private val ui = File("src/main/kotlin/com/brightblu/parc/reader/ReaderMotionUi.kt").readText()
    private val screen = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
    private val shelf = File("src/main/kotlin/com/brightblu/parc/LibraryPage.kt").readText()
    private val handoff = File("src/main/kotlin/com/brightblu/parc/reader/ReaderCoverHandoff.kt")
        .readText()

    @Test fun theRealCoverIsLoadedForFirstAndReturningReads() {
        // The T341 guard loaded the illustrated cover only for a first read; a returning book got
        // the plain title card. The T352 card is exactly that complaint.
        assertFalse(ui.contains("request.initialPosition == BookPosition()"))
        assertTrue(ui.contains("BookMetadataLoader(context).displayCover(request.book"))
        assertTrue("display size, never the fixed thumbnail",
            ui.contains("screen.width.toInt(), screen.height.toInt()"))
    }

    @Test fun theShelfTileHandsItsCoverToTheReader() {
        // The tile already holds the real art in memory; the reader starts from it, so the first
        // frame is a cover and not a title card while the display-sized copy decodes.
        assertTrue(shelf.contains("ReaderCoverHandoff.remember(book.id, cover)"))
        assertTrue(ui.contains("ReaderCoverHandoff.get(request.book.id)"))
        assertTrue(handoff.contains("private const val Keep = 4"))
        assertTrue(handoff.contains("removeEldestEntry"))
        assertTrue("never recycle a bitmap the shelf still shows", !handoff.contains("recycle()"))
    }

    @Test fun theCoverHoldsUntilThePositionedFrameAndThenFades() {
        assertTrue(ui.contains("ReaderMotion.coverMayFade(entered, contentReady, closing)"))
        val gate = ui.indexOf("ReaderMotion.coverMayFade(entered, contentReady, closing)")
        val fade = ui.indexOf("coverAlpha.animateTo(0f")
        assertTrue("the cover fades only after the readiness gate", fade > gate)
        assertFalse("no timer can release the cover", ui.contains("readerCoverHoldMaxMs"))
        assertFalse("the shell must not paint a title over a cover",
            ui.contains("if (!contentReady) ReaderLoading(request.book.title, skin)"))
        assertTrue("readiness is the session's confirmed draw", screen.contains("contentReady = contentReady,"))
        assertTrue(screen.contains("onContentReady = { contentReady = true }"))
    }

    @Test fun theTitleCardIsOnlyTheFallbackForABookWithoutCoverArt() {
        val load = ui.indexOf("BookMetadataLoader(context).displayCover(request.book")
        val title = ui.indexOf("ReaderCoverArt.title(context, request.book")
        assertTrue("title art is built after the file has no cover", title > load)
        assertTrue("title art is the null branch", ui.contains("} else if (coverArt == null) {"))
        // A moving plate keeps a real-cover book free of a title card before its bitmap is read.
        assertTrue(ui.contains("coverPlate.draw(this, rect, alpha, coverRadiusPx)"))
        assertTrue(ui.contains("internal class ReaderCoverPlate"))
    }

    @Test fun oneShellCoversTheWholeOpenNotOnlyTheLoadedSession() {
        assertEquals("exactly one reader shell", 1, Regex("ReaderMotionShell\\(").findAll(screen).count())
        val host = screen.substring(screen.indexOf("internal fun ReaderHost("), screen.indexOf("private class ReaderCloseFlush"))
        assertTrue("the shell wraps the resolve step too", host.contains("ReaderMotionShell("))
        assertTrue(host.contains("ReaderLoading(request.book.title, skin)"))
        assertTrue("a tiled cover open must not flash a quiet title around the growing cover",
            host.contains("if (request.coverRect != null && !request.unadded) {"))
        assertTrue("closing the shell still flushes the session's place",
            screen.contains("onClose = { closeFlush.run(); onClose() }"))
        assertTrue(screen.contains("SideEffect { closeFlush.action = { flushPosition() } }"))
    }

    @Test fun aBookThatFailsToOpenLiftsTheCoverToSaySo() {
        assertTrue(screen.contains("LaunchedEffect(error) { if (error != null) onContentReady() }"))
    }
}
