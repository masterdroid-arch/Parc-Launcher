package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T220: the five-group Settings IA, pinned as one data table. A future move has to update this table
 * and the registry together; a lost, duplicated or renamed row fails here.
 */
class SettingsIaTest {
    private data class Row(val group: String, val page: String, val card: String)

    /** id → group, sub-page, card. The 72 rows of REVIEW-PLAN §3.3, with T219/T220, T245 and T260 new rows. */
    private val expected: Map<String, Row> = mapOf(
        // Home
        "ArcsShown" to Row("Home", "Arcs", "Arcs"),
        "IconsPerArc" to Row("Home", "Arcs", "Arcs"),
        "ArcMaximums" to Row("Home", "Arcs", "Maximum apps"),
        "ArcLayout" to Row("Home", "Arcs", "Arcs"),
        "LeftHand" to Row("Home", "Arcs", "Arcs"),
        "LockLayout" to Row("Home", "Arcs", "Arcs"),
        "Rotation" to Row("Home", "Arcs", "Arcs"),
        "MainApps" to Row("Home", "Apps & folders", "Apps"),
        "Folders" to Row("Home", "Apps & folders", "Folders"),
        "MarkWorkApps" to Row("Home", "Apps & folders", "Work apps"),
        "HiddenApps" to Row("Home", "Hidden & locked", "Hidden apps"),
        "HiddenFolders" to Row("Home", "Hidden & locked", "Hidden folders"),
        "LockedFolders" to Row("Home", "Hidden & locked", "Locked"),
        // Appearance > Skin
        "Appearance" to Row("Appearance", "Skin", "Skin"),
        "SkinStudio" to Row("Appearance", "Skin", "Skin"),
        // Appearance > Wallpaper
        "WallpaperSkinMode" to Row("Appearance", "Wallpaper", "Wallpaper skin"),
        "WallpaperSkinLook" to Row("Appearance", "Wallpaper", "Wallpaper skin"),
        "WallpaperImage" to Row("Appearance", "Wallpaper", "Wallpaper"),
        "WallpaperDim" to Row("Appearance", "Wallpaper", "Wallpaper"),
        "WallpaperReadability" to Row("Appearance", "Wallpaper", "Wallpaper"),
        // Appearance > Icons & clock
        "IconShape" to Row("Appearance", "Icons & clock", "Icons"),
        "IconSize" to Row("Appearance", "Icons & clock", "Icons"),
        "IconPack" to Row("Appearance", "Icons & clock", "Icons"),
        "ClockVisible" to Row("Appearance", "Icons & clock", "Clock"),
        "ClockStyle" to Row("Appearance", "Icons & clock", "Clock"),
        "ShowDate" to Row("Appearance", "Icons & clock", "Clock"),
        "ShowAlarm" to Row("Appearance", "Icons & clock", "Clock"),
        // Appearance > Hubs
        "GlassEffect" to Row("Appearance", "Hubs", "Both hubs"),
        "GlassRefraction" to Row("Appearance", "Hubs", "Both hubs"),
        "ArcHubColour" to Row("Appearance", "Hubs", "Arc hub"),
        "ArcHubOpacity" to Row("Appearance", "Hubs", "Arc hub"),
        "StatsHubColour" to Row("Appearance", "Hubs", "Stats hub"),
        "StatsHubOpacity" to Row("Appearance", "Hubs", "Stats hub"),
        "StatsVisible" to Row("Appearance", "Hubs", "Stats hub"),
        "HubPages" to Row("Appearance", "Hubs", "Pages & alerts"),
        // Appearance > Hubs > Pages & alerts
        "HubSize" to Row("Appearance", "Pages & alerts", "Stats hub"),
        "HubFullScreen" to Row("Appearance", "Pages & alerts", "Stats hub"),
        "Agenda" to Row("Appearance", "Pages & alerts", "Pages"),
        "StatsAlerts" to Row("Appearance", "Pages & alerts", "Alerts"),
        // Gestures & search > Gestures
        "SwipeUp" to Row("Gestures & search", "Gestures", "Gestures"),
        "HubLongPress" to Row("Gestures & search", "Gestures", "Gestures"),
        "ShowHintsAgain" to Row("Gestures & search", "Gestures", "Gestures"),
        "HubDoubleTap" to Row("Gestures & search", "Gestures", "Gestures"),
        "Joystick" to Row("Gestures & search", "Gestures", "Gestures"),
        // Gestures & search > Feel
        "Haptics" to Row("Gestures & search", "Feel", "Feel"),
        "HapticPreset" to Row("Gestures & search", "Feel", "Feel"),
        "SoundTheme" to Row("Gestures & search", "Feel", "Feel"),
        "SoundVolume" to Row("Gestures & search", "Feel", "Feel"),
        "SpinLimit" to Row("Gestures & search", "Feel", "Feel"),
        "Bounce" to Row("Gestures & search", "Feel", "Feel"),
        "Magnify" to Row("Gestures & search", "Feel", "Feel"),
        // Gestures & search > Search
        "Search" to Row("Gestures & search", "Search", "Search"),
        "SearchPhoneSettings" to Row("Gestures & search", "Search", "Search"),
        "SearchFiles" to Row("Gestures & search", "Search", "Search"),
        "SearchEvents" to Row("Gestures & search", "Search", "Search"),
        // Notifications & Focus > Notifications
        "NotificationAccess" to Row("Notifications & Focus", "Notifications", "Access"),
        "NotificationBadges" to Row("Notifications & Focus", "Notifications", "Badges"),
        "BadgesInAllApps" to Row("Notifications & Focus", "Notifications", "Badges"),
        "MenuNotifications" to Row("Notifications & Focus", "Notifications", "Menus"),
        "MenuNotificationText" to Row("Notifications & Focus", "Notifications", "Menus"),
        // Notifications & Focus > Messages
        "Comms" to Row("Notifications & Focus", "Messages", "Messages"),
        "CommsEnabled" to Row("Notifications & Focus", "Messages", "Messages"),
        "CommsRecordHistory" to Row("Notifications & Focus", "Messages", "History"),
        "CommsApps" to Row("Notifications & Focus", "Messages", "Apps"),
        "QuickReplies" to Row("Notifications & Focus", "Messages", "Replies"),
        "CommsClearHistory" to Row("Notifications & Focus", "Messages", "History"),
        // Notifications & Focus > Focus
        "Focus" to Row("Notifications & Focus", "Focus", "Focus"),
        // Data & about > Backup
        "Backup" to Row("Data & about", "Backup", "Backup"),
        "WallpaperImageBackup" to Row("Data & about", "Backup", "Backup"),
        // Data & about > This build
        "About" to Row("Data & about", "This build", "About"),
        "WhatsNew" to Row("Data & about", "This build", "About"),
        "VersionHistory" to Row("Data & about", "This build", "About"),
        // Data & about > Permissions
        "Permissions" to Row("Data & about", "Permissions", "Access"),
        "RerunOnboarding" to Row("Data & about", "Permissions", "Access"),
        "AndroidSettings" to Row("Data & about", "Permissions", "Access"),
        // Data & about > Help
        "Diagnostics" to Row("Data & about", "Help", "Help"),
    )

