package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T235: the hub scroll priority, the Library's remembered place, and the PDF fixes' wiring. */
class T235LibraryTest {
    @Test fun aPanelPullStartsOnlyFromTheGripWhenThePageIsAtItsTop() {
        assertTrue(HubPullPriority.startsSearchPull(fromHub = false, atScrollTop = false, onGrip = false))
        assertTrue(HubPullPriority.startsSearchPull(fromHub = true, atScrollTop = true, onGrip = true))
        assertFalse(HubPullPriority.startsSearchPull(fromHub = true, atScrollTop = true, onGrip = false))
        assertFalse(HubPullPriority.startsSearchPull(fromHub = true, atScrollTop = false, onGrip = true))
        assertFalse(HubPullPriority.startsSearchPull(fromHub = false, atScrollTop = true, onGrip = true,
            blocked = true))
        assertFalse(HubPullPriority.startsSearchPull(fromHub = false, atScrollTop = true, onGrip = true,
            onControl = true))
    }

    @Test fun theGripIsTheBottomRowInsideTheCardInset() {
        val bottom = 800f
        val inset = 16f
        val grip = Metrics.hubGripHeightDp
        assertTrue(HubPullPriority.onGrip(bottom - inset - grip / 2f, bottom, inset, grip))
        assertTrue(HubPullPriority.onGrip(bottom - inset, bottom, inset, grip))
        assertFalse(HubPullPriority.onGrip(bottom - inset - grip - 1f, bottom, inset, grip))
        assertFalse(HubPullPriority.onGrip(bottom - inset + 1f, bottom, inset, grip))
        assertFalse(HubPullPriority.onGrip(Float.NaN, bottom, inset, grip))
    }

    @Test fun theLibraryRestoresItsCellAndClampsWhenBooksGoAway() {
        assertEquals(HubScrollPosition(0, 0), LibraryScroll.restore(null, 20))
        assertEquals(HubScrollPosition(4, 37), LibraryScroll.restore(HubScrollPosition(4, 37), 20))
        assertEquals(HubScrollPosition(7, 0), LibraryScroll.restore(HubScrollPosition(12, 40), 8))
        assertEquals(HubScrollPosition(0, 0), LibraryScroll.restore(HubScrollPosition(3, 2), 0))
    }

    @Test fun scrollPositionsSurviveThePageAndTheTopIsTheDefault() {
        val memory = HubPageScrollMemory()
        assertEquals(null, memory.get(LibraryPage.Id))
        memory.put(LibraryPage.Id, 6, 120)
        assertEquals(HubScrollPosition(6, 120), memory.get(LibraryPage.Id))
        memory.put(LibraryPage.Id, 0, 0)
        assertEquals(null, memory.get(LibraryPage.Id))
    }

    @Test fun theLibraryShelfIsRegisteredWithTheHubScrollGateAndRemembersItsPlace() {
        val source = File("src/main/kotlin/com/brightblu/parc/LibraryPage.kt").readText()
        assertTrue(source.contains("rememberLazyGridState()"))
        assertTrue(source.contains("RegisterHubScroll(gridState)"))
        assertTrue(source.contains("LocalHubPageScrollMemory.current"))
        assertTrue(source.contains("LibraryScroll.restore(savedScroll"))
        assertTrue(source.contains("state = gridState"))
    }

    @Test fun everyScrollableHubPageUsesTheSharedGate() {
        // The Library grid was the one hub scrollable that bypassed the gate; it is registered now.
        val source = File("src/main/kotlin/com/brightblu/parc/LibraryPage.kt").readText()
        assertTrue(source.contains("RegisterHubScroll(gridState)"))
        assertTrue(source.contains("RegisterHubScroll(demoState)"))
        val home = File("src/main/kotlin/com/brightblu/parc/LauncherHome.kt").readText()
        assertTrue(home.contains("HubPullPriority.onGrip"))
        assertTrue(home.contains("onGrip = onGrip"))
    }

    @Test fun thePdfRendererIsNotClosedByTheEffectThatObservesItArrive() {
        val source = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
        assertTrue(source.contains("val opened = pdf"))
        assertTrue(source.contains("onDispose { opened?.close() }"))
        assertFalse(source.contains("onDispose { pdf?.close() }"))
    }

    @Test fun pdfZoomGoesToFiveAndRendersAtTheSettledScale() {
        assertEquals(5f, Metrics.pdfMaxZoom, 0f)
        val pdf = File("src/main/kotlin/com/brightblu/parc/reader/ReaderPdf.kt").readText()
        assertTrue(pdf.contains("renderZoom"))
        assertTrue(pdf.contains("renderScaleForZoom"))
        assertTrue(pdf.contains("cache.ensure(targets, viewportWidth, maxRenderHeight, maxCachePages, renderScale,"))
        assertTrue(pdf.contains("ReaderPdfMessages.PageFailed"))
    }
}
