package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingScenes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** T244: drag an app into a folder, out onto a Home arc, and reorder inside an open folder. */
@OptIn(ExperimentalCoroutinesApi::class)
class T244Test {
    private val geometry = RingLayout(384f, 853f)
    private val social = FolderId("social")
    private fun id(name: String) = AppId("test/$name@0")
    private val a = id("A")
    private val b = id("B")
    private val c = id("C")
    private val d = id("D")
    private val e = id("E")
    private val f = id("F")
    private val g = id("G")
    private val h = id("H")
    private fun at(arcId: String, index: Int) = SlotPosition(arcId, index)
    private fun arc(id: String) = geometry.arcs.first { it.id == id }

    private fun folderScene(folder: Folder, apps: List<AppId>): RingState =
        RingScenes.folder(geometry, folder, apps.mapIndexed { index, app ->
            RingSlot(SlotPosition("pending", index), Slot.App(app), app.value)
        })

    @Test fun folderRanksFollowTheLiveArcCapacities() {
        val apps = listOf(a, b, c, d, e, f, g, h)
        val folder = Folder(social, "Social", appIds = apps)
        val scene = folderScene(folder, apps)
        assertEquals(3, scene.arcs.first().slotCapacity)
        assertEquals(5, scene.arcs.last().slotCapacity)
        assertEquals(0, folderIndex(scene, at(scene.arcs[0].id, 0)))
        assertEquals(2, folderIndex(scene, at(scene.arcs[0].id, 2)))
        assertEquals(3, folderIndex(scene, at(scene.arcs[1].id, 0)))
        assertEquals(7, folderIndex(scene, at(scene.arcs[1].id, 4)))
        assertEquals(-1, folderIndex(scene, at(scene.arcs[1].id, 5)))
        assertEquals(-1, folderIndex(scene, at("missing", 0)))
    }

    @Test fun theOuterFolderArcCanBeReorderedNow() = runTest {
        val apps = listOf(a, b, c, d, e, f, g, h)
        val folder = Folder(social, "Social", appIds = apps)
        val original = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it, emptyMap()) },
            folders = listOf(folder))
        val repository = FakeLayoutRepository(original)
        val edit = RingEditSession(repository, this)
        val scene = folderScene(folder, apps)
        edit.scene = scene
        edit.enter()
        // D is the first item on the outer arc (rank 3); E is the next one.
        edit.drop(scene.slots[3].position, scene.slots[4].position)
        runCurrent()
        assertEquals(listOf(a, b, c, e, d, f, g, h), repository.layout.value.folders.single().appIds)
        assertFalse(edit.busy)
    }

    @Test fun droppingAnAppOnAFolderPutsItInWithoutMovingTheHomeSlot() = runTest {
        val home = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it,
            if (it.id == "A1") mapOf(0 to Slot.App(a))
            else if (it.id == "A3") mapOf(0 to Slot.Folder(social)) else emptyMap()) },
            folders = listOf(Folder(social, "Social")))
        val entry = AppEntry(a, "App A", "test.a", false, 0)
        val scene = LauncherScenes.build(geometry, home, listOf(entry), ShellState(), "") { null }
        val from = at("A1", 0)
        val to = at("A3", 0)
        assertTrue(editDropAllowed(home, scene, from, to))
        val repository = FakeLayoutRepository(home)
        val edit = RingEditSession(repository, this)
        edit.scene = scene
        edit.enter()
        edit.drop(from, to)
        runCurrent()
        assertEquals(listOf(a), repository.layout.value.folders.single().appIds)
        assertEquals(Slot.App(a), repository.layout.value.arcs.first { it.arc.id == "A1" }.slots[0])
    }

    @Test fun anAppWithNoHomeSlotLandsInTheFirstFreeSpotOnTheTargetArc() {
        val layout = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it,
            if (it.id == "A1") mapOf(0 to Slot.App(b)) else emptyMap()) },
            folders = listOf(Folder(social, "Social", appIds = listOf(a, b))))
        val next = requireNotNull(FolderDrop.exit(layout, social, a, "A1"))
        assertEquals(Slot.App(a), next.arcs.first { it.arc.id == "A1" }.slots[1])
        assertEquals(listOf(b), next.folders.single().appIds)
    }

    @Test fun anAppAlreadyOnAnotherArcMovesToTheTargetArc() {
        val layout = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it,
            if (it.id == "A1") mapOf(0 to Slot.App(a)) else emptyMap()) },
            folders = listOf(Folder(social, "Social", appIds = listOf(a))))
        val next = requireNotNull(FolderDrop.exit(layout, social, a, "A2"))
        assertFalse(next.arcs.first { it.arc.id == "A1" }.slots.containsKey(0))
        assertEquals(Slot.App(a), next.arcs.first { it.arc.id == "A2" }.slots[0])
        assertTrue(next.folders.single().appIds.isEmpty())
    }

    @Test fun anAppAlreadyOnTheTargetArcKeepsItsOwnSlot() {
        val layout = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it,
            if (it.id == "A1") mapOf(1 to Slot.App(a)) else emptyMap()) },
            folders = listOf(Folder(social, "Social", appIds = listOf(a))))
        assertEquals(at("A1", 1), FolderDrop.target(layout, a, "A1"))
        val next = requireNotNull(FolderDrop.exit(layout, social, a, "A1"))
        assertEquals(Slot.App(a), next.arcs.first { it.arc.id == "A1" }.slots[1])
        assertTrue(next.folders.single().appIds.isEmpty())
    }

    @Test fun aFullArcOffersNoDropAndAnUnknownIdChangesNothing() {
        val full = (0 until arc("A1").slotCapacity).associateWith { Slot.App(id("full$it")) }
        val layout = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it,
            if (it.id == "A1") full else emptyMap()) },
            folders = listOf(Folder(social, "Social", appIds = listOf(a))))
        assertNull(FolderDrop.target(layout, a, "A1"))
        assertFalse(FolderDrop.canExit(layout, social, a, "A1"))
        assertNull(FolderDrop.exit(layout, social, a, "A1"))
        assertNull(FolderDrop.exit(layout, social, a, "A9"))
        assertNull(FolderDrop.exit(layout, FolderId("other"), a, "A2"))
        assertNull(FolderDrop.exit(layout, social, b, "A2"))
    }

    @Test fun theRingAndHostUseTheSameDragOutAndRankRules() {
        val ring = File("../feature/ring/src/main/kotlin/com/brightblu/parc/ring/CanvasRingRenderer.kt").readText()
        assertTrue(ring.contains("private class DropGuide"))
        assertTrue(ring.contains("session.dropGuide?.target(x, y, source.id)"))
        assertTrue(ring.contains("guide.draw(canvas, session.dropPosition, session.dropAllowed)"))
        val edit = File("src/main/kotlin/com/brightblu/parc/RingEditSession.kt").readText()
        assertTrue(edit.contains("FolderDrop.exit(snapshot"))
        assertTrue(edit.contains("FolderDrop.homeArc(layout, to.arcId)"))
        assertFalse(edit.contains("when (arc) { 0 -> 0; 1 -> 4"))
    }
}
