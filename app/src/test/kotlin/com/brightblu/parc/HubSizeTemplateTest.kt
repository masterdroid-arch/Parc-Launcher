package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test

class HubSizeTemplateTest {
    @Test fun missingUnknownAndRetiredFlagsMigrateToStandard() {
        for (raw in listOf(null, "", "true", "false", "expanded", "future")) assertEquals(HubSize.Standard, HubSize.decode(raw))
        assertEquals(HubSize.Standard, HubConfig.decode("system:true:battery:").size)
        HubSize.entries.forEach { assertEquals(it, HubSize.decode(it.id)) }
        assertEquals(HubSize.Compact, HubSize.Tall.next())
    }
    @Test fun hubSizeHasBoundedBackupKeyAndLegacyFlagIsKept() {
        assertEquals(16, BackupSpecs.hub.key("hub_size")!!.maxLength)
        assertNotNull(BackupSpecs.settings.key("stats_expanded"))
    }
    @Test fun pageRowsDropFromBottomAsRoomAndTextScaleChange() {
        val tiles = listOf("main", "a", "b", "history")
        assertEquals(listOf("main"), HubPanelLayout.visibleTiles(tiles, HubSize.Compact, 80f, 1.3f))
        val normal = HubPanelLayout.visibleTiles(tiles, HubSize.Standard, 200f, 1f)
        val large = HubPanelLayout.visibleTiles(tiles, HubSize.Standard, 200f, 1.3f)
        assertTrue(normal.containsAll(large))
        assertEquals(tiles, HubPanelLayout.visibleTiles(tiles, HubSize.Tall, 240f, 1.3f))
        assertTrue(HubPanelLayout.heightDp(HubSize.Compact, 400f) < HubPanelLayout.heightDp(HubSize.Standard, 400f))
    }
    @Test fun everyPageHasSummaryActionAndUsefulEmptyState() {
        assertEquals(HubCatalog.pages.toSet(), hubTemplates.keys)
        hubTemplates.values.forEach { assertTrue(it.summary.isNotBlank()); assertTrue(it.action.isNotBlank()); assertTrue(it.empty.length > 15) }
        assertEquals("Hub size", settingsRowSpec(SettingsRowId.HubSize).title)
    }
    @Test fun tallProjectionKeepsSavedSlotsAndMapsMenuActionsBack() {
        val a3 = ArcSpec("A3", 2, 240f, 180f, 270f, 8, Layer.Home)
        val a4 = ArcSpec("A4", 3, 300f, 180f, 270f, 10, Layer.Home)
        val outer = Slot.App(AppId("outer/app"))
        val layout = ParcLayout(arcs = listOf(ArcAssignment(a3, mapOf(2 to Slot.Action(ActionKind.Settings))),
            ArcAssignment(a4, mapOf(4 to outer))))
        val shown = LayoutCounts.migrate(layout, 3)
        assertEquals(outer, shown.arcs.first().slots[3])
        assertEquals(SlotPosition("A4", 4), hubSourcePosition(layout, SlotPosition("A3", 3)))
        assertEquals(SlotPosition("A3", 2), hubSourcePosition(layout, SlotPosition("A3", 2)))
        assertEquals(outer, layout.arcs.last().slots[4])
    }
    @Test fun customSkinsDefaultToSoftAndTerminalIsExplicit() {
        assertFalse(SkinStyle().terminalStats)
        assertTrue(SkinStyle(terminalStats = true).terminalStats)
        assertTrue(Metrics.hubValueSp > Metrics.hubLineSp)
        assertTrue(Metrics.hubLineSp > Skin.Midnight.typography.caption.sizeSp)
    }
}
