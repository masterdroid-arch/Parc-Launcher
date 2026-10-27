package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*
import org.junit.Assert.*
import org.junit.Test
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class RingEditTest {
    private val geometry = RingLayout(384f, 853f)
    private val a = AppId("test/A@0")
    private val b = AppId("test/B@0")
    private fun position(index: Int) = SlotPosition(geometry.arcs[0].id, index)
    private fun layout(vararg slots: Pair<Int, Slot>) = ParcLayout(arcs = geometry.arcs.map {
        ArcAssignment(it, if (it.index == 0) mapOf(*slots) else emptyMap())
    })
    private fun scene(layout: ParcLayout) = RingScenes.home(geometry, layout.arcs.flatMap { arc ->
        arc.slots.map { (i, slot) -> RingSlot(SlotPosition(arc.arc.id, i), slot, "Test") }
    })

    @Test fun emptyHomeTargetAllowedAndPinnedTargetRejected() {
        val layout = layout(0 to Slot.App(a), 1 to Slot.App(b)).copy(pins = setOf(b))
        assertTrue(editDropAllowed(layout, scene(layout), position(0), position(2)))
        assertFalse(editDropAllowed(layout, scene(layout), position(0), position(1)))
        assertFalse(editDropAllowed(layout, scene(layout), position(0), position(0)))
        assertFalse(editDropAllowed(layout, scene(layout), position(0), position(99)))
    }

    @Test fun syntheticOrActionSourceCannotMove() {
        val layout = layout(0 to Slot.Action(ActionKind.Settings))
        assertFalse(editDropAllowed(layout, scene(layout), position(0), position(1)))
        val synthetic = RingScenes.home(geometry, listOf(RingSlot(position(0), Slot.Folder(FolderId("new")), "New")))
        assertFalse(editDropAllowed(layout, synthetic, position(0), position(1)))
    }

    @Test fun folderUsesT11ReorderRules() {
        val folder = Folder(FolderId("social"), "Social", appIds = listOf(a, b))
        val layout = layout().copy(folders = listOf(folder), pins = setOf(b))
        val scene = RingScenes.folder(geometry, folder, listOf(a, b).map {
            RingSlot(position(0), Slot.App(it), "App")
        })
        assertFalse(editDropAllowed(layout, scene, scene.slots[0].position, scene.slots[1].position))
        assertTrue(editDropAllowed(layout, scene, scene.slots[0].position, SlotPosition(scene.arcs[1].id, 0)))
    }

    @Test fun linkSlotSurvivesSerializationWithoutChangingTheAppsAroundIt() {
        val original = layout(0 to Slot.App(a), 1 to Slot.Link("https://example.com/help", "Help"))
        val reloaded = LayoutJson.decode(LayoutJson.encode(original))
        assertEquals(Slot.App(a), reloaded.arcs[0].slots[0])
        assertEquals(Slot.Link("https://example.com/help", "Help"), reloaded.arcs[0].slots[1])
    }

    @Test fun folderDropPersistsCompleteOrderInOneWriteAndBlocksDoubleDrop() = runTest {
        val folder = Folder(FolderId("social"), "Social", appIds = listOf(a, b))
        val original = layout().copy(folders = listOf(folder))
        val storage = FakeLayoutRepository(original)
        var imports = 0
        val repository = object : LayoutRepository by storage {
            override suspend fun importLayout(source: String) { imports++; storage.importLayout(source) }
        }
        val edit = RingEditSession(repository, this)
        val scene = RingScenes.folder(geometry, folder, listOf(a, b).map { RingSlot(position(0), Slot.App(it), "App") })
        edit.scene = scene
        edit.enter()
        val from = scene.slots[0].position
        val to = scene.slots[1].position
        edit.drop(from, to)
        assertTrue(edit.busy)
        edit.drop(from, to)
        runCurrent()
        assertFalse(edit.busy)
        assertEquals(1, imports)
        assertEquals(listOf(b, a), repository.layout.value.folders.single().appIds)
        edit.close()
        assertFalse(edit.enabled)
    }

    @Test fun rejectedDropWritesNothing() = runTest {
        val original = layout(0 to Slot.App(a), 1 to Slot.App(b)).copy(pins = setOf(b))
        val repository = FakeLayoutRepository(original)
        val edit = RingEditSession(repository, this)
        edit.scene = scene(original)
        edit.drop(position(0), position(1))
        runCurrent()
        assertEquals(original, repository.layout.value)
    }

}
