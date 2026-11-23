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
        "ArcsShown" to Row("Home layout", "Arcs", "Arcs"),
        "IconsPerArc" to Row("Home layout", "Arcs", "Arcs"),
        "ArcMaximums" to Row("Home layout", "Arcs", "Arc capacities"),
        "ArcLayout" to Row("Home layout", "Arcs", "Arcs"),
        "LeftHand" to Row("Home layout", "Arcs", "Arcs"),
        "LockLayout" to Row("Home layout", "Arcs", "Arcs"),
        "Rotation" to Row("Home layout", "Arcs", "Arcs"),
        "MainApps" to Row("Home layout", "Apps & folders", "Apps"),
        "Folders" to Row("Home layout", "Apps & folders", "Folders"),
        "MarkWorkApps" to Row("Home layout", "Apps & folders", "Work apps"),
        "HiddenApps" to Row("Home layout", "Hidden & locked", "Hidden apps"),
        "HiddenFolders" to Row("Home layout", "Hidden & locked", "Hidden folders"),
        "LockedFolders" to Row("Home layout", "Hidden & locked", "Locked"),
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
        // Hub pages (T375: page visibility and default size moved up one level)
        "StatsVisible" to Row("Hub pages", "Hub pages", "Stats hub"),
        "HubPages" to Row("Hub pages", "Hub pages", "Page visibility"),
        "HubSize" to Row("Hub pages", "Hub pages", "Stats hub"),
        "HubFullScreen" to Row("Hub pages", "Hub pages", "Stats hub"),
        "Agenda" to Row("Hub pages", "Hub pages", "Pages"),
        "StatsAlerts" to Row("Hub pages", "Hub pages", "Alerts"),
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
        // Gestures & search > Search settings (T375: sources, access, web engine and history in one page)
        "Search" to Row("Gestures & search", "Search settings", "Search"),
        "SearchPhoneSettings" to Row("Gestures & search", "Search settings", "Search"),
        "SearchFiles" to Row("Gestures & search", "Search settings", "Search"),
        "SearchEvents" to Row("Gestures & search", "Search settings", "Search"),
        // Notifications & Focus > Notifications
        "NotificationAccess" to Row("Notifications & focus", "Notifications", "Access"),
        "NotificationBadges" to Row("Notifications & focus", "Notifications", "Badges"),
        "BadgesInAllApps" to Row("Notifications & focus", "Notifications", "Badges"),
        "MenuNotifications" to Row("Notifications & focus", "Notifications", "Menus"),
        "MenuNotificationText" to Row("Notifications & focus", "Notifications", "Menus"),
        // Notifications & Focus > Messages
        "Comms" to Row("Notifications & focus", "Messages", "Messages"),
        "CommsEnabled" to Row("Notifications & focus", "Messages", "Messages"),
        "CommsRecordHistory" to Row("Notifications & focus", "Messages", "History"),
        "CommsApps" to Row("Notifications & focus", "Messages", "Apps"),
        "QuickReplies" to Row("Notifications & focus", "Messages", "Replies"),
        "CommsClearHistory" to Row("Notifications & focus", "Messages", "History"),
        // Notifications & Focus > Focus
        "Focus" to Row("Notifications & focus", "Focus", "Focus"),
        // Data & about > Backup
        "Backup" to Row("Data & help", "Backup", "Backup"),
        "WallpaperImageBackup" to Row("Data & help", "Backup", "Backup"),
        // Data & about > This build
        "About" to Row("Data & help", "This build", "About"),
        "WhatsNew" to Row("Data & help", "This build", "About"),
        "VersionHistory" to Row("Data & help", "This build", "About"),
        "ReportProblem" to Row("Data & help", "This build", "About"),
        // Data & about > Permissions
        "Permissions" to Row("Data & help", "Permissions", "Access"),
        "RerunOnboarding" to Row("Data & help", "Permissions", "Access"),
        "AndroidSettings" to Row("Data & help", "Permissions", "Access"),
        // Data & about > Help
        "Diagnostics" to Row("Data & help", "Help", "Help"),
    )

    @Test fun theExactRowMapIsImplementedOnce() {
        assertEquals(77, expected.size)
        // T396: the family build adds one row (Check for updates, Data & help > This build > Updates); no other build does.
        val withBuildRows = expected + FamilyUpdate.settingRows.associate { it.id to Row("Data & help", "This build", "Updates") }
        assertEquals(withBuildRows.keys, SettingsRegistry.all.map { it.id }.toSet())
        assertEquals(SettingsRegistry.all.size, SettingsRegistry.all.map { it.id }.toSet().size)
        SettingsRegistry.all.forEach { def ->
            val row = withBuildRows.getValue(def.id)
            assertEquals(def.id, row.group, def.page.group?.title)
            assertEquals(def.id, row.page, def.page.title)
            assertEquals(def.id, row.card, def.card)
        }
    }

    @Test fun everyGroupHasThreeOrFourSubPagesAndEverySubPageFitsTheRowCap() {
        assertEquals(listOf("Appearance", "Home layout", "Hub pages", "Gestures & search", "Notifications & focus", "Data & help"),
            SettingsGroup.entries.map { it.title })
        val counts = SettingsGroup.entries.map { group ->
            SettingsPage.entries.count { it.group == group && it.parent == null }
        }
        // T382 adds the personal-build Launch pause page under Notifications & focus. T373: Hub pages is one direct page,
        // and Diagnostics is reached from Data & help without the Help wrapper.
        assertEquals(listOf(4, 3, 1, 3, 4, 5), counts)
        SettingsPage.entries.filter { it != SettingsPage.Root && it.parent == null }.forEach { page ->
            val rows = SettingsRegistry.on(page).size
            assertTrue("${page.title} has $rows rows", rows <= 8)
        }
        // Pages & alerts is a list editor plus four rows, still under the cap.
        assertEquals(6, SettingsRegistry.on(SettingsPage.HubPages).size)
        assertEquals(6, SettingsRegistry.on(SettingsPage.Hubs).size)
    }

    @Test fun selfEntryRowsAreSearchableButHiddenOnTheirOwnPage() {
        assertEquals(setOf("Comms", "Focus", "HubPages", "Search"), SettingsRegistry.selfEntryIds)
        assertTrue("Comms" in SettingsRegistry.on(SettingsPage.Messages).map { it.id })
        assertTrue("Focus" in SettingsRegistry.on(SettingsPage.Focus).map { it.id })
        assertTrue("Comms" !in SettingsRegistry.shown(SettingsPage.Messages, SettingsSnapshot()).map { it.id })
        assertTrue("Focus" !in SettingsRegistry.shown(SettingsPage.Focus, SettingsSnapshot()).map { it.id })
        assertTrue("HubPages" in SettingsRegistry.on(SettingsPage.HubPages).map { it.id })
        assertTrue("Search" in SettingsRegistry.on(SettingsPage.Search).map { it.id })
        assertTrue("HubPages" !in SettingsRegistry.shown(SettingsPage.HubPages, SettingsSnapshot()).map { it.id })
        assertTrue("Search" !in SettingsRegistry.shown(SettingsPage.Search, SettingsSnapshot()).map { it.id })
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
        assertEquals("Hub pages · Hub pages", SettingsRegistry.byId.getValue("StatsVisible").page.breadcrumb)
        assertEquals("Hub pages · Hub pages", SettingsRegistry.byId.getValue("HubSize").page.breadcrumb)
        assertEquals("Notifications & focus · Messages", SettingsRegistry.byId.getValue("CommsEnabled").page.breadcrumb)
        assertTrue(SettingsRegistry.search("look").any { it.page == SettingsPage.AppearanceSkin })
        assertTrue(SettingsRegistry.search("layout").any { it.page == SettingsPage.HomeArcs })
        assertTrue(SettingsRegistry.search("storage").any { it.id == "CommsClearHistory" })
    }

    @Test fun searchResultSubtitleIsTheBreadcrumbAndTheHiddenRowNote() {
        val visible = SettingsRegistry.byId.getValue("HubSize")
        assertEquals("Hub pages · Hub pages", settingsSearchSubtitle(visible, false))
        assertEquals("Hub pages · Hub pages · not shown now, it has no effect here",
            settingsSearchSubtitle(visible, true))
        assertEquals("Notifications & focus · Messages", settingsSearchSubtitle(SettingsRegistry.byId.getValue("CommsEnabled"), false))
    }

    @Test fun theHubCardsAreUnderAppearanceAndTheirPagesUnderHubPages() {
        listOf("GlassEffect", "GlassRefraction", "ArcHubColour", "ArcHubOpacity", "StatsHubColour",
            "StatsHubOpacity").map { SettingsRegistry.byId.getValue(it) }.forEach { def ->
            assertEquals(def.id, SettingsGroup.Appearance, def.page.group)
            assertEquals(def.id, SettingsPage.Hubs, def.page)
        }
        listOf("StatsVisible", "HubPages", "HubSize", "HubFullScreen", "Agenda", "StatsAlerts").map { SettingsRegistry.byId.getValue(it) }.forEach { def ->
            assertEquals(def.id, SettingsGroup.HubPages, def.page.group)
            assertEquals(def.id, SettingsPage.HubPages, def.page)
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
