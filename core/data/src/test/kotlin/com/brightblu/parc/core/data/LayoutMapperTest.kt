package com.brightblu.parc.core.data

import com.brightblu.parc.model.ActionKind
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutMapperTest {

    private val alpha = appId("com.alpha/com.alpha.Main")
    private val beta = appId("com.beta/com.beta.Main")
    private val social = FolderId("social")

    private fun layoutWithEverySlotKind(): ParcLayout = ParcLayout(
        version = LayoutVersion,
        arcs = homeArcSpecs().map { spec ->
            when (spec.id) {
                "A1" -> ArcAssignment(spec, mapOf(0 to Slot.App(alpha), 2 to Slot.App(beta)))
                "A2" -> ArcAssignment(spec, mapOf(1 to Slot.Folder(social), 4 to Slot.Action(ActionKind.Settings)))
                "A3" -> ArcAssignment(spec, mapOf(3 to Slot.Link("https://example.invalid/chat", "Open chat")))
                else -> ArcAssignment(spec, emptyMap())
            }
        },
        folders = listOf(Folder(social, "Social", setOf("social", "chat"), listOf(beta, alpha))),
        pins = setOf(beta),
        hidden = setOf(alpha),
    )

    @Test
    fun `round trip keeps every slot kind, hole and slot index`() {
        val original = layoutWithEverySlotKind()

        val restored = LayoutMapper.toLayout(LayoutMapper.toRows(original), homeArcSpecs())

        assertEquals(original, restored)
    }

    @Test
    fun `empty positions stay absent from the rows`() {
        val rows = LayoutMapper.toRows(layoutWithEverySlotKind())

        assertEquals(5, rows.assignments.size)
        assertTrue(rows.assignments.none { it.arcId == "A4" })
        assertEquals(listOf(0, 2), rows.assignments.filter { it.arcId == "A1" }.map { it.slotIndex })
    }

    @Test
    fun `the pinned flag rides on the app row only`() {
        val rows = LayoutMapper.toRows(layoutWithEverySlotKind())
        val appRows = rows.assignments.filter { it.kind == LayoutMapper.KindApp }

        assertEquals(
            setOf("com.beta/com.beta.Main@0"),
            appRows.filter { it.pinned }.map { it.appId }.toSet(),
        )
        assertEquals(
            setOf("com.alpha/com.alpha.Main@0"),
            appRows.filterNot { it.pinned }.map { it.appId }.toSet(),
        )
        assertEquals(setOf(beta), LayoutMapper.toLayout(rows, homeArcSpecs()).pins)
    }

    @Test
    fun `folder rows keep the stored order and the tags`() {
        val rows = LayoutMapper.toRows(layoutWithEverySlotKind())

        assertEquals(listOf("com.beta/com.beta.Main@0", "com.alpha/com.alpha.Main@0"), rows.folderApps.map { it.appId })
        assertEquals(listOf(0, 1), rows.folderApps.map { it.position })
        assertEquals(setOf("social", "chat"), LayoutMapper.toLayout(rows, homeArcSpecs()).folders.single().tags)
    }

    @Test
    fun `hidden ids come back as a set`() {
        val rows = LayoutMapper.toRows(layoutWithEverySlotKind())

        assertEquals(setOf(alpha), LayoutMapper.toLayout(rows, homeArcSpecs()).hidden)
    }

    @Test
    fun `every arc keeps its spec even when it holds nothing`() {
        val restored = LayoutMapper.toLayout(LayoutMapper.toRows(emptyLayout()), homeArcSpecs())

        assertEquals(homeArcSpecs().map { it.id }, restored.arcs.map { it.arc.id })
        assertEquals(Layer.Home, restored.arcs.first().arc.layer)
        assertTrue(restored.arcs.all { it.slots.isEmpty() })
    }

    @Test
    fun `rows for an arc the caller does not know are dropped`() {
        val rows = LayoutRows(
            assignments = listOf(
                SlotAssignmentEntity("A1", 0, LayoutMapper.KindApp, appId = alpha.value, pinned = true),
                SlotAssignmentEntity("Z9", 0, LayoutMapper.KindApp, appId = beta.value),
            ),
        )

        val restored = LayoutMapper.toLayout(rows, homeArcSpecs())

        assertEquals(listOf("A1", "A2", "A3", "A4"), restored.arcs.map { it.arc.id })
        assertEquals(mapOf(0 to Slot.App(alpha)), restored.arcs.first().slots)
        assertEquals(setOf(alpha), restored.pins)
    }

    @Test
    fun `a slot beyond the arc capacity is kept and a negative index is dropped`() {
        // T36: a rotating ring (A2, five visible slots) may hold more items than it shows, so only a
        // negative index is invalid.
        val rows = LayoutRows(
            assignments = listOf(
                SlotAssignmentEntity("A2", 0, LayoutMapper.KindApp, appId = alpha.value),
                SlotAssignmentEntity("A2", 7, LayoutMapper.KindApp, appId = beta.value, pinned = true),
                SlotAssignmentEntity("A2", -1, LayoutMapper.KindApp, appId = alpha.value),
            ),
        )

        val restored = LayoutMapper.toLayout(rows, homeArcSpecs())

        assertEquals(mapOf(0 to Slot.App(alpha), 7 to Slot.App(beta)), restored.arcs[1].slots)
        assertEquals(setOf(beta), restored.pins)
    }

    @Test
    fun `a ring item that sits beyond its arc capacity round trips`() {
        val original = ParcLayout(
            version = LayoutVersion,
            arcs = homeArcSpecs().map { spec ->
                if (spec.id == "A2") {
                    ArcAssignment(spec, mapOf(5 to Slot.App(alpha), 7 to Slot.App(beta)))
                } else {
                    ArcAssignment(spec, emptyMap())
                }
            },
            pins = setOf(beta),
        )

        assertEquals(original, LayoutMapper.toLayout(LayoutMapper.toRows(original), homeArcSpecs()))
    }

    @Test
    fun `an unknown slot kind is left out and reported, never failing the whole layout`() {
        // OPUS6 (audit F5): throwing here crashed Parc at every start after a downgrade.
        val rows = LayoutRows(assignments = listOf(SlotAssignmentEntity("A1", 0, "widget", label = "weather")))

        val layout = LayoutMapper.toLayout(rows, homeArcSpecs())

        assertTrue(layout.arcs.all { it.slots.isEmpty() })
        assertEquals(rows.assignments, LayoutMapper.unreadableRows(rows))
    }

    @Test
    fun `a kind without its payload is left out and reported`() {
        val rows = LayoutRows(assignments = listOf(SlotAssignmentEntity("A1", 0, LayoutMapper.KindApp)))

        assertTrue(LayoutMapper.toLayout(rows, homeArcSpecs()).arcs.all { it.slots.isEmpty() })
        assertEquals(1, LayoutMapper.unreadableRows(rows).size)
    }

    @Test
    fun `a layout with no slots and no folders counts as never written`() {
        // Documented consequence: an imported document that is completely empty is treated as a
        // first run, so the next cold start seeds instead of showing nothing.
        assertTrue(LayoutRows().isEmpty())
        assertTrue(LayoutRows(hiddenAppIds = listOf(alpha.value)).isEmpty())
        assertTrue(LayoutMapper.toRows(emptyLayout()).isEmpty())
        assertTrue(!LayoutMapper.toRows(layoutWithEverySlotKind()).isEmpty())
    }
}
