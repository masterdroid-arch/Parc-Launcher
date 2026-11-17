package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.reader.ReaderMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T210: the pure geometry and thresholds behind the reader's cover/page motion. */
class T210MotionTest {
    private val screen = ReaderMotion.full(1080f, 2400f)
    private val cover = ReaderMotion.Rect(120f, 600f, 420f, 1020f)

    @Test fun aCoverGrowsIntoTheFullPageAndBack() {
        val start = ReaderMotion.coverToPage(cover, screen, 0f)
        val middle = ReaderMotion.coverToPage(cover, screen, 0.5f)
        val end = ReaderMotion.coverToPage(cover, screen, 1f)
        assertEquals(cover, start)
        assertEquals(screen, end)
        assertEquals(405f, middle.centerX, 0.01f)
        assertEquals(1005f, middle.centerY, 0.01f)
        assertEquals(ReaderMotion.coverToPage(cover, screen, 1f), ReaderMotion.coverToPage(cover, screen, 4f))
        assertEquals(ReaderMotion.coverToPage(cover, screen, 0f), ReaderMotion.coverToPage(cover, screen, -2f))
    }

    @Test fun aBrokenCoverRectangleIsIgnored() {
        assertNull(ReaderMotion.coverOrNull(0f, 0f, 0f, 100f))
        assertNull(ReaderMotion.coverOrNull(10f, 20f, 5f, 30f))
        assertNull(ReaderMotion.coverOrNull(Float.NaN, 0f, 10f, 10f))
        assertEquals(ReaderMotion.Rect(10f, 20f, 30f, 50f), ReaderMotion.coverOrNull(10f, 20f, 30f, 50f))
    }

    @Test fun thePullFollowsTheFingerAndClamps() {
        assertEquals(0f, ReaderMotion.pullProgress(0f, 2400f), 0f)
        assertEquals(0f, ReaderMotion.pullProgress(-40f, 2400f), 0f)
        val quarter = ReaderMotion.pullProgress(330f, 2400f)
        val half = ReaderMotion.pullProgress(660f, 2400f)
        assertTrue(half > quarter)
        assertEquals(1f, ReaderMotion.pullProgress(5000f, 2400f), 0f)
        assertEquals(0f, ReaderMotion.pullProgress(100f, 0f), 0f)
    }

    @Test fun aSlowPullNeedsDistanceAndAFlickClosesEarlier() {
        assertFalse(ReaderMotion.shouldClose(0.10f, 0f))
        assertTrue(ReaderMotion.shouldClose(Metrics.readerPullCloseFraction, 0f))
        assertFalse(ReaderMotion.shouldClose(0.20f, Metrics.readerPullFlingPxPerSec - 1f))
        assertTrue(ReaderMotion.shouldClose(0.05f, Metrics.readerPullFlingPxPerSec))
        assertEquals(1f, ReaderMotion.pullTarget(0.05f, Metrics.readerPullFlingPxPerSec), 0f)
        assertEquals(0f, ReaderMotion.pullTarget(0.05f, 0f), 0f)
    }

    @Test fun anIncompletePullSpringsBackToThePage() {
        assertEquals(0f, ReaderMotion.pullTarget(0.10f, -1_500f), 0f)
        assertEquals(1f, ReaderMotion.pullTarget(0.75f, -1_500f), 0f)
    }

    @Test fun theCoverOnlyAppearsNearTheEndOfTheClose() {
        assertEquals(0f, ReaderMotion.closeCoverAlpha(0f), 0f)
        assertEquals(0f, ReaderMotion.closeCoverAlpha(Metrics.readerCoverRevealStart), 0f)
        assertTrue(ReaderMotion.closeCoverAlpha(0.8f) > 0f)
        assertEquals(1f, ReaderMotion.closeCoverAlpha(1f), 0f)
    }

    @Test fun openWithRisesFromTheBottom() {
        assertEquals(2400f, ReaderMotion.riseOffsetPx(2400f, 0f), 0f)
        assertEquals(1200f, ReaderMotion.riseOffsetPx(2400f, 0.5f), 0f)
        assertEquals(0f, ReaderMotion.riseOffsetPx(2400f, 1f), 0f)
        assertEquals(0f, ReaderMotion.riseOffsetPx(2400f, 3f), 0f)
    }

    @Test fun aPageFollowsTheFingerAndResistsAtTheEnds() {
        assertEquals(-300f, ReaderMotion.pageDragOffset(-300f, 1080f, false, false), 0f)
        val normal = ReaderMotion.pageDragOffset(300f, 1080f, false, false)
        val resisted = ReaderMotion.pageDragOffset(300f, 1080f, true, false)
        assertEquals(300f, normal, 0f)
        assertEquals(300f * Metrics.readerPageEdgeResistance, resisted, 0.01f)
        assertEquals(-300f * Metrics.readerPageEdgeResistance,
            ReaderMotion.pageDragOffset(-300f, 1080f, false, true), 0.01f)
        assertEquals(1080f, ReaderMotion.pageDragOffset(5000f, 1080f, false, false), 0f)
        assertEquals(-1080f, ReaderMotion.pageDragOffset(-5000f, 1080f, false, false), 0f)
    }

