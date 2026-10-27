package com.brightblu.parc.core.data.usage

import com.brightblu.parc.core.data.homeArcSpecs
import com.brightblu.parc.model.ActionKind
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The usage layer's decision rules (T34): the 04:00 gate, the 25 % margin, hard pins, hidden apps
 * and new apps. Pure, so every case is a fixed score map and a fixed clock.
 *
 * The layer is full (four learned slots) unless a test is about a vacancy, so a challenging app can
 * only ever be judged against the incumbent of one slot.
 */
class UsageLayerRankingTest {

    private val alpha = testId("alpha")
    private val beta = testId("beta")
    private val gamma = testId("gamma")
    private val delta = testId("delta")
    private val epsilon = testId("epsilon")
    private val zeta = testId("zeta")
    private val pinned = testId("pinned")
    private val anchors = listOf(testId("one"), testId("two"), testId("three"), testId("four"))

    private val layer = listOf(alpha, beta, gamma, delta)
    private val fitted = seededUsageLayout(layer, pinned)
    private val slots = DefaultUsageSlots
    private val installed = (layer + epsilon + pinned).toSet()

    private fun millis(text: String): Long = Instant.parse(text).toEpochMilli()

    // ---- the daily gate ---------------------------------------------------------------------

    @Test
    fun `the day turns over at 0400 local, not at midnight`() {
        val lateEvening = UsageLayerRanking.dayKey(millis("2026-10-15T23:59:00Z"), 0)
        val beforeFour = UsageLayerRanking.dayKey(millis("2026-10-16T03:59:00Z"), 0)
        val afterFour = UsageLayerRanking.dayKey(millis("2026-10-16T04:01:00Z"), 0)
        val endOfDay = UsageLayerRanking.dayKey(millis("2026-10-16T23:59:00Z"), 0)

        assertEquals(lateEvening, beforeFour)
        assertFalse(UsageLayerRanking.isDue(lateEvening, beforeFour))
        assertTrue(UsageLayerRanking.isDue(lateEvening, afterFour))
        assertEquals(afterFour, endOfDay)
        assertEquals(1, afterFour - lateEvening)
    }

    @Test
    fun `the day key follows the local offset, not UTC`() {
        val twoHours = 2 * 60 * 60 * 1000

        // 02:00 UTC on the 16th is 04:00 local at +02:00: the local day has already turned over.
        assertEquals(
            UsageLayerRanking.dayKey(millis("2026-10-16T12:00:00Z"), twoHours),
            UsageLayerRanking.dayKey(millis("2026-10-16T02:00:00Z"), twoHours),
        )
        assertTrue(
            UsageLayerRanking.isDue(
                UsageLayerRanking.dayKey(millis("2026-10-15T12:00:00Z"), twoHours),
                UsageLayerRanking.dayKey(millis("2026-10-16T02:00:00Z"), twoHours),
            ),
        )
    }

    @Test
    fun `a layer that has never ranked is due on the first start`() {
        assertTrue(UsageLayerRanking.isDue(null, 20_300))
    }

    // ---- positions --------------------------------------------------------------------------

    @Test
    fun `positions that do not exist fall back to the default layer`() {
        assertEquals(slots, UsageLayerRanking.positionsIn(fitted, slots))
        assertEquals(slots, UsageLayerRanking.positionsIn(fitted, listOf(SlotPosition("A9", 0))))
        // A2 holds five slots, so index 9 is outside the arc and the whole request is not trusted.
        assertEquals(slots, UsageLayerRanking.positionsIn(fitted, listOf(SlotPosition("A2", 9))))
        assertEquals(
            listOf(SlotPosition("A2", 1)),
            UsageLayerRanking.positionsIn(fitted, listOf(SlotPosition("A2", 1), SlotPosition("A2", 1))),
        )
    }

    @Test
    fun `a geometry without the usage arc has no layer`() {
        val noA2 = ParcLayout(
            version = 1,
            arcs = homeArcSpecs().filter { it.id != "A2" }.map { ArcAssignment(it, emptyMap()) },
        )

        assertTrue(UsageLayerRanking.positionsIn(noA2, slots).isEmpty())
    }

