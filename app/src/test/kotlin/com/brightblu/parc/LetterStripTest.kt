package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingRotation
import org.junit.Assert.*
import org.junit.Test

/** OPUS4: the All-apps A to Z strip. */
class LetterStripTest {
    private fun app(label: String) = AppEntry(AppId("p.${label.hashCode()}/Main@0"), label, "p.${label.hashCode()}", false, 0)

    /** Labels exactly as the ring shows them (same ordering rule). */
    private fun ring(vararg labels: String) = allAppsOrder(labels.map(::app), ParcLayout()).map { it.label }

    private fun letter(l: String) = LetterStrip.Letters.indexOf(l)

    @Test fun firstAppPerLetterMatchesTheRingOrder() {
        val labels = ring("Zoom", "camera", "Calendar", "Éclair", "eBay", "2048", "@home", "Maps")
        // 2048 and @home go under #, Éclair folds to E, case is ignored.
        val first = LetterStrip.firstIndex(labels)
        assertEquals(0, first[letter("#")])
        assertEquals(labels.indexOf("Calendar"), first[letter("C")])
        assertEquals("Calendar", labels[first[letter("C")]])
        assertEquals(labels.indexOf("eBay"), first[letter("E")])
        assertEquals(labels.indexOf("Maps"), first[letter("M")])
        assertEquals(labels.indexOf("Zoom"), first[letter("Z")])
        assertEquals(-1, first[letter("B")])
        assertEquals(27, LetterStrip.Letters.size)
    }

    @Test fun digitsAndSymbolsAreGroupedUnderHash() {
        listOf("2048", "@home", "_x", "  ", "", "…").forEach { assertEquals(it, 0, LetterStrip.letterOf(it)) }
        assertEquals(letter("A"), LetterStrip.letterOf("Ámbito"))
        assertEquals(letter("Z"), LetterStrip.letterOf("zed"))
    }

    @Test fun emptyLettersAreSkippedToTheNearestLetterWithApps() {
        val first = LetterStrip.firstIndex(ring("Alpha", "Delta", "Echo"))
        assertEquals(letter("A"), LetterStrip.nearestEnabled(letter("B"), first))
        assertEquals(letter("D"), LetterStrip.nearestEnabled(letter("C"), first))
        assertEquals(letter("E"), LetterStrip.nearestEnabled(letter("Z"), first))
        assertEquals(letter("A"), LetterStrip.nearestEnabled(letter("#"), first))
        assertEquals(-1, LetterStrip.nearestEnabled(letter("A"), LetterStrip.firstIndex(emptyList())))
    }

    @Test fun jumpingBringsTheFirstAppToTheFrontTheShortWayAndKeepsWrapping() {
        val labels = ring(*Array(40) { "App %02d".format(it) }, "Zed")
        val first = LetterStrip.firstIndex(labels)
        val z = first[letter("Z")]
        val target = RingRotation.nearestOffsetFor(z, 3f, labels.size)
        assertEquals(z, RingRotation.frontItem(target, labels.size))
        assertTrue("short way round: Z is just before A", target < 3f)
        assertEquals(letter("Z"), LetterStrip.frontLetter(target, labels))
        // Hand turning afterwards still wraps: one slot further is back at the first app.
        assertEquals(0, RingRotation.frontItem(target + 1f, labels.size))
        assertEquals(letter("A"), LetterStrip.frontLetter(target + 1f, labels))
    }

    @Test fun onlyOnTheAllAppsRingWhenItTurnsAndNothingCoversIt() {
        assertTrue(LetterStrip.visible(Layer.AllApps, false, false, 180, 7))
        assertFalse(LetterStrip.visible(Layer.Home, false, false, 180, 7))
        assertFalse(LetterStrip.visible(Layer.Folder, false, false, 180, 7))
        assertFalse("search open", LetterStrip.visible(Layer.AllApps, true, false, 180, 7))
        assertFalse("editing", LetterStrip.visible(Layer.AllApps, false, true, 180, 7))
        assertFalse("everything fits, nothing to jump to", LetterStrip.visible(Layer.AllApps, false, false, 6, 7))
    }

    // ---- OPUS5: letters on an inner arc around the hub -------------------------------------

    private fun ringFor(w: Float, h: Float): ArcSpec = com.brightblu.parc.ring.RingScenes.allApps(RingLayout(w, h),
        List(180) { RingSlot(SlotPosition("p", 0), Slot.App(AppId("a/$it@0")), "A$it") }).arcs.single()

