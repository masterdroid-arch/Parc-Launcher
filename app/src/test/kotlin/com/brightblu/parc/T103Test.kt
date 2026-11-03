package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*
import com.brightblu.parc.core.data.backup.BackupValueType
import com.brightblu.parc.feature.settings.ui.SettingKind
import org.junit.Assert.*
import org.junit.Test

class T103Test {
    private val geometry = RingLayout(384f, 853f)
    private val shortcut = Slot.Shortcut("com.example.notes", "new-note", 0, "New note")
    private val folder = FolderId("tools")
    private fun empty() = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it, emptyMap()) },
        folders = listOf(Folder(folder, "Tools")))
    private class Request(override val shortcut: Slot.Shortcut?, override val valid: Boolean = true,
        override val enabled: Boolean = true, private val accepted: Boolean = true) : PinRequest {
        var accepts = 0
        override fun accept(): Boolean { accepts++; return accepted }
    }
    @Test fun acceptOnlyAfterConfirmationAndOnlyOnce() {
        val request = Request(shortcut)
        val controller = PinController(request)
        var saved = empty()
        assertEquals(0, request.accepts)
        assertEquals(PinResult.Added, controller.confirm(saved, geometry.arcs, null) { saved = it })
        assertTrue(ShortcutPlacement.contains(saved, shortcut))
        assertEquals(1, request.accepts)
        assertEquals(PinResult.Unavailable, controller.confirm(saved, geometry.arcs, null) { fail() })
    }
    @Test fun declineDisabledInvalidWidgetAndFailedAcceptNeverSave() {
        val declined = Request(shortcut)
        val controller = PinController(declined)
        assertEquals(PinResult.Declined, controller.decline())
        assertEquals(PinResult.Unavailable, controller.confirm(empty(), geometry.arcs, null) { fail() })
        assertEquals(0, declined.accepts)
        listOf(Request(shortcut, valid = false), Request(shortcut, enabled = false),
            Request(null), Request(shortcut, accepted = false)).forEach { request ->
            assertEquals(PinResult.Unavailable, PinController(request).confirm(empty(), geometry.arcs, null) { fail() })
        }
    }
    @Test fun duplicatesAreScopedToPackageAndUserAndDoNotCreateExtraSlots() {
        val placed = ShortcutPlacement.add(empty(), shortcut, geometry.arcs, null)
        val duplicate = Request(shortcut.copy(label = "Changed"))
        assertEquals(PinResult.Duplicate, PinController(duplicate).confirm(placed, geometry.arcs, folder) { fail() })
        assertEquals(0, duplicate.accepts)
        assertFalse(ShortcutPlacement.contains(placed, shortcut.copy(userSerial = 10)))
        assertFalse(ShortcutPlacement.contains(placed, shortcut.copy(packageName = "com.example.other")))
    }
    @Test fun placementFillsFirstHoleWithoutOverwritingThenAppends() {
        val filled = empty().copy(arcs = geometry.arcs.map { arc -> ArcAssignment(arc,
            (0 until arc.slotCapacity).associateWith { Slot.Action(ActionKind.Settings) }) })
        val after = ShortcutPlacement.add(filled, shortcut, geometry.arcs, null)
        assertEquals(shortcut, after.arcs.last().slots[geometry.arcs.last().slotCapacity])
        assertEquals(filled.arcs.sumOf { it.slots.size } + 1, after.arcs.sumOf { it.slots.size })
        val hole = filled.copy(arcs = filled.arcs.mapIndexed { i, arc -> if (i == 0) arc.copy(slots = arc.slots - 1) else arc })
        assertEquals(shortcut, ShortcutPlacement.add(hole, shortcut, geometry.arcs, null).arcs.first().slots[1])
    }
    @Test fun folderDestinationPersistsAndCanBeRemovedWithoutTouchingOtherSlots() {
        val placed = ShortcutPlacement.add(empty(), shortcut, geometry.arcs, folder)
        val payload = placed.arcs.flatMap { it.slots.values }.single() as Slot.Shortcut
        assertEquals(folder, payload.folderId)
        assertEquals(placed, LayoutJson.decode(LayoutJson.encode(placed)))
        assertEquals(empty(), ShortcutPlacement.remove(placed, payload))
        assertEquals(empty(), ShortcutPlacement.add(empty(), shortcut, geometry.arcs, FolderId("missing")))
        val locked = empty().copy(folders = listOf(Folder(folder, "Tools").withLocked(true)))
        assertEquals(locked, ShortcutPlacement.add(locked, shortcut, geometry.arcs, folder))
    }
    @Test fun reducingCountsMigratesEveryPayloadOnceAndIncreasingDoesNotDelete() {
        val original = empty().copy(arcs = empty().arcs.map { arc -> when (arc.arc.id) {
            "A3" -> arc.copy(slots = mapOf(4 to Slot.Folder(folder)))
            "A4" -> arc.copy(slots = mapOf(0 to shortcut, 9 to Slot.Link("example:note", "Note")))
            else -> arc
        } })
        val reduced = LayoutCounts.migrate(original, 3)
        assertTrue(reduced.arcs.last().slots.isEmpty())
        assertEquals(original.arcs.flatMap { it.slots.values }.toSet(), reduced.arcs.flatMap { it.slots.values }.toSet())
        assertEquals(shortcut, reduced.arcs[2].slots[5])
        assertEquals(reduced, LayoutCounts.migrate(reduced, 3))
        assertEquals(reduced, LayoutCounts.migrate(reduced, 4))
    }
    @Test fun capsClampToCapacityOnAllTargetScreensWithSafePitch() {
        listOf(384f to 853f, 360f to 800f, 411f to 914f).forEach { (w, h) ->
            for (arcs in 3..4) for (cap in listOf(0, 1, 3, 4, 100)) {
                val auto = RingLayout(w, h, options = RingLayoutOptions(arcsShown = arcs))
                val actual = RingLayout(w, h, options = RingLayoutOptions(arcsShown = arcs, iconsPerArc = cap))
                assertEquals(arcs, actual.arcs.size)
                actual.arcs.forEachIndexed { i, arc ->
                    assertEquals(LayoutCounts(icons = cap).capacity(auto.arcs[i].slotCapacity), arc.slotCapacity)
                    assertTrue(arc.slotCapacity <= auto.arcs[i].slotCapacity)
                }
                assertTrue(actual.minimumPitchDp() >= Metrics.minSlotPitchDp - 0.01f)
                val ranked = List(50) { RingSlot(SlotPosition("pending", 0), Slot.App(AppId("app$it")), "App $it") }
                val folderScene = RingScenes.folder(actual, Folder(folder, "Tools"), ranked)
                assertEquals(50, folderScene.slots.size)
                assertEquals(50, folderScene.slots.map { it.slot }.distinct().size)
                assertEquals(50, RingScenes.allApps(actual, ranked).slots.size)
            }
        }
    }
    @Test fun shortcutSceneUsesFolderDestinationAndHonoursHiddenAndLockedPackages() {
        val placed = ShortcutPlacement.add(empty(), shortcut, geometry.arcs, folder)
        val app = AppEntry(AppId("com.example.notes/.Main@0"), "Notes", shortcut.packageName, false, 0)
        val home = LauncherScenes.build(geometry, placed, listOf(app), ShellState(), "") { null }
        assertTrue(home.slots.none { it.slot is Slot.Shortcut })
        val inside = LauncherScenes.build(geometry, placed, listOf(app), ShellState(layer = Layer.Folder, folderId = folder), "") { null }
        assertEquals(1, inside.slots.count { it.slot is Slot.Shortcut })
        assertFalse(inside.slots.single().enabled)
        val hidden = LauncherScenes.build(geometry, placed.copy(hidden = setOf(app.id)), listOf(app),
            ShellState(layer = Layer.Folder, folderId = folder), "") { null }
        assertTrue(hidden.slots.isEmpty())
        val locked = LauncherScenes.build(geometry, placed, listOf(app),
            ShellState(layer = Layer.Folder, folderId = folder), "", lockedFolders = setOf(folder)) { null }
        assertTrue(locked.slots.isEmpty())
        val removed = LauncherScenes.build(geometry, placed, emptyList(),
            ShellState(layer = Layer.Folder, folderId = folder), "") { null }
        assertEquals(1, removed.slots.size)
        assertFalse(removed.slots.single().enabled)
        val focusHidden = LauncherScenes.build(geometry, placed, emptyList(),
            ShellState(layer = Layer.Folder, folderId = folder), "", shortcutHiddenPackages = setOf(shortcut.packageName)) { null }
        assertTrue(focusHidden.slots.isEmpty())
    }

}
