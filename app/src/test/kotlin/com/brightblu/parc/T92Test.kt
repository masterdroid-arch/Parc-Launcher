package com.brightblu.parc

import com.brightblu.parc.feature.settings.ui.SettingKind
import org.junit.Assert.*
import org.junit.Test

class T92Test {
    @org.junit.Test fun hubPageOptionsCatalogIsCompleteForSettingsAndHome() {
        org.junit.Assert.assertEquals(HubCatalog.pages.toSet(), hubPageOptions.keys)
        org.junit.Assert.assertTrue(settingsRowSpec(SettingsRowId.HubPages).description.contains("options"))
        assertTrue(settingsRowSpec(SettingsRowId.HubPages).description.contains("Second time zone"))
        assertTrue(hubPageOptions.getValue("clock").tap.contains("Long press"))
        hubPageOptions.values.forEach { org.junit.Assert.assertTrue(it.fullSettings in SettingsPage.entries) }
    }
    @Test fun sharedEditorsKeepEveryExistingDestination() {
        assertEquals(com.brightblu.parc.feature.settings.ui.EditorSpec.entries.toSet(), editorDestinations.keys)
        assertEquals(10, editorDestinations.values.distinct().size)
        editorDestinations.values.forEach { assertTrue(it in SettingsPage.entries) }
    }

    @Test fun focusV2UsesTheExistingRootPage() {
        val spec = settingsRowSpec(SettingsRowId.Focus)
        assertEquals(com.brightblu.parc.feature.settings.ui.SettingKind.Page, spec.kind)
        assertTrue(spec.description.contains("how strict"))
        assertTrue(spec.description.contains("apps"))
        assertTrue(spec.description.contains("times"))
    }

    private val rows = settingsRootRows("Midnight", false, true, false, true)

    @Test fun everySettingIdHasExactlyOneReachableRow() {
        // Stable ids from the pre-redesign host plus the newly separated destinations.
        val expected = setOf("Appearance", "Look", "PanelOpacity", "WallpaperReadability", "WallpaperImage", "WallpaperImageBackup", "ImageReadabilityScrim", "WallpaperDim", "Focus", "SkinStudio", "WallpaperSkinMode", "WallpaperSkinLook", "Feel", "ArcsShown", "IconsPerArc", "ArcLayout", "StatsVisible", "HubSize",
            "HubDoubleTap", "SwipeUp", "HubLongPress", "Joystick", "Folders", "LockedFolders", "HiddenApps", "Backup", "Permissions",
            "RerunOnboarding", "NotificationAccess", "NotificationBadges", "BadgesInAllApps", "MenuNotifications", "MenuNotificationText", "MarkWorkApps",
            "Comms", "StatsAlerts", "HubPages", "HeaderTime", "Agenda", "Diagnostics", "AndroidSettings", "Search", "SearchPhoneSettings", "SearchFiles", "SearchEvents", "MainApps", "Rotation", "About")
        assertEquals(expected, SettingsRowId.entries.map { it.name }.toSet())
        val all = settingsRootRows("Midnight", false, true, false, true,
            wallpaperMode = com.brightblu.parc.core.data.skins.WallpaperMode.System)
        assertEquals(expected, SettingsRowId.entries.map { it.name }.toSet())
        SettingsRowId.entries.forEach { assertTrue(settingsRowSpec(it).description.isNotBlank()) }
        assertFalse(all.any { it.id in skinRows && it.id != SettingsRowId.SkinStudio })
        all.groupingBy { it.id }.eachCount().forEach { (_, count) -> assertEquals(1, count) }
    }

    @Test fun allNineSectionsArePresentAndEveryRowIsInOneGroup() {
        val groups = groupSettingsRows(rows, "")
        assertEquals(SettingsSectionId.entries, groups.keys.toList())
        assertEquals(rows.size, groups.values.sumOf { it.size })
        assertEquals("Look", SettingsSectionId.entries.first().title)
        assertEquals("Backup and About", SettingsSectionId.entries.last().title)
        assertTrue(groups.getValue(SettingsSectionId.Layout).any { it.id == SettingsRowId.MainApps })
        assertTrue(groups.getValue(SettingsSectionId.Search).any { it.id == SettingsRowId.Search })
    }

    @Test fun fourTreatmentsAreExplicitAndTogglesCarryRealState() {
        assertEquals(setOf(SettingKind.Toggle, SettingKind.Choice, SettingKind.Page, SettingKind.Action), rows.map { it.kind }.toSet())
        rows.forEach { row -> assertEquals(row.kind == SettingKind.Toggle, row.checked != null) }
        assertEquals(SettingKind.Choice, rows.single { it.id == SettingsRowId.ArcLayout }.kind)
        assertEquals(SettingKind.Page, rows.single { it.id == SettingsRowId.RerunOnboarding }.kind)
        assertEquals(SettingKind.Action, rows.single { it.id == SettingsRowId.NotificationAccess }.kind)
        assertTrue(rows.single { it.id == SettingsRowId.StatsVisible }.checked!!)
        assertEquals(SettingKind.Page, rows.single { it.id == SettingsRowId.HubSize }.kind)
    }

    @Test fun filterUsesTitlesAndDescriptionsWithTrimmedCaseInsensitiveWords() {
        assertEquals(rows, filterSettingsRows(rows, "  "))
        assertEquals(listOf(SettingsRowId.Look), filterSettingsRows(rows, " ICON PACK ").map { it.id })
        assertEquals(listOf(SettingsRowId.RerunOnboarding, SettingsRowId.Search), filterSettingsRows(rows, "contacts").map { it.id })
        assertEquals(listOf(SettingsRowId.Comms), filterSettingsRows(rows, "quick replies").map { it.id })
        assertTrue(filterSettingsRows(rows, "unfindable").isEmpty())
        assertTrue(groupSettingsRows(rows, "unfindable").isEmpty())
        assertEquals(listOf(SettingsSectionId.Search), groupSettingsRows(rows, "recent").keys.toList())
    }

    @Test fun nestedPagesReturnToTheirParent() {
        assertEquals(SettingsPage.Comms, settingsParent(SettingsPage.CommsApps))
        assertEquals(SettingsPage.Comms, settingsParent(SettingsPage.QuickReplies))
        SettingsPage.entries.filterNot { it in listOf(SettingsPage.CommsApps, SettingsPage.QuickReplies, SettingsPage.WhatsNew, SettingsPage.VersionHistory) }.forEach {
            assertEquals(SettingsPage.Root, settingsParent(it))
        }
    }

    @Test fun storedValuesAndToggleChangesKeepIdentityAndGrouping() {
        val changed = settingsRootRows("Field Atlas Light", true, false, true, false, hubDoubleTap = false)
        assertEquals(rows.filter { it.id in changed.map { row -> row.id } }.map { it.id to it.section }, changed.map { it.id to it.section })
        assertEquals(false, changed.single { it.id == SettingsRowId.Joystick }.checked)
        assertEquals("Aligned", changed.single { it.id == SettingsRowId.ArcLayout }.detail)
    }
}