    private fun arcFor(w: Float, h: Float): Pair<ArcSpec, LetterArc.Arc> {
        val ring = ringFor(w, h)
        val top = com.brightblu.parc.ring.searchButtonTopDp(listOf(ring))
        return ring to LetterArc.place(ring.originXDp, ring.originYDp, ring, 48f,
            Metrics.searchButtonLeftDp + Metrics.searchButtonTouchDp / 2f, top + Metrics.searchButtonTouchDp / 2f)
    }

    @Test fun angleAroundTheHubPicksTheLetterHashAtTheTopZAtTheLeft() {
        val (_, arc) = arcFor(384f, 853f)
        assertEquals(letter("#"), LetterArc.letterAt(arc, arc.endDeg))
        assertEquals(letter("Z"), LetterArc.letterAt(arc, arc.startDeg))
        for (i in LetterStrip.Letters.indices) {
            assertEquals(i, LetterArc.letterAt(arc, LetterArc.angleOfLetter(arc, i)))
            // Just under half a step either side still picks the same letter.
            assertEquals(i, LetterArc.letterAt(arc, LetterArc.angleOfLetter(arc, i) + LetterArc.step(arc) * 0.45f))
            assertEquals(i, LetterArc.letterAt(arc, LetterArc.angleOfLetter(arc, i) - LetterArc.step(arc) * 0.45f))
        }
        assertEquals("past the top end clamps", letter("#"), LetterArc.letterAt(arc, 300f))
        assertEquals("past the left end clamps", letter("Z"), LetterArc.letterAt(arc, 150f))
        // A real touch: the point drawn for M maps back to M.
        val (x, y) = LetterArc.point(arc, LetterArc.angleOfLetter(arc, letter("M")))
        assertTrue(LetterArc.inZone(arc, x, y))
        assertEquals(letter("M"), LetterArc.letterAt(arc, LetterArc.angleOf(arc, x, y)))
    }

    @Test fun theHubKeepsItsTouchesAndTheLetterBandIsARing() {
        for ((w, h) in listOf(384f to 853f, 360f to 800f, 411f to 914f)) {
            val (ring, arc) = arcFor(w, h)
            val hubReach = Metrics.hubTouchDp / 2f
            assertTrue("$w x $h: band starts well outside the hub", arc.inner >= hubReach + Metrics.letterArcHubGapDp)
            // Every touch on the hub, and a joystick pull up to the aim dead zone and beyond, starts outside the band.
            for (deg in 0 until 360 step 5) for (r in listOf(0f, 10f, hubReach, hubReach + Metrics.letterArcHubGapDp - 1f)) {
                val a = Math.toRadians(deg.toDouble())
                val x = ring.originXDp + kotlin.math.cos(a).toFloat() * r
                val y = ring.originYDp + kotlin.math.sin(a).toFloat() * r
                assertFalse("$w x $h hub point $deg/$r", LetterArc.inZone(arc, x, y))
            }
            // Outside the band (too far in, too far out) is not a letter either.
            val (ix, iy) = LetterArc.point(arc, LetterArc.angleOfLetter(arc, 10), arc.inner - 1f)
            val (ox, oy) = LetterArc.point(arc, LetterArc.angleOfLetter(arc, 10), arc.outer + 1f)
            assertFalse(LetterArc.inZone(arc, ix, iy))
            assertFalse(LetterArc.inZone(arc, ox, oy))
        }
    }

    @Test fun theLettersStayClearOfTheRingTheSearchButtonAndTheScreenEdges() {
        for ((w, h) in listOf(384f to 853f, 360f to 800f, 411f to 914f)) {
            val (ring, arc) = arcFor(w, h)
            assertTrue("$w x $h: band inside the ring's icons", arc.outer <= ring.radiusDp - 24f)
            assertTrue("$w x $h: letters at least 12 dp apart along the arc",
                Math.toRadians(LetterArc.step(arc).toDouble()) * arc.radius >= 12.0)
            val searchTop = com.brightblu.parc.ring.searchButtonTopDp(listOf(ring))
            val searchX = Metrics.searchButtonLeftDp + Metrics.searchButtonTouchDp / 2f
            val searchY = searchTop + Metrics.searchButtonTouchDp / 2f
            for (i in LetterStrip.Letters.indices) {
                val angle = LetterArc.angleOfLetter(arc, i)
                for (r in listOf(arc.inner, arc.radius, arc.outer)) {
                    val (x, y) = LetterArc.point(arc, angle, r)
                    assertTrue("$w x $h letter $i inside the screen", x in Metrics.keepOutLeftDp..(w - Metrics.keepOutRightDp) && y in 0f..h)
                    assertTrue("$w x $h letter $i clear of search",
                        kotlin.math.hypot(x - searchX, y - searchY) > Metrics.searchButtonTouchDp / 2f + Metrics.gridDp * 2)
                }
            }
        }
    }
}
