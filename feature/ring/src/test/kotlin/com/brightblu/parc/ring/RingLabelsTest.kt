package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RingLabelsTest {
    private val layout = RingLayout(384f, 853f)
    /** Eight fixture folder names; the longest must still render whole. */
    private val fixtureFolders = listOf("Tools", "Media", "Work", "Life", "Games", "Social", "Shop", "News")

    /**
     * Reference fixture layout: four A1 anchors, the eight-item A2 usage ring, seven folders on A3,
     * the gear on A4[0] and Life on A4[1]. A2 items past index 4 reach the window only by rotating
     * (T36/T37).
     */
    private fun home(inboxAt: Int? = null): RingState {
        val anchors = listOf("Alpha", "Bravo", "Charlie", "Delta")
        val usage = listOf("Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliet", "Kilo", "Lima")
        val slots = mutableListOf<RingSlot>()
        val arcs = layout.arcs
        anchors.forEachIndexed { i, label ->
            if (i < arcs[0].slotCapacity) slots += RingSlot(SlotPosition(arcs[0].id, i), Slot.App(AppId("a1/$i")), label)
        }
        usage.forEachIndexed { i, label ->
            slots += RingSlot(SlotPosition(arcs[1].id, i), Slot.App(AppId("a2/$i")), label)
        }
        fixtureFolders.forEachIndexed { i, name ->
            val arc = if (i < arcs[2].slotCapacity) arcs[2] else arcs[3]
            val index = if (arc === arcs[2]) i else 1
            if (index < arc.slotCapacity) {
                slots += RingSlot(SlotPosition(arc.id, index), Slot.Folder(FolderId(name.lowercase())), name)
            }
        }
        if (arcs[3].slotCapacity > 0) slots += RingSlot(SlotPosition(arcs[3].id, 0), Slot.Action(ActionKind.Settings), "Settings")
        if (inboxAt != null) slots += RingSlot(SlotPosition(arcs[3].id, inboxAt), Slot.Folder(FolderId("new")), "New")
        return RingScenes.home(layout, slots, "09:41")
    }

    private fun social(): RingState = RingScenes.folder(layout, Folder(FolderId("social"), "Social"),
        List(18) { i -> RingSlot(SlotPosition("unused", i), Slot.App(AppId("social/$i")),
            listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel")[i % 8]) })

    @Test fun everyFocusedHomeLabelStaysInsideScreenAndKeepOuts() = assertSafe(home())
    @Test fun everyFocusedSocialLabelStaysInsideScreenAndKeepOuts() = assertSafe(social())
    @Test fun theEightFixtureFolderNamesRenderWhole() = assertCaptions(home())

    @Test fun theEightFixtureFolderNamesAreNeverTruncated() {
        assertEquals(8, fixtureFolders.size)
        for (name in fixtureFolders) {
            assertEquals(name, folderCaption(name))
            assertTrue("$name fits the 8-character rule", name.length <= Metrics.folderLabelMaxChars)
        }
        // Eight characters at 11 sp need the T37 cap; a 7 dp-per-character model is conservative.
        assertTrue(8 * 7f <= Metrics.folderLabelMaxWidthDp)
    }

    @Test fun longerNamesStillCollapseToOneEllipsizedLine() {
        assertEquals("Music &…", folderCaption("Music & Media"))
        assertEquals("Getting…", folderCaption("Getting Around"))
        // The ellipsis drops a trailing space rather than keeping it before the mark.
        assertEquals("Health…", folderCaption("Health & Fitness"))
        assertEquals("Reading", folderCaption("Reading"))
        for (name in listOf("Music & Media", "Getting Around", "Health & Fitness")) {
            assertTrue(folderCaption(name).length <= Metrics.folderLabelMaxChars)
            assertTrue(folderCaption(name).endsWith("…"))
        }
    }

    @Test fun theSeededInboxInTheLastVacancyAlsoClearsCaptionsAndIcons() =
        assertCaptions(home(inboxAt = layout.arcs[3].slotCapacity - 1))

    @Test fun aCaptionThatCannotReachTheMarginIsClampedInsteadOfThrowing() {
        // T30: a user-chosen aligned layout can put a folder where the old strict assertion failed.
        val wide = FolderLabels(floatArrayOf(370f), floatArrayOf(840f), floatArrayOf(90f), floatArrayOf(14f),
            booleanArrayOf(true), 384f, 853f)
        assertTrue(wide.x[0] >= Metrics.folderLabelScreenMarginDp)
        assertTrue(wide.x[0] + 90f <= 384f - Metrics.folderLabelScreenMarginDp)
        assertTrue(wide.y[0] + 14f <= 853f - Metrics.folderLabelScreenMarginDp)

        // Wider than the whole safe band: pinned to the left margin rather than crashing.
        val impossible = FolderLabels(floatArrayOf(200f), floatArrayOf(400f), floatArrayOf(400f), floatArrayOf(14f),
            booleanArrayOf(true), 384f, 853f)
        assertEquals(Metrics.folderLabelScreenMarginDp, impossible.x[0], 0f)
    }

    @Test fun captionOnlyFlipsAboveWhenBelowLeavesScreenMargin() {        val xs = floatArrayOf(336f, 100f)
        val ys = floatArrayOf(797f, 830f)
        val placed = FolderLabels(xs, ys, floatArrayOf(48f, 48f), floatArrayOf(14f, 14f),
            booleanArrayOf(true, true), 384f, 853f)
        assertEquals(819f, placed.y[0], 0f) // Bottom/right touch keep-outs do not apply.
        assertEquals(794f, placed.y[1], 0f)
        assertEquals(312f, placed.x[0], 0f)
        assertEquals(76f, placed.x[1], 0f)
    }

    /**
     * Drawn slot centres in dp. Folded window angles, because a ring item past its arc capacity
     * (the A2 usage ring) has no stored slot angle (T36/T37) and is only on screen once rotated.
     */
    private fun centres(state: RingState): Pair<FloatArray, FloatArray> {
        val arcs = state.slots.map { slot -> state.arcs.first { it.id == slot.position.arcId } }
        val xs = FloatArray(arcs.size)
        val ys = FloatArray(arcs.size)
        for (i in arcs.indices) {
            val slot = state.slots[i]
            val count = state.slots.count { it.position.arcId == slot.position.arcId }
            val angle = Math.toRadians(RingRotation.windowAngleDeg(arcs[i], slot.position.index, 0f, count).toDouble())
            xs[i] = arcs[i].originXDp + cos(angle).toFloat() * arcs[i].radiusDp
            ys[i] = arcs[i].originYDp + sin(angle).toFloat() * arcs[i].radiusDp
        }
        return xs to ys
    }

    private fun assertCaptions(state: RingState) {
        val (xs, ys) = centres(state)
        val flags = BooleanArray(xs.size) { state.slots[it].slot is Slot.Folder }
        // Conservative bounds exceed the actual 11sp raster (including tracking and rounding).
        val widths = FloatArray(xs.size) { min(folderCaption(state.slots[it].label).length * 7f, Metrics.folderLabelMaxWidthDp) }
        val heights = FloatArray(xs.size) { 14f }
        val placed = FolderLabels(xs, ys, widths, heights, flags, 384f, 853f)
        var captions = 0
        for (i in xs.indices.filter { flags[it] }) {
            captions++
            val l = placed.x[i]; val t = placed.y[i]; val r = l + widths[i]; val b = t + heights[i]
            assertTrue(l >= Metrics.folderLabelScreenMarginDp && r <= 384f - Metrics.folderLabelScreenMarginDp)
            assertTrue(t >= Metrics.folderLabelScreenMarginDp && b <= 853f - Metrics.folderLabelScreenMarginDp)
            assertEquals(xs[i], (l + r) / 2f, 0.001f)
            val below = ys[i] + Metrics.iconDp / 2f + Metrics.folderLabelGapDp
            assertEquals(if (below + heights[i] <= 853f - Metrics.folderLabelScreenMarginDp) below
                else ys[i] - Metrics.iconDp / 2f - Metrics.folderLabelGapDp - heights[i], t, 0.001f)
            assertTrue(folderCaption(state.slots[i].label).length <= 8)
            for (j in xs.indices) {
                val dx = xs[j] - xs[j].coerceIn(l, r); val dy = ys[j] - ys[j].coerceIn(t, b)
                assertTrue("caption $i intersects icon $j", dx * dx + dy * dy >= Metrics.iconDp * Metrics.iconDp / 4f)
                if (flags[j] && i != j) assertFalse("captions $i and $j intersect",
                    l < placed.x[j] + widths[j] && r > placed.x[j] && t < placed.y[j] + heights[j] && b > placed.y[j])
            }
        }
        // The seven A3 folders plus Life, and the opt-in inbox caption when asked for.
        assertEquals(if (state.slots.any { it.label == "New" }) 9 else 8, captions)
    }
    private fun assertSafe(state: RingState) {
        val arcs = state.slots.map { slot -> state.arcs.first { it.id == slot.position.arcId } }
        val (xs, ys) = centres(state)
        // Include surface-pill padding and conservative fixed-12sp text bounds.
        val widths = FloatArray(arcs.size) { min(state.slots[it].label.length * 7f + Metrics.labelPaddingHorizontalDp * 2, Metrics.labelMaxWidthDp) }
        val heights = FloatArray(arcs.size) { 16f + Metrics.labelPaddingVerticalDp * 2 }
        val placed = RingLabels(xs, ys, widths, heights, layout.originXDp, layout.originYDp, layout.widthDp, layout.heightDp)
        for (focus in state.slots.indices) {
            val l = placed.x[focus]; val t = placed.y[focus]
            val r = l + widths[focus]; val b = t + heights[focus]
            assertTrue("focused $focus outside safe screen [$l,$t,$r,$b]",
                l >= Metrics.keepOutLeftDp && r <= layout.widthDp - Metrics.keepOutRightDp &&
                t >= Metrics.keepOutTopDp && b <= layout.heightDp - Metrics.keepOutBottomDp)
            // When the outward side crosses a keep-out, the pill flips inward.
            if (abs(xs[focus] - layout.originXDp) >= abs(ys[focus] - layout.originYDp) &&
                xs[focus] - Metrics.iconDp / 2 - Metrics.labelGapDp - widths[focus] < Metrics.keepOutLeftDp) {
                assertTrue(l >= xs[focus] + Metrics.iconDp / 2 + Metrics.labelGapDp)
            }
        }
    }
}