    @Test fun theExactRowMapIsImplementedOnce() {
        assertEquals(76, expected.size)
        assertEquals(expected.keys, SettingsRegistry.all.map { it.id }.toSet())
        assertEquals(SettingsRegistry.all.size, SettingsRegistry.all.map { it.id }.toSet().size)
        SettingsRegistry.all.forEach { def ->
            val row = expected.getValue(def.id)
            assertEquals(def.id, row.group, def.page.group?.title)
            assertEquals(def.id, row.page, def.page.title)
            assertEquals(def.id, row.card, def.card)
        }
    }

    @Test fun everyGroupHasThreeOrFourSubPagesAndEverySubPageFitsTheRowCap() {
        assertEquals(listOf("Home", "Appearance", "Gestures & search", "Notifications & Focus", "Data & about"),
            SettingsGroup.entries.map { it.title })
        val counts = SettingsGroup.entries.map { group ->
            SettingsPage.entries.count { it.group == group && it.parent == null }
        }
        assertEquals(listOf(3, 4, 3, 3, 4), counts)
        SettingsPage.entries.filter { it != SettingsPage.Root && it.parent == null }.forEach { page ->
            val rows = SettingsRegistry.on(page).size
            assertTrue("${page.title} has $rows rows", rows <= 8)
        }
        // Pages & alerts is a list editor plus four rows, still under the cap.
        assertEquals(4, SettingsRegistry.on(SettingsPage.HubPages).size)
        assertEquals(8, SettingsRegistry.on(SettingsPage.Hubs).size)
    }

