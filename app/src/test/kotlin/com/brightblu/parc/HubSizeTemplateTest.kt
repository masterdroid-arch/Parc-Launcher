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
    @Test fun customSkinsDefaultToSoftAndTerminalIsExplicit() {
        assertFalse(SkinStyle().terminalStats)
        assertTrue(SkinStyle(terminalStats = true).terminalStats)
        assertTrue(Metrics.hubValueSp > Metrics.hubLineSp)
        assertTrue(Metrics.hubLineSp > Skin.Midnight.typography.caption.sizeSp)
    }
}
