package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupValueType
import com.brightblu.parc.core.data.backup.BackupValues
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class T109Test {
    @Test fun everyClockStyleUsesAValidWeightIncludingInheritedSkinWeights() {
        val skins = listOf(Skin.Midnight, FieldAtlas.Dark, FieldAtlas.Light, Phosphor.Skin,
            Skin.Midnight.copy(id = "wallpaper", name = "Wallpaper"))
        for (skin in skins) for (style in ClockStyle.entries) {
            assertTrue(hubClockWeight(style, skin) in 1..1000)
            if (style == ClockStyle.Bold) assertEquals(700, hubClockWeight(style, skin))
        }
        assertEquals(Skin.Midnight.typography.clock.weight, hubClockWeight(ClockStyle.Thin, Skin.Midnight))
    }

    @Test fun oldConfigurationGainsClockFirstWithoutLosingChoices() {
        val config = HubConfig.decode("system:true:ram,battery:battery;power:false:charging:;sound:true:alarm,media:media;comms:false::")
        assertEquals("clock", config.pages.first())
        assertTrue("power" !in config.pages && "comms" !in config.pages)
        assertTrue("battery" in config.hiddenTiles.getValue("system"))
        assertEquals("ram", config.tiles("system").first())
        assertEquals("sound", restoredHubPage("sound", config))
        assertEquals(config.encode(), HubConfig.decode(config.encode()).encode())
    }
    @Test fun clockCanBeHiddenAndPagesReorderedAcrossSaveAndRestore() {
        val config = HubConfig().movePage("clock", 99).movePage("media", -99).showPage("clock", false)
        val restored = HubConfig.decode(config.encode())
        assertEquals("media", restored.pages.first())
        assertEquals("clock", restored.orderedPages.last())
        assertFalse("clock" in restored.pages)
        assertEquals(config.encode(), restored.encode())
        assertEquals("clock", restored.showPage("clock", true).pages.last())
    }
    @Test fun panelUsesInsetAndEntireSafeBandAtEveryReferenceSize() {
        for ((w, h) in listOf(384f to 853f, 360f to 800f, 411f to 914f)) {
            val layout = RingLayout(w, h)
            val scene = RingScenes.home(layout, layout.arcs.flatMap { arc ->
                (0 until arc.slotCapacity).map { RingSlot(SlotPosition(arc.id, it), Slot.Folder(FolderId("f${arc.id}$it")), "Folder") }
            }, "")
            for (inset in listOf(0f, 24f, 32f, 56f)) {
                val top = HubPanelLayout.topDp(inset)
                val bottom = RingHudBounds.bottomDp(scene, 24f)
                assertEquals(inset + 8f, top, 0f)
                assertTrue(top < bottom)
                assertEquals(bottom, top + RingHudBounds.heightDp(top, bottom), .001f)
                assertTrue(bottom < h)
            }
        }
        assertEquals(8f, HubPanelLayout.topDp(-24f), 0f)
        assertEquals(0f, RingHudBounds.heightDp(60f, 40f), 0f)
    }
    @Test fun flexibleTilesGrowWithinLimitsAndOverflowScrolls() {
        assertEquals(94f, HubPanelLayout.tileHeightDp(400f, 4), 0f)
        assertEquals(Metrics.hudCellHeightDp, HubPanelLayout.tileHeightDp(100f, 4), 0f)
        assertEquals(Metrics.hubTileMaxHeightDp, HubPanelLayout.tileHeightDp(600f, 1), 0f)
        assertEquals(Metrics.hudCellHeightDp, HubPanelLayout.tileHeightDp(0f, 0), 0f)
        for (rows in 1..10) for (available in listOf(0f, 180f, 400f, 600f)) {
            val height = HubPanelLayout.tileHeightDp(available, rows)
            assertTrue(height in Metrics.hudCellHeightDp..Metrics.hubTileMaxHeightDp)
            if (height > Metrics.hudCellHeightDp) assertTrue(height * rows + Metrics.hudCellGapDp * (rows - 1) <= available + .001f)
        }
    }
    @Test fun headerTimeIsAnOffByDefaultStatsToggle() {
        val row = settingsRootRows("Midnight", false, true, true, true).single { it.id == SettingsRowId.HeaderTime }
        assertEquals(SettingKind.Toggle, row.kind)
        assertEquals(SettingsSectionId.Stats, row.section)
        assertEquals(false, row.checked)
        assertEquals("Time in the page header", row.label)
    }
    @Test fun newHeaderSettingAndPageOrderTravelThroughAppBackup() = runTest {
        assertEquals(BackupSpecs.hub, AppBackup.HubSpec)
        val key = AppBackup.HubSpec.key("header_time")!!
        assertEquals(BackupValueType.Bool, key.type)
        assertTrue(BackupValues.matches(key, false))
        assertFalse(BackupValues.matches(key, "true"))
        val config = HubConfig().movePage("clock", 4).showPage("sound", false)
        val source = FakeSharedPreferences().apply { data["header_time"] = true; data["config_v1"] = config.encode(); data["last_page"] = "media" }
        val values = SharedPreferencesStoreAccess(source).read(AppBackup.HubSpec)
        val target = FakeSharedPreferences()
        SharedPreferencesStoreAccess(target).write(AppBackup.HubSpec, values)
        assertEquals(values, SharedPreferencesStoreAccess(target).read(AppBackup.HubSpec))
        assertEquals(config.pages, HubConfig.decode(target.data["config_v1"] as String).pages)
        assertEquals("media", target.data["last_page"])
    }
}
