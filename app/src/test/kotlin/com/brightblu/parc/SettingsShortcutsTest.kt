package com.brightblu.parc

import com.brightblu.parc.model.LayoutCounts
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.WallpaperStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T373: the six root tiles and the group rows read and write the registry's own rows. */
class SettingsShortcutsTest {
    private val base = SettingsSnapshot()

    @Test fun sixTilesInRootOrderEachPointAtARegistryRow() {
        assertEquals(listOf("Appearance", "WallpaperImage", "ArcsShown", "GlassEffect", "LeftHand", "LockLayout"),
            SettingsShortcut.root.map { it.rowId })
        assertEquals(SettingsShortcut.entries, SettingsShortcut.root)
        SettingsShortcut.root.forEach { assertNotNull(it.rowId, SettingsRegistry.byId[it.rowId]) }
    }

    @Test fun threeSwitchInPlaceAndThreeOpenTheirPage() {
        assertEquals(listOf(SettingsShortcut.Glass, SettingsShortcut.LeftHand, SettingsShortcut.LockLayout),
            SettingsShortcut.root.filter { it.inPlace })
        assertEquals(SettingsPage.AppearanceSkin, SettingsShortcut.Skin.def.page)
        assertEquals(SettingsPage.AppearanceWallpaper, SettingsShortcut.Wallpaper.def.page)
        assertEquals(SettingsPage.HomeArcs, SettingsShortcut.Arcs.def.page)
        assertNull(SettingsShortcut.Skin.toggledIndex(base))
        assertNull(SettingsShortcut.Wallpaper.toggledIndex(base))
        assertNull(SettingsShortcut.Arcs.toggledIndex(base))
    }

    @Test fun tilesReadTheValuesTheGroupsRead() {
        val s = base.copy(counts = LayoutCounts(arcs = 4), leftHand = true, layoutLocked = false,
            look = LookSettings(wallpaper = WallpaperStyle.Image))
        assertEquals("Midnight", SettingsShortcut.Skin.value(s))
        assertEquals("Picture", SettingsShortcut.Wallpaper.value(s))
        assertEquals("4 rings", SettingsShortcut.Arcs.value(s))
        assertEquals("On", SettingsShortcut.LeftHand.value(s))
        assertEquals("Off", SettingsShortcut.LockLayout.value(s))
        assertEquals("3 rings", SettingsShortcut.Arcs.value(s.copy(counts = LayoutCounts(arcs = 3))))
        assertEquals("1 ring", arcsLabel(1))
        assertEquals("Phone", wallpaperSourceLabel(WallpaperStyle.System))
        assertEquals("Skin colour", wallpaperSourceLabel(WallpaperStyle.Solid))
        assertEquals(if (s.glass) "On" else "Off", SettingsShortcut.Glass.value(s))
    }

    @Test fun aTapOnASwitchWritesThroughTheRegistryAndTheValueFollows() {
        listOf(SettingsShortcut.LeftHand, SettingsShortcut.LockLayout, SettingsShortcut.Glass).forEach { tile ->
            val before = tile.value(base)
            val index = tile.toggledIndex(base)!!
            val after = SettingsRegistry.set(base, tile.rowId, index)
            assertTrue(tile.name, tile.value(after) != before)
            // Tapping again restores the saved value exactly.
            assertEquals(tile.name, before, tile.value(SettingsRegistry.set(after, tile.rowId, tile.toggledIndex(after)!!)))
        }
        assertEquals(true, SettingsRegistry.set(base, "LeftHand", SettingsShortcut.LeftHand.toggledIndex(base)!!).leftHand)
        assertEquals(true, SettingsRegistry.set(base, "LockLayout", SettingsShortcut.LockLayout.toggledIndex(base)!!).layoutLocked)
        assertEquals(!base.glass, SettingsRegistry.set(base, "GlassEffect", SettingsShortcut.Glass.toggledIndex(base)!!).glass)
    }

    @Test fun eachGroupListsItsShortcuts() {
        assertEquals(listOf(SettingsShortcut.Skin, SettingsShortcut.Wallpaper, SettingsShortcut.Glass), SettingsShortcut.of(SettingsGroup.Appearance))
        assertEquals(listOf(SettingsShortcut.Arcs, SettingsShortcut.LeftHand, SettingsShortcut.LockLayout), SettingsShortcut.of(SettingsGroup.Home))
        assertEquals(emptyList<SettingsShortcut>(), SettingsShortcut.of(SettingsGroup.DataAbout))
        SettingsShortcut.root.forEach { assertEquals(it.name, it.def.page.group, it.group) }
    }

    @Test fun tilesFillRowsOfTheColumnCount() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6)), tileRows(listOf(1, 2, 3, 4, 5, 6), 3))
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)), tileRows(listOf(1, 2, 3, 4, 5, 6), 2))
        assertEquals(listOf(listOf(1, 2, 3), listOf(4)), tileRows(listOf(1, 2, 3, 4), 3))
    }

    @Test fun sixGroupsKeepEveryOldGroupNameSearchable() {
        assertEquals(6, SettingsGroup.entries.size)
        listOf("Data & about" to SettingsPage.DataBackup, "Notifications & Focus" to SettingsPage.Messages,
            "Home" to SettingsPage.HomeArcs).forEach { (old, page) ->
            assertTrue(old, SettingsRegistry.search(old).any { it.page == page })
        }
        assertTrue(SettingsRegistry.search("Hub pages").any { it.id == "HubSize" })
        listOf("Data & about", "Notifications & Focus", "Home").forEach { old ->
            assertTrue(old, SettingsSearchEntries.all.any { entry -> entry.key.startsWith("group:") && (entry.title == old || old in entry.keywords) })
        }
    }

    @Test fun aGroupWithOnePageOpensItAtOnceAndBackLeavesForTheRoot() {
        assertEquals(SettingsPage.HubPages, SettingsGroup.HubPages.directPage())
        assertEquals(SettingsScreen.Page(SettingsPage.HubPages), settingsScreenForGroup(SettingsGroup.HubPages))
        assertEquals(SettingsScreen.Root, settingsParent(SettingsPage.HubPages))
        assertNull(SettingsGroup.Home.directPage())
        assertEquals(SettingsScreen.Group(SettingsGroup.Home), settingsScreenForGroup(SettingsGroup.Home))
        assertEquals(SettingsScreen.Group(SettingsGroup.DataAbout), settingsParent(SettingsPage.Diagnostics))
    }

    @Test fun everyGroupTargetStillResolvesFromSearch() {
        SettingsGroup.entries.forEach { assertNotNull(it.name, SettingsSearchEntries.target("group:${it.name}")) }
    }

    @Test fun hubPagesLeadRowsComeBeforeThePageSwitches() {
        // T375: Show stats hub, Default hub size and Full-screen hub lead; the page switches and the rest follow.
        assertEquals(setOf("StatsVisible", "HubSize", "HubFullScreen"), HubPagesLayout.leadIds)
        val all = SettingsRegistry.on(SettingsPage.HubPages).map { it.id }.toSet()
        assertTrue(HubPagesLayout.leadIds.all { it in all })
        assertTrue(all - HubPagesLayout.leadIds == setOf("HubPages", "Agenda", "StatsAlerts"))
    }
}
