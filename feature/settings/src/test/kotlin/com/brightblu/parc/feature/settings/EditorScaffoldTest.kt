package com.brightblu.parc.feature.settings

import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Metrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorScaffoldTest {
    @Test fun debounceUsesTrailingEdgeAndFlushesOnLeaving() = runTest {
        val writes = mutableListOf<String>()
        val writer = EditorWriter<String>(this) { writes += it }
        writer.changed("first")
        advanceTimeBy(Metrics.focusSaveDebounceMs - 1)
        writer.changed("latest")
        advanceTimeBy(Metrics.focusSaveDebounceMs - 1)
        runCurrent()
        assertTrue(writes.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("latest"), writes)
        writer.changed("leaving"); writer.flush(); runCurrent()
        assertEquals(listOf("latest", "leaving"), writes)
        advanceUntilIdle()
        assertEquals(2, writes.size)
    }
    @Test fun newerEditCannotCancelOrOvertakeAnInflightWrite() = runTest {
        val writes = mutableListOf<String>()
        val writer = EditorWriter<String>(this) { delay(1000); writes += it }
        writer.changed("first"); writer.flush(); runCurrent()
        writer.changed("second")
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), writes)
    }
    @Test fun removalCanDiscardPendingSkinSave() = runTest {
        val writes = mutableListOf<String>()
        val writer = EditorWriter<String>(this) { writes += it }
        writer.changed("removed"); writer.discard(); writer.flush()
        advanceUntilIdle(); assertTrue(writes.isEmpty())
    }
    @Test fun undoIsOneShotAndExpiresWithFakeClock() {
        var now = 0L
        val timing = EditorTiming { now }
        var restored = 0
        timing.offerUndo { restored++ }
        assertTrue(timing.undo()); assertFalse(timing.undo()); assertEquals(1, restored)
        timing.offerUndo { restored++ }
        now += Metrics.focusUndoMs
        assertFalse(timing.undo()); assertEquals(1, restored)
    }
    @Test fun firstUseSurvivesScreenRecreationAndIsSeparatePerEditor() {
        val stored = mutableSetOf<String>()
        fun open() = object : EditorHints {
            override fun seen(id: String) = id in stored
            override fun markSeen(id: String) { stored += id }
        }
        assertTrue(open().firstUse(EditorSpec.MainApps.name))
        assertFalse(open().firstUse(EditorSpec.MainApps.name))
        assertTrue(open().firstUse(EditorSpec.Folders.name))
        assertFalse(open().firstUse(EditorSpec.Folders.name))
    }
    private fun content(spec: EditorSpec) {
        assertTrue(spec.title.contains(":"))
        assertTrue(spec.instruction.contains("Tap", ignoreCase = true))
        assertTrue(spec.instruction.contains("automatically"))
        assertTrue(spec.empty.isNotBlank())
    }
    @Test fun mainAppsExplainsEditingAndEmptyNextStep() = content(EditorSpec.MainApps)
    @Test fun foldersExplainsEditingAndEmptyNextStep() = content(EditorSpec.Folders)
    @Test fun hiddenAppsExplainsEditingAndEmptyNextStep() = content(EditorSpec.HiddenApps)
    @Test fun rotationExplainsEditingAndEmptyNextStep() = content(EditorSpec.Rotation)
    @Test fun hubPagesExplainsEditingAndEmptyNextStep() = content(EditorSpec.HubPages)
    @Test fun agendaExplainsEditingAndEmptyNextStep() = content(EditorSpec.Agenda)
    @Test fun alertsExplainsEditingAndEmptyNextStep() = content(EditorSpec.Alerts)
    @Test fun skinExplainsEditingAndEmptyNextStep() = content(EditorSpec.Skin)
    @Test fun commsAppsExplainsEditingAndEmptyNextStep() = content(EditorSpec.CommsApps)
    @Test fun quickRepliesExplainsEditingAndEmptyNextStep() = content(EditorSpec.QuickReplies)
}
