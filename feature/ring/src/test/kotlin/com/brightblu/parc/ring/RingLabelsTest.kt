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
    private fun home(inboxAt: Int? = null, folderNames: List<String> = fixtureFolders): RingState {
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
        folderNames.forEachIndexed { i, name ->
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

    @Test fun aCaptionThatCannotFitIsPinnedInsteadOfThrowing() {
        // T30: a user-chosen aligned layout can put a folder where no side fits.
        val placer = FolderLabelPlacer(1)
        placer.solve(floatArrayOf(200f), floatArrayOf(400f), floatArrayOf(400f), floatArrayOf(14f),
            booleanArrayOf(true), booleanArrayOf(true), 1, Metrics.iconDp, 8f, 8f, 376f, 845f)
        assertEquals(8f, placer.x[0], 0f)
    }

    @Test fun captionFlipsAboveWhenBelowLeavesTheBounds() {
        val placer = FolderLabelPlacer(2)
        placer.solve(floatArrayOf(336f, 100f), floatArrayOf(837f, 400f), floatArrayOf(48f, 48f), floatArrayOf(14f, 14f),
            booleanArrayOf(true, true), booleanArrayOf(true, true), 2, Metrics.iconDp, 8f, 8f, 376f, 845f)
        assertEquals(837f - Metrics.iconDp / 2f - Metrics.folderLabelGapDp - 14f, placer.y[0], 0.001f)
        assertEquals(312f, placer.x[0], 0.001f)
        assertEquals(400f + Metrics.iconDp / 2f + Metrics.folderLabelGapDp, placer.y[1], 0.001f)
    }

    @Test fun captionMovesToTheSideWhenAboveAndBelowAreTaken() {
        // A neighbour icon right under and right above the folder: the caption goes beside it.
        val placer = FolderLabelPlacer(3)
        val dx = floatArrayOf(200f, 200f, 200f)
        val dy = floatArrayOf(400f, 400f + Metrics.iconDp + 8f, 400f - Metrics.iconDp - 8f)
        placer.solve(dx, dy, floatArrayOf(48f, 0f, 0f), floatArrayOf(14f, 0f, 0f),
            booleanArrayOf(true, false, false), booleanArrayOf(true, true, true), 3, Metrics.iconDp, 8f, 8f, 376f, 845f)
        val l = placer.x[0]; val t = placer.y[0]
        for (j in 1..2) assertTrue(l >= dx[j] + Metrics.iconDp / 2f || l + 48f <= dx[j] - Metrics.iconDp / 2f ||
            t >= dy[j] + Metrics.iconDp / 2f || t + 14f <= dy[j] - Metrics.iconDp / 2f)
    }

    @Test fun theLabelTokensAreFigtree12MediumOnAFaintSixDpTint() {
        assertEquals(12f, Metrics.folderLabelSp, 0f)
        assertEquals(500, Metrics.folderLabelWeight)
        assertTrue(Metrics.folderLabelTintAlpha <= 0.10f)
        assertEquals(6f, Metrics.folderLabelCornerDp, 0f)
        assertEquals(12f, Metrics.folderLabelCardInsetDp, 0f)
    }

    /** The caption tint is the card colour itself at 10 %, so the text keeps the card's ink contrast. */
    @Test fun captionInkKeepsAtLeast4Point5OnTheCardForTheBuiltInSkins() {
        for (skin in listOf(Skin.Midnight, FieldAtlas.Light, FieldAtlas.Dark, Phosphor.Skin))
            assertTrue("${skin.id} ink contrast", SkinSafety.moduleInkContrast(skin) >= 4.5)
    }

    /** T170 demo layout: four folders on the card, none may touch another icon or leave the card inset. */
    @Test fun theFourDemoFoldersStayInsideTheCardAndClearOfEveryIcon() =
        assertCaptions(home(folderNames = listOf("Social", "Tools", "Media", "Games")), 4, clearOfIcons = true)

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

    /**
     * [clearOfIcons] is the T170 rule for the 4-folder demo. The 8-folder fixture is packed tighter
     * than any real home screen, so there only the card inset and caption-vs-caption are asserted.
     */
    private fun assertCaptions(state: RingState, expected: Int = if (state.slots.any { it.label == "New" }) 9 else 8,
        clearOfIcons: Boolean = false) {
        val (xs, ys) = centres(state)
        val flags = BooleanArray(xs.size) { state.slots[it].slot is Slot.Folder }
        // Text plus the tint padding at the real 12 sp size, with some room for tracking.
        val widths = FloatArray(xs.size) { min(folderCaption(state.slots[it].label).length * 7.5f +
            Metrics.folderLabelPaddingHorizontalDp * 2, Metrics.folderLabelMaxWidthDp) }
        val heights = FloatArray(xs.size) { 15f + Metrics.folderLabelPaddingVerticalDp * 2 }
        val card = com.brightblu.parc.ring.module.ModuleCard.rect(layout.originXDp, layout.originYDp, layout.widthDp)
        val inset = Metrics.folderLabelCardInsetDp
        val placer = FolderLabelPlacer(xs.size)
        placer.solve(xs, ys, widths, heights, flags, BooleanArray(xs.size) { true }, xs.size, Metrics.iconDp,
            card.left + inset, card.top + inset, card.right - inset, card.bottom - inset)
        var captions = 0
        val half = Metrics.iconDp / 2f
        for (i in xs.indices.filter { flags[it] }) {
            captions++
            val l = placer.x[i]; val t = placer.y[i]; val r = l + widths[i]; val b = t + heights[i]
            assertTrue("caption $i leaves the card inset [$l,$t,$r,$b] in $card",
                l >= card.left + inset - 0.01f && r <= card.right - inset + 0.01f &&
                t >= card.top + inset - 0.01f && b <= card.bottom - inset + 0.01f)
            assertTrue(folderCaption(state.slots[i].label).length <= 8)
            for (j in xs.indices) {
                if (j == i) continue
                if (clearOfIcons) assertFalse("caption $i overlaps icon $j",
                    l < xs[j] + half && r > xs[j] - half && t < ys[j] + half && b > ys[j] - half)
                if (flags[j]) assertFalse("captions $i and $j intersect",
                    l < placer.x[j] + widths[j] && r > placer.x[j] && t < placer.y[j] + heights[j] && b > placer.y[j])
            }
        }
        assertEquals(expected, captions)
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
