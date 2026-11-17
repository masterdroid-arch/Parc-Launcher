package com.brightblu.parc.core.data.library

import com.brightblu.parc.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPdfTest {
    @Test fun renderSizeFillsTheWidthAndCapsTallPages() {
        val normal = ReaderPdfMath.renderSize(612, 792, 1080, 4800)
        assertEquals(1080, normal.width)
        assertEquals(1398, normal.height)

        val tall = ReaderPdfMath.renderSize(612, 3000, 1080, 2400)
        assertEquals(2400, tall.height)
        assertTrue(tall.width < 1080)
        assertTrue(tall.width > 0)

        val tiny = ReaderPdfMath.renderSize(1, 1, 1, 1)
        assertEquals(PdfRenderSize(1, 1), tiny)
    }

    @Test fun displayHeightUsesThePageAspect() {
        assertEquals(1398, ReaderPdfMath.displayHeight(612, 792, 1080))
        assertEquals(1, ReaderPdfMath.displayHeight(1000, 1, 1))
    }

    @Test fun cacheLimitKeepsARenderWindowAndStaysBounded() {
        assertEquals(4, ReaderPdfMath.cacheLimit(bytesPerPage = 8_000_000, budgetBytes = 32_000_000,
            minPages = 2, maxPages = 5))
        assertEquals(2, ReaderPdfMath.cacheLimit(bytesPerPage = 40_000_000, budgetBytes = 32_000_000,
            minPages = 2, maxPages = 5))
        assertEquals(5, ReaderPdfMath.cacheLimit(bytesPerPage = 1_000, budgetBytes = 32_000_000,
            minPages = 2, maxPages = 5))
        assertEquals(2, ReaderPdfMath.cacheLimit(bytesPerPage = 0, budgetBytes = 0, minPages = 2, maxPages = 5))
        assertTrue(Metrics.pdfCacheMaxBytes > 0)
    }

    @Test fun pageModeKeepsTheCurrentPageAndItsNeighbours() {
        assertEquals(listOf(0, 1, 2), ReaderPdfMath.pageTargets(current = 0, pageCount = 300, maxPages = 3))
        assertEquals(listOf(50, 49, 51), ReaderPdfMath.pageTargets(current = 50, pageCount = 300, maxPages = 3))
        assertEquals(listOf(299, 298, 297), ReaderPdfMath.pageTargets(current = 299, pageCount = 300, maxPages = 3))
        assertEquals(listOf(0), ReaderPdfMath.pageTargets(current = 0, pageCount = 1, maxPages = 5))
        val clamped = ReaderPdfMath.pageTargets(current = 999, pageCount = 3, maxPages = 5)
        assertEquals(listOf(2, 1, 0), clamped)
    }

    @Test fun continuousVisiblePagesIncludePartialPagesAndOverscan() {
        val tops = listOf(0f, 100f, 200f, 300f)
        val heights = listOf(100f, 100f, 100f, 100f)
        assertEquals(listOf(0, 1), ReaderPdfMath.visiblePages(tops, heights, scroll = 50f, viewport = 100f))
        assertEquals(listOf(0, 1, 2), ReaderPdfMath.visiblePages(tops, heights, scroll = 50f, viewport = 100f, overscan = 1))
        assertEquals(listOf(2, 3), ReaderPdfMath.visiblePages(tops, heights, scroll = 250f, viewport = 100f))
        assertEquals(listOf(3), ReaderPdfMath.visiblePages(tops, heights, scroll = 1000f, viewport = 100f))
        assertTrue(ReaderPdfMath.visiblePages(tops, heights, scroll = 0f, viewport = 0f).isEmpty())
        assertTrue(ReaderPdfMath.visiblePages(emptyList(), emptyList(), 0f, 100f).isEmpty())
    }

    @Test fun positionAndScrollAreInverses() {
        val tops = listOf(0f, 100f, 200f, 300f)
        val heights = listOf(100f, 100f, 100f, 100f)
        assertEquals(BookPosition(page = 0, pageOffset = 0.5), ReaderPdfMath.positionAt(tops, heights, 50f))
        assertEquals(BookPosition(page = 1, pageOffset = 0.0), ReaderPdfMath.positionAt(tops, heights, 100f))
        assertEquals(BookPosition(page = 3, pageOffset = 0.5), ReaderPdfMath.positionAt(tops, heights, 350f))
        assertEquals(150f, ReaderPdfMath.scrollFor(tops, heights, 1, 0.5), 0f)
        assertEquals(0f, ReaderPdfMath.scrollFor(tops, heights, 0, 0.0), 0f)
        assertEquals(1.0, ReaderPdfMath.positionAt(tops, heights, 1000f).pageOffset, 0.0)
    }

    @Test fun progressClampsAndReachesOneAtTheLastPage() {
        assertEquals(0.0, ReaderPdfMath.progress(BookPosition(page = 0), 300), 0.0)
        assertEquals(1.0, ReaderPdfMath.progress(BookPosition(page = 299), 300), 0.0)
        assertEquals(1.0, ReaderPdfMath.progress(BookPosition(page = 299, pageOffset = 0.9), 300), 0.0)
        assertEquals(1.0, ReaderPdfMath.progress(BookPosition(page = 0), 1), 0.0)
        assertEquals(0.5 / 299.0, ReaderPdfMath.progress(BookPosition(page = 0, pageOffset = 0.5), 300), 0.0001)
        assertEquals(BookPosition(page = 3), ReaderPdfMath.clamp(BookPosition(page = 9), 4))
        assertEquals(BookPosition(page = 3, pageOffset = 0.9), ReaderPdfMath.clamp(BookPosition(page = 3, pageOffset = 0.9), 4))
    }

    @Test fun samePagePositionIgnoresTinyOffsetJitter() {
        assertTrue(ReaderPdfMath.same(BookPosition(page = 4, pageOffset = 0.30), BookPosition(page = 4, pageOffset = 0.305)))
        assertFalse(ReaderPdfMath.same(BookPosition(page = 4, pageOffset = 0.30), BookPosition(page = 4, pageOffset = 0.50)))
        assertFalse(ReaderPdfMath.same(BookPosition(page = 4), BookPosition(page = 5)))
        assertFalse(ReaderPdfMath.same(null, BookPosition(page = 4)))
    }

    @Test fun scrubberFractionMapsToAPage() {
        assertEquals(0, ReaderPdfMath.pageForFraction(0f, 300))
        assertEquals(299, ReaderPdfMath.pageForFraction(1f, 300))
        assertEquals(150, ReaderPdfMath.pageForFraction(0.5f, 300))
        assertEquals(0, ReaderPdfMath.pageForFraction(0.5f, 1))
        assertEquals(299, ReaderPdfMath.pageForFraction(9f, 300))
    }

    @Test fun maxRenderHeightFollowsTheMemoryToken() {
        assertEquals((1000 * Metrics.pdfMaxRenderHeightFactor).toInt(), ReaderPdfMath.maxRenderHeight(1000))
        assertTrue(ReaderPdfMath.maxRenderHeight(0) > 0)
    }

    @Test fun aZoomedRenderIsLargerAndStaysInsideTheMemoryBudget() {
        val base = ReaderPdfMath.renderSize(612, 792, 1080, 4800)
        val twice = ReaderPdfMath.renderSize(612, 792, 1080, 4800, scale = 2f)
        assertTrue(twice.width > base.width)
        assertTrue(twice.height > base.height)
        val five = ReaderPdfMath.renderSize(612, 792, 1080, 4800, scale = 5f)
        assertTrue(five.width.toLong() * five.height <= Metrics.pdfMaxRenderPixels)
        assertTrue(five.width >= base.width)
        assertEquals(1f, ReaderPdfMath.renderScaleForZoom(0.5f), 0f)
        assertEquals(5f, ReaderPdfMath.renderScaleForZoom(9f), 0f)
        assertEquals(1f, ReaderPdfMath.renderScaleForZoom(Float.NaN), 0f)
    }

    @Test fun panLimitsGrowWithZoomAndClampToThePage() {
        val one = ReaderPdfMath.panLimit(1080, 2400, 1400f, 1f)
        assertTrue(one.isZero)
        val two = ReaderPdfMath.panLimit(1080, 2400, 1400f, 2f)
        assertEquals(540f, two.x, 0f)
        assertEquals(200f, two.y, 0f)
        assertEquals(ReaderPdfMath.PdfPan(540f, 200f),
            ReaderPdfMath.clampPan(900f, 900f, two))
        assertEquals(ReaderPdfMath.PdfPan(-540f, -200f),
            ReaderPdfMath.clampPan(-900f, -900f, two))
        val continuous = ReaderPdfMath.panLimit(1080, 2400, 1400f, 3f, vertical = false)
        assertEquals(0f, continuous.y, 0f)
        assertTrue(continuous.x > two.x)
    }

    @Test fun aZoomedPageTurnsOnlyWhenPushedPastAPannedEdge() {
        val limit = ReaderPdfMath.panLimit(1080, 2400, 1400f, 2f)
        assertEquals(null, ReaderPdfMath.zoomedEdgeTurn(5, 10, 0f, 0f, limit, 0f, 1080))
        assertEquals(6, ReaderPdfMath.zoomedEdgeTurn(5, 10, -300f, -limit.x, limit, 0f, 1080))
        assertEquals(6, ReaderPdfMath.zoomedEdgeTurn(5, 10, -10f, -limit.x, limit, -900f, 1080))
        assertEquals(2, ReaderPdfMath.zoomedEdgeTurn(3, 10, 300f, limit.x, limit, 0f, 1080))
        assertEquals(null, ReaderPdfMath.zoomedEdgeTurn(5, 10, 300f, 0f, limit, 0f, 1080))
        assertEquals(0, ReaderPdfMath.zoomedEdgeTurn(0, 10, 300f, limit.x, limit, 0f, 1080))
        assertEquals(9, ReaderPdfMath.zoomedEdgeTurn(9, 10, -300f, -limit.x, limit, 0f, 1080))
    }

    @Test fun theRenderWindowMovesWithThePageIndex() {
        for (page in listOf(0, 1, 5, 50, 299)) {
            val window = ReaderPdfMath.pageTargets(page, 300, 3)
            assertEquals(page, window.first())
            assertTrue(window.all { it in 0..299 })
            assertTrue(window.distinct().size == window.size)
        }
        assertEquals(listOf(50, 49, 51), ReaderPdfMath.pageTargets(50, 300, 3))
        assertEquals(listOf(51, 50, 52), ReaderPdfMath.pageTargets(51, 300, 3))
    }

    @Test fun pdfSettingsRoundTripAndOldDocumentsReadAsDefaults() {
        val custom = ReaderSettings(pdfLayout = PdfLayout.Continuous, pdfInvert = true)
        assertEquals(custom, ReaderSettingsJson.decode(ReaderSettingsJson.encode(custom)))
        assertTrue(ReaderSettingsJson.encode(custom).length <= ReaderSettingsJson.MaxChars)

        val old = ReaderSettingsJson.decode(
            """{"fontSp":22,"lineSpacing":1.8,"marginFraction":0.1,"family":"Sans","pageColor":"Sepia"}""")
        assertEquals(PdfLayout.Page, old.pdfLayout)
        assertFalse(old.pdfInvert)
        assertEquals(ReaderFamily.Sans, old.family)
        assertEquals(ReaderPageColor.Sepia, old.pageColor)
    }

    @Test fun pdfPositionsSurviveTheLibraryJsonRoundTrip() {
        val book = Book("book-1", "content://books/1", "A PDF", "An Author", BookFormat.PDF,
            position = BookPosition(page = 42, pageOffset = 0.25))
        val decoded = LibraryJson.decode(LibraryJson.encode(listOf(book)))
        assertEquals(book, decoded.single())
        assertEquals(42, decoded.single().position.page)
        assertEquals(0.25, decoded.single().position.pageOffset, 0.0)
    }
}