    @Test fun selfEntryRowsAreSearchableButHiddenOnTheirOwnPage() {
        assertEquals(setOf("Comms", "Focus"), SettingsRegistry.selfEntryIds)
        assertTrue("Comms" in SettingsRegistry.on(SettingsPage.Messages).map { it.id })
        assertTrue("Focus" in SettingsRegistry.on(SettingsPage.Focus).map { it.id })
        assertTrue("Comms" !in SettingsRegistry.shown(SettingsPage.Messages, SettingsSnapshot()).map { it.id })
        assertTrue("Focus" !in SettingsRegistry.shown(SettingsPage.Focus, SettingsSnapshot()).map { it.id })
        assertTrue(SettingsRegistry.search("Messages").any { it.id == "Comms" })
        assertTrue(SettingsRegistry.search("Focus").any { it.id == "Focus" })
    }

    @Test fun theNewMessagesRowsReadTheRepositoryValuesAndTheStorageLine() {
        val rows = SettingsRegistry.shown(SettingsPage.Messages, SettingsSnapshot(commsStoredCount = 3, commsStorageLine = "3 messages · 2 KB"))
            .map { it.id }
        assertEquals(listOf("CommsEnabled", "CommsRecordHistory", "CommsApps", "QuickReplies", "CommsClearHistory"), rows)
        assertEquals("On", SettingsRegistry.byId.getValue("CommsEnabled").value(SettingsSnapshot(commsEnabled = true)))
        assertEquals("Off", SettingsRegistry.byId.getValue("CommsRecordHistory").value(SettingsSnapshot(commsRecordHistory = false)))
        assertEquals("3 messages · 2 KB",
            SettingsRegistry.byId.getValue("CommsClearHistory").value(SettingsSnapshot(commsStorageLine = "3 messages · 2 KB")))
        assertEquals(SettingsPage.CommsApps, SettingsRegistry.byId.getValue("CommsApps").opens)
        assertEquals(SettingsPage.QuickReplies, SettingsRegistry.byId.getValue("QuickReplies").opens)
        assertEquals(SettingsPage.Messages, SettingsRegistry.byId.getValue("Comms").page)
    }

    @Test fun searchFindsEveryRowOldPageNameAndTheBreadcrumb() {
        SettingsRegistry.all.forEach { def ->
            assertTrue(def.id, SettingsRegistry.search(def.title).contains(def))
            assertTrue(def.id, SettingsSearchEntries.all.any { it.key == "setting:${def.id}" })
        }
        listOf("Look", "Layout", "Stats hub pages", "Search and notifications", "Backup and about", "Comms",
            "Messages and quick replies", "Gestures and feel").forEach { old ->
            assertTrue("old page name: $old", SettingsRegistry.search(old).isNotEmpty())
        }
        assertEquals("Appearance · Hubs", SettingsRegistry.byId.getValue("StatsVisible").page.breadcrumb)
        assertEquals("Appearance · Hubs · Pages & alerts", SettingsRegistry.byId.getValue("HubSize").page.breadcrumb)
        assertEquals("Notifications & Focus · Messages", SettingsRegistry.byId.getValue("CommsEnabled").page.breadcrumb)
        assertTrue(SettingsRegistry.search("look").any { it.page == SettingsPage.AppearanceSkin })
        assertTrue(SettingsRegistry.search("layout").any { it.page == SettingsPage.HomeArcs })
        assertTrue(SettingsRegistry.search("storage").any { it.id == "CommsClearHistory" })
    }

    @Test fun searchResultSubtitleIsTheBreadcrumbAndTheHiddenRowNote() {
        val visible = SettingsRegistry.byId.getValue("HubSize")
        assertEquals("Appearance · Hubs · Pages & alerts", settingsSearchSubtitle(visible, false))
        assertEquals("Appearance · Hubs · Pages & alerts · not shown now, it has no effect here",
            settingsSearchSubtitle(visible, true))
        assertEquals("Notifications & Focus · Messages", settingsSearchSubtitle(SettingsRegistry.byId.getValue("CommsEnabled"), false))
    }