    @Test fun aSlowDragTurnsOnlyPastTheThreshold() {
        val width = 1080f
        assertEquals(2, ReaderMotion.pageTarget(2, 5, -width * 0.05f, 0f, width))
        assertEquals(3, ReaderMotion.pageTarget(2, 5, -width * 0.25f, 0f, width))
        assertEquals(1, ReaderMotion.pageTarget(2, 5, width * 0.25f, 0f, width))
        assertEquals(3, ReaderMotion.pageTarget(2, 5, -width * 0.20f, -10f, width))
    }

    @Test fun aFastFlickTurnsBeforeTheDistanceThreshold() {
        val width = 1080f
        assertEquals(3, ReaderMotion.pageTarget(2, 5, -20f, -Metrics.readerPageFlingPxPerSec, width))
        assertEquals(1, ReaderMotion.pageTarget(2, 5, 20f, Metrics.readerPageFlingPxPerSec, width))
        // The flick decides the direction even if the finger ends up moving slightly the other way.
        assertEquals(3, ReaderMotion.pageTarget(2, 5, 40f, -Metrics.readerPageFlingPxPerSec, width))
    }

    @Test fun theFirstAndLastPageNeverPassTheirEnds() {
        val width = 1080f
        assertEquals(0, ReaderMotion.pageTarget(0, 5, 900f, 5_000f, width))
        assertEquals(4, ReaderMotion.pageTarget(4, 5, -900f, -5_000f, width))
        assertEquals(0, ReaderMotion.pageTarget(0, 1, -900f, -5_000f, width))
        assertEquals(0, ReaderMotion.turnTarget(0, 5, -1))
        assertEquals(4, ReaderMotion.turnTarget(4, 5, 1))
        assertEquals(3, ReaderMotion.turnTarget(2, 5, 1))
    }

    @Test fun theSettleOffsetUsesThePageWidth() {
        assertEquals(-1080f, ReaderMotion.pageSettleOffset(2, 3, 1080f), 0f)
        assertEquals(1080f, ReaderMotion.pageSettleOffset(2, 1, 1080f), 0f)
        assertEquals(0f, ReaderMotion.pageSettleOffset(2, 2, 1080f), 0f)
        assertTrue(ReaderMotion.returnsToCurrentPage(2, 2))
        assertFalse(ReaderMotion.returnsToCurrentPage(2, 3))
    }

    @Test fun theCoverIsCentreCroppedWithoutStretching() {
        val wide = ReaderMotion.sourceCrop(320, 448, 1080f, 2400f)
        assertTrue(wide.width < 320f)
        assertEquals(448f, wide.height, 0f)
        val tall = ReaderMotion.sourceCrop(320, 448, 320f, 448f)
        assertEquals(320f, tall.width, 0f)
        assertTrue(tall.height <= 448f)
    }

    @Test fun theTopZoneAndHorizontalGestureRulesAreExplicit() {
        assertTrue(ReaderMotion.startsInPullZone(10f, 2400f))
        assertTrue(ReaderMotion.startsInPullZone(2400f * Metrics.readerPullZoneFraction, 2400f))
        assertFalse(ReaderMotion.startsInPullZone(2400f * Metrics.readerPullZoneFraction + 1f, 2400f))
        assertFalse(ReaderMotion.startsInPullZone(-1f, 2400f))
        assertTrue(ReaderMotion.isHorizontalGesture(40f, 5f, 10f))
        assertFalse(ReaderMotion.isHorizontalGesture(5f, 40f, 10f))
        assertFalse(ReaderMotion.isHorizontalGesture(40f, 40f, 10f))
    }

    @Test fun theReaderWindowIsSeeThroughAndHasNoBlur() {
        val styles = File("src/main/res/values/styles.xml").readText()
        val readerStyle = Regex("""<style name="Theme\.Parc\.Reader"[^>]*>(.*?)</style>""",
            RegexOption.DOT_MATCHES_ALL).find(styles)
        assertTrue("Theme.Parc.Reader must exist", readerStyle != null)
        val body = readerStyle!!.groupValues[1]
        assertTrue(body.contains("windowIsTranslucent"))
        assertTrue(body.contains("windowBackground"))
        assertTrue(body.contains("windowSplashScreenBackground"))
        assertTrue(body.contains("transparent"))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = Regex("""<activity[^>]*android:name="\.reader\.ReaderActivity"[^>]*>""").find(manifest)
        assertTrue(activity!!.value.contains("Theme.Parc.Reader"))

        listOf(
            "src/main/kotlin/com/brightblu/parc/reader/ReaderMotionUi.kt",
            "src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt",
            "src/main/kotlin/com/brightblu/parc/reader/ReaderPdf.kt",
        ).forEach { path ->
            val source = File(path).readText()
            assertFalse("$path must not blur", source.contains("RenderEffect"))
            assertFalse("$path must not blur", source.contains("BlurMaskFilter"))
        }
    }
}
