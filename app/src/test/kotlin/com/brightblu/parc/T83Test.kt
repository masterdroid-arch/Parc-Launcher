package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * T83: the All-apps scene must always be built from the current state, even when a single icon or
 * an odd projection throws, and the phone must be able to report what it built.
 */
class T83Test {
    private val geometry = RingLayout(384f, 853f)
    private val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun apps(count: Int = 150): List<AppEntry> = List(count) { i ->
        AppEntry(AppId("fixture/phone/$i"),
            "${letters[i % 26]}app ${i.toString().padStart(3, '0')}", "pkg.fixture.phone.$i", false, i.toLong())
    }

    /** A populated seed shape: A1 anchors, a full A2, seven A3 folders, a sparse A4. */
    private fun seededLayout(): ParcLayout {
        val ids = apps().map { it.id }
        val folders = listOf(
            Folder(FolderId("ai"), "AI", appIds = ids.take(5)),
            Folder(FolderId("google"), "Google", appIds = ids.drop(5).take(12)),
            Folder(FolderId("media"), "Media", appIds = ids.drop(17).take(10)),
            Folder(FolderId("money"), "Money", appIds = ids.drop(27).take(6)),
            Folder(FolderId("extra"), "Extra", appIds = ids.drop(33).take(7)),
            Folder(FolderId("social"), "Social", appIds = ids.drop(40).take(9)),
            Folder(FolderId("tools"), "Tools", appIds = ids.drop(49).take(11)),
            Folder(FolderId("life"), "Life", appIds = ids.drop(60).take(8)),
            Folder(FolderId("new"), "New", appIds = ids.drop(68).take(4)),
        )
        val arcs = geometry.arcs
        return ParcLayout(arcs = listOf(
            ArcAssignment(arcs[0], (0 until 4).associateWith { Slot.App(ids[it]) }),
            ArcAssignment(arcs[1], (0 until 8).associateWith { Slot.App(ids[4 + it]) }),
            ArcAssignment(arcs[2], (0 until 7).associateWith { Slot.Folder(folders[it].id) }),
            ArcAssignment(arcs[3], mapOf(
                0 to Slot.Action(ActionKind.Settings),
                1 to Slot.Folder(FolderId("life")),
                3 to Slot.Link("parc://fixture", "L"),
            )),
        ), folders = folders)
    }

    @After fun clearDiagnostics() = SceneDiagnostics.clear()

    @Test fun allAppsWithSeededLayoutIsAlphabeticalAndKeepsEveryApp() {
        val apps = apps()
        val state = LauncherScenes.build(geometry, seededLayout(), apps,
            ShellState(layer = Layer.AllApps), "09:41") { null }
        assertEquals(Layer.AllApps, state.layer)
        assertEquals(150, state.slots.size)
        assertEquals(150, state.slots.map { it.slot }.distinct().size)
        // Every slot is an app (no folder or action survives into All-apps).
        assertTrue(state.slots.all { it.slot is Slot.App })
        val labels = state.slots.map { it.label }
        assertEquals(labels.sortedWith(compareBy({ it.lowercase() }, { it })), labels)
        // Outer-to-inner traversal starts on A4 and keeps the A-Z order.
        assertEquals("all/A4", state.slots.first().position.arcId)
        assertTrue(state.arcs.all { it.layer == Layer.AllApps })
    }

    @Test fun oneBrokenIconNeverFailsTheAllAppsScene() {
        val apps = apps()
        val state = LauncherScenes.build(geometry, seededLayout(), apps,
            ShellState(layer = Layer.AllApps), "09:41") { id ->
            if (id.value.endsWith("7")) throw IllegalStateException("broken icon") else null
        }
        assertEquals(Layer.AllApps, state.layer)
        assertEquals(150, state.slots.size)
        assertTrue(SceneDiagnostics.lastError.contains("Icon failed for"))
    }

    @Test fun aFailedProjectionStillReturnsTheRequestedLayer() {
        val layout = seededLayout()
        val state = LauncherScenes.build(geometry, layout, apps(),
            ShellState(layer = Layer.Folder, folderId = FolderId("social")), "09:41",
            rank = { throw IllegalStateException("rank failed") }) { null }
        assertEquals(Layer.Folder, state.layer)
        assertTrue(state.slots.isEmpty())
        assertTrue(state.arcs.all { it.layer == Layer.Folder })
        assertTrue(SceneDiagnostics.lastError.contains("rank failed"))
    }

    @Test fun allAppsWithNoAppsIsAnEmptyAllAppsScene() {
        val state = LauncherScenes.build(geometry, ParcLayout(), emptyList(),
            ShellState(layer = Layer.AllApps), "") { null }
        assertEquals(Layer.AllApps, state.layer)
        assertTrue(state.slots.isEmpty())
        // OPUS1: All apps is one ring of every app, so even an empty view has exactly one arc.
        assertEquals(1, state.arcs.size)
    }

    @Test fun diagnosticsRoundTripAndFormatForSettings() {
        val value = SceneDiagnostic("AllApps", 150, "4,5,7,7", "AllApps: boom\nnext", 1_700_000_000_000L)
        val decoded = SceneDiagnostic.decode(value.encode())
        assertEquals("AllApps", decoded.layer)
        assertEquals(150, decoded.slots)
        assertEquals("4,5,7,7", decoded.capacities)
        assertEquals(1_700_000_000_000L, decoded.updatedAtMillis)
        assertEquals("AllApps: boom next", decoded.error)
        val lines = sceneDiagnosticLines(decoded)
        assertEquals("Layer: AllApps", lines[0])
        assertEquals("Scene slots: 150", lines[1])
        assertEquals("Arc capacities: 4,5,7,7", lines[2])
        assertEquals("Last scene error: AllApps: boom next", lines[3])
        assertEquals(SceneDiagnostic(), SceneDiagnostic.decode(""))
        assertEquals(SceneDiagnostic(), SceneDiagnostic.decode("9|AllApps|1|x|0|"))
        assertEquals("none", sceneDiagnosticLines(SceneDiagnostic()).last().substringAfter(": "))
        assertTrue(SceneDiagnostic.decode("1|" + "L".repeat(100) + "|1|1|0|").layer.length <= 16)
    }

    @Test fun diagnosticsSettingsRowIsPresent() {
        val rows = settingsRootRows("Midnight", false, true, false, true)
        assertTrue(rows.any { it.id == SettingsRowId.Diagnostics })
        assertEquals("Scene, capacities, error", rows.first { it.id == SettingsRowId.Diagnostics }.detail)
    }
}