    // ---- the 25 % margin --------------------------------------------------------------------

    @Test
    fun `a challenger at exactly the margin does not replace the incumbent`() {
        val changes = UsageLayerRanking.plan(
            slots,
            fitted,
            installed,
            mapOf(alpha to 1.0, beta to 10.0, gamma to 10.0, delta to 10.0, epsilon to 1.25),
        )

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `a challenger above the margin replaces the incumbent of the nearest slot it beats`() {
        val changes = UsageLayerRanking.plan(
            slots,
            fitted,
            installed,
            mapOf(alpha to 1.0, beta to 10.0, gamma to 10.0, delta to 10.0, epsilon to 1.26),
        )

        assertEquals(listOf(UsageSwap(SlotPosition("A2", 0), alpha, epsilon)), changes)
    }

    @Test
    fun `the strongest newcomer takes the nearest slot it can win`() {
        val changes = UsageLayerRanking.plan(
            slots,
            fitted,
            installed,
            mapOf(alpha to 1.0, beta to 2.0, gamma to 3.0, delta to 4.0, epsilon to 10.0),
        )

        // epsilon beats alpha first, so it takes slot 0; alpha is out of the layer, nothing else moves.
        assertEquals(listOf(UsageSwap(SlotPosition("A2", 0), alpha, epsilon)), changes)
    }

    @Test
    fun `an incumbent is never displaced by an app that is already on the layer`() {
        // gamma is far more used than alpha, but gamma is already an incumbent: its slot is stable.
        val changes = UsageLayerRanking.plan(
            slots,
            fitted,
            installed,
            mapOf(alpha to 1.0, beta to 1.0, gamma to 100.0, delta to 1.0),
        )

        assertTrue(changes.isEmpty())
    }

    // ---- hard pins, hidden apps -------------------------------------------------------------

    @Test
    fun `a pinned incumbent never moves, however large the challenger`() {
        val layout = seededUsageLayout(layer, pinned, pins = setOf(alpha, pinned))

        val changes = UsageLayerRanking.plan(slots, layout, installed, mapOf(alpha to 0.0, epsilon to 1_000.0))

        assertEquals(listOf(UsageSwap(SlotPosition("A2", 1), beta, epsilon)), changes)
    }

    @Test
    fun `a hard-pinned app never enters a usage slot`() {
        val layout = seededUsageLayout(listOf(alpha), pinned, pins = anchors.toSet() + pinned)

        // The anchors are the most used apps on the device and the layer has three empty slots.
        val changes = UsageLayerRanking.plan(
            slots,
            layout,
            installed + anchors,
            anchors.associateWith { 1_000.0 },
        )

        // Every empty slot is filled in reach order; no anchor is ever written.
        assertEquals(
            listOf(SlotPosition("A2", 1), SlotPosition("A2", 2), SlotPosition("A2", 3)),
            changes.map { it.position },
        )
        assertEquals(listOf(beta, delta, epsilon), changes.map { it.to })
        assertTrue(changes.none { it.to in anchors })
    }

    @Test
    fun `a hidden app never enters a usage slot`() {
        val layout = seededUsageLayout(layer, pinned, hidden = setOf(epsilon))

        val changes = UsageLayerRanking.plan(slots, layout, installed, mapOf(alpha to 1.0, epsilon to 1_000.0))

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `a slot holding a folder or the gear is never overwritten`() {
        val layout = usageLayerLayout(
            slots = mapOf(
                SlotPosition("A2", 0) to Slot.App(alpha),
                SlotPosition("A2", 1) to Slot.Folder(FolderId("social")),
                SlotPosition("A2", 2) to Slot.Action(ActionKind.Settings),
                SlotPosition("A2", 4) to Slot.App(pinned),
            ),
            pins = setOf(pinned),
        )

        val changes = UsageLayerRanking.plan(
            slots,
            layout,
            installed,
            mapOf(alpha to 100.0, epsilon to 50.0),
        )

        // Slots 1 and 2 are somebody else's; only the genuinely empty slot 3 takes a candidate.
        assertEquals(listOf(UsageSwap(SlotPosition("A2", 3), null, epsilon)), changes)
    }

    @Test
    fun `a hidden incumbent is replaced by the best candidate without needing the margin`() {
        val layout = seededUsageLayout(layer, pinned, hidden = setOf(beta))

        val changes = UsageLayerRanking.plan(
            slots,
            layout,
            installed,
            mapOf(alpha to 5.0, beta to 5.0, gamma to 5.0, delta to 5.0, epsilon to 1.0),
        )

        assertEquals(listOf(UsageSwap(SlotPosition("A2", 1), beta, epsilon)), changes)
    }

    @Test
    fun `a slot whose app is gone is filled by the best candidate`() {
        val remaining = (layer - beta + epsilon + pinned).toSet()

        val changes = UsageLayerRanking.plan(
            slots,
            fitted,
            remaining,
            mapOf(alpha to 5.0, gamma to 5.0, delta to 5.0, epsilon to 1.0),
        )

        assertEquals(listOf(UsageSwap(SlotPosition("A2", 1), beta, epsilon)), changes)
    }

    @Test
    fun `an empty slot is filled by the best candidate`() {
        val layout = seededUsageLayout(listOf(alpha, beta, gamma), pinned)

        val changes = UsageLayerRanking.plan(
            slots,
            layout,
            installed,
            mapOf(alpha to 5.0, beta to 5.0, gamma to 5.0, epsilon to 0.5),
        )

        assertEquals(listOf(UsageSwap(SlotPosition("A2", 3), null, epsilon)), changes)
    }

    // ---- new apps ---------------------------------------------------------------------------

    @Test
    fun `a new app with no launches never displaces an incumbent`() {
        val changes = UsageLayerRanking.plan(slots, fitted, installed, emptyMap())

        assertTrue(changes.isEmpty())
        assertFalse(UsageLayerRanking.beats(emptyMap(), epsilon, alpha))
    }

    @Test
    fun `a new app with the best score wins a slot`() {
        val changes = UsageLayerRanking.plan(
            slots,
            fitted,
            installed,
            mapOf(alpha to 0.1, beta to 0.1, gamma to 0.1, delta to 0.1, epsilon to 0.2),
        )

        assertEquals(listOf(UsageSwap(SlotPosition("A2", 0), alpha, epsilon)), changes)
    }

    @Test
    fun `the margin compares against the incumbent, not the raw best score`() {
        assertTrue(UsageLayerRanking.beats(mapOf(alpha to 4.0, epsilon to 5.01), epsilon, alpha))
        assertFalse(UsageLayerRanking.beats(mapOf(alpha to 4.0, epsilon to 5.0), epsilon, alpha))
        assertFalse(UsageLayerRanking.beats(mapOf(alpha to 0.0, epsilon to 0.0), epsilon, alpha))
    }

    @Test
    fun `a tie between two candidates is broken by id and does not depend on iteration order`() {
        val scores = mapOf(
            alpha to 1.0,
            beta to 10.0,
            gamma to 10.0,
            delta to 10.0,
            epsilon to 2.0,
            zeta to 2.0,
        )
        val first = UsageLayerRanking.plan(slots, fitted, installed + zeta, scores)
        val second = UsageLayerRanking.plan(slots, fitted, (installed + zeta).reversed().toSet(), scores)

        assertEquals(listOf(UsageSwap(SlotPosition("A2", 0), alpha, epsilon)), first)
        assertEquals(first, second)
    }

    @Test
    fun `nothing moves while every incumbent outranks every candidate`() {
        val changes = UsageLayerRanking.plan(
            slots,
            fitted,
            installed,
            mapOf(alpha to 9.0, beta to 8.0, gamma to 7.0, delta to 6.0, epsilon to 1.0),
        )

        assertTrue(changes.isEmpty())
    }
}