    @Test fun theHubObjectIsOnlyUnderAppearanceHubs() {
        val hubRows = listOf("GlassEffect", "GlassRefraction", "ArcHubColour", "ArcHubOpacity", "StatsHubColour",
            "StatsHubOpacity", "StatsVisible", "HubPages", "HubSize", "HubFullScreen", "Agenda", "StatsAlerts").map { SettingsRegistry.byId.getValue(it) }
        hubRows.forEach { def ->
            assertEquals(def.id, SettingsGroup.Appearance, def.page.group)
            assertTrue(def.id, def.page == SettingsPage.Hubs || def.page == SettingsPage.HubPages)
        }
    }

    @Test fun everyPageAndHubOptionTargetHasAParentChainEndingAtTheRoot() {
        SettingsPage.entries.filter { it != SettingsPage.Root }.forEach { page ->
            var cursor = page
            val seen = mutableSetOf<SettingsPage>()
            var steps = 0
            while (true) {
                assertTrue("loop at $page", seen.add(cursor))
                when (val parent = settingsParent(SettingsScreen.Page(cursor))) {
                    SettingsScreen.Root -> break
                    is SettingsScreen.Group -> {
                        assertEquals(cursor.group, parent.group)
                        assertEquals(SettingsScreen.Root, settingsParent(parent))
                        break
                    }
                    is SettingsScreen.Page -> { cursor = parent.page; steps++ }
                }
                assertTrue("parent chain too long at $page", steps < 10)
            }
        }
        hubPageOptions.forEach { (id, spec) ->
            assertNotNull(id, SettingsPage.entries.firstOrNull { it == spec.fullSettings })
            assertTrue(id, settingsParent(spec.fullSettings) != SettingsScreen.Page(spec.fullSettings))
        }
    }

    @Test fun oldHubOptionTargetsAreRemappedAndTheOldAliasIsGone() {
        assertEquals(SettingsPage.HubPages, hubPageOptions.getValue(LibraryPage.Id).fullSettings)
        assertEquals(SettingsPage.HubPages, hubPageOptions.getValue(TodayPage.Id).fullSettings)
        assertEquals(SettingsPage.StatsAlerts, hubPageOptions.getValue(DevicePage.Id).fullSettings)
        assertEquals(SettingsPage.Agenda, hubPageOptions.getValue("agenda").fullSettings)
        assertEquals(SettingsPage.Messages, hubPageOptions.getValue("comms").fullSettings)
        assertEquals(SettingsPage.Focus, hubPageOptions.getValue("focus").fullSettings)
        assertNull(SettingsPage.entries.firstOrNull { it.name == "StatsPages" })
        assertNull(SettingsPage.entries.firstOrNull { it.name == "Comms" && it != SettingsPage.Messages })
    }

    @Test fun hubPageNamesAndDefaultTabsStayStable() {
        assertEquals("Messages", hubPageName("comms"))
        assertEquals("Controls", hubPageName("quick_toggles"))
        assertEquals("Commonplace", hubPageName("quotes"))
        assertEquals("Today", hubPageName("today"))
        assertEquals("Device", hubPageName("system"))
        assertEquals("Commonplace", hubPageName(CommonplacePage.Id))
        assertEquals("Library", hubPageName("library"))
        assertEquals("Arcade", hubPageName("arcade"))
        assertTrue("focus" !in HubCatalog.defaultTabs)
        assertEquals(HubCatalog.pages.toSet(), hubPageOptions.keys)
    }

    @Test fun everyStableBackupKeyIsStillThere() {
        val keys = BackupSpecs.settings.keys.map { it.name }.toSet()
        listOf("comms_enabled", "comms_apps", "comms_canned_replies", "comms_keep_history",
            "skin_id", "stats_visible", "clock_visible", "main_apps").forEach {
            assertTrue(it, it in keys)
        }
        // These ids came from the seven flat pages and must keep their names.
        listOf("Appearance", "SkinStudio", "WallpaperImage", "HubSize", "HubPages", "Agenda", "StatsAlerts",
            "Comms", "Focus", "Backup", "About", "Permissions", "Diagnostics").forEach {
            assertNotNull(it, SettingsRegistry.byId[it])
        }
    }
}
