package com.brightblu.parc

import com.brightblu.parc.feature.settings.ui.SettingKind

internal enum class SettingsSectionId(val title: String) {
    Appearance("Look"), Layout("Home layout"), Feel("Gestures and feel"), Stats("Stats hub"), Search("Search"),
    Notifications("Notifications and messages"), Focus("Focus"), Permissions("Phone access"),
    Backup("Backup and About"),

}

internal data class SettingsRowSpec(val section: SettingsSectionId, val kind: SettingKind,
    val description: String = "", val title: String? = null)

/** Exhaustive dispatch: adding a destination requires an explicit section and row treatment. */
internal fun settingsRowSpec(id: SettingsRowId): SettingsRowSpec = when (id) {
    SettingsRowId.Appearance -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Page,
        "Choose the colours and shapes of Parc", "Skin")
    SettingsRowId.SkinStudio -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Page,
        "Make your own skin, import or export skin files", "Skin editor")
    SettingsRowId.WallpaperSkinMode -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Choice,
        "Dark, light or follow the phone for the Wallpaper skin", "Wallpaper skin colours")
    SettingsRowId.WallpaperSkinLook -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Choice,
        "Which skin the Wallpaper skin copies its shapes and frames from", "Wallpaper skin based on")
    SettingsRowId.Feel -> SettingsRowSpec(SettingsSectionId.Feel, SettingKind.Page,
        "Vibration strength and click sounds", "Haptics and sound")
    SettingsRowId.Permissions -> SettingsRowSpec(SettingsSectionId.Permissions, SettingKind.Page,
        "See which optional permissions are on and turn them on", "Permissions")
    SettingsRowId.PanelOpacity -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Choice, "Opacity of the floating hub panel", "Panel opacity")
    SettingsRowId.WallpaperReadability -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Toggle, "Keep text readable over pictures", "Keep text readable")
    SettingsRowId.WallpaperImage -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Page, "Choose what sits behind Parc", "Wallpaper")
    SettingsRowId.WallpaperImageBackup -> SettingsRowSpec(SettingsSectionId.Backup, SettingKind.Toggle, "Include the private picture in exported backups, maximum 2 MB", "Include wallpaper in backups")
    SettingsRowId.ImageReadabilityScrim -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Toggle, "Cached gradient behind text over your image", "Keep text readable")
    SettingsRowId.WallpaperDim -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Choice, "Dim your picture from 0 to 80 percent", "Dim")
    SettingsRowId.Look -> SettingsRowSpec(SettingsSectionId.Appearance, SettingKind.Page,
        "Change icon shape, size and pack, clock style, date and alarm", "Icons and clock")
    SettingsRowId.MainApps -> SettingsRowSpec(SettingsSectionId.Layout, SettingKind.Page, "Choose and order the four inner-arc apps")
    SettingsRowId.ArcsShown -> SettingsRowSpec(SettingsSectionId.Layout, SettingKind.Choice,
        "Show three or four arcs. Items on a removed arc move to the next ring", "Arcs shown")
    SettingsRowId.IconsPerArc -> SettingsRowSpec(SettingsSectionId.Layout, SettingKind.Choice,
        "Limit visible icons on each arc. Turn a ring to reach the rest", "Icons per arc")
    SettingsRowId.ArcLayout -> SettingsRowSpec(SettingsSectionId.Layout, SettingKind.Choice, "Line up icons or stagger them between arcs")
    SettingsRowId.Rotation -> SettingsRowSpec(SettingsSectionId.Layout, SettingKind.Page, "How to turn and position each arc")
    SettingsRowId.Joystick -> SettingsRowSpec(SettingsSectionId.Feel, SettingKind.Toggle, "Drag from the hub to aim at an app")
    SettingsRowId.HubDoubleTap -> SettingsRowSpec(SettingsSectionId.Feel, SettingKind.Toggle, "Open the last app with two hub taps")
    SettingsRowId.SwipeUp, SettingsRowId.HubLongPress -> SettingsRowSpec(SettingsSectionId.Feel, SettingKind.Choice, "Choose what this gesture opens")
    SettingsRowId.Folders -> SettingsRowSpec(SettingsSectionId.Layout, SettingKind.Page, "Create, rename and organise folders")
    SettingsRowId.LockedFolders -> SettingsRowSpec(SettingsSectionId.Layout, SettingKind.Page,
        "Hide the apps in chosen folders until you confirm it is you; unlock time and folder names", "Locked folders")
    SettingsRowId.HiddenApps -> SettingsRowSpec(SettingsSectionId.Layout, SettingKind.Page, "Hide apps or bring them back")
    SettingsRowId.StatsVisible -> SettingsRowSpec(SettingsSectionId.Stats, SettingKind.Toggle, "Show device information above the arcs", "Show stats hub")
    SettingsRowId.HubSize -> SettingsRowSpec(SettingsSectionId.Stats, SettingKind.Page, "Compact, Standard or Tall for every page", "Hub size")
    SettingsRowId.HeaderTime -> SettingsRowSpec(SettingsSectionId.Stats, SettingKind.Toggle,
        "Show a small time beside the name on every hub page", "Time in the page header")
    SettingsRowId.HubPages -> SettingsRowSpec(SettingsSectionId.Stats, SettingKind.Page, "Add, Remove or Move pages; clock options include 24-hour clock, Show seconds and Second time zone", "Hub pages")
    SettingsRowId.Agenda -> SettingsRowSpec(SettingsSectionId.Stats, SettingKind.Page,
        "Choose calendars and how many days to show", "Agenda")
    SettingsRowId.StatsAlerts -> SettingsRowSpec(SettingsSectionId.Stats, SettingKind.Page, "Set warning levels and show history graphs")
    SettingsRowId.Search -> SettingsRowSpec(SettingsSectionId.Search, SettingKind.Page, "Web engine, contacts access and recent searches")
    SettingsRowId.SearchPhoneSettings -> SettingsRowSpec(SettingsSectionId.Search, SettingKind.Toggle,
        "Let search open phone settings pages such as Wi-Fi or Battery", "Search phone settings")
    SettingsRowId.SearchFiles -> SettingsRowSpec(SettingsSectionId.Search, SettingKind.Toggle,
        "Let search find photos, videos and music by name. Needs permission, so it stays off until you allow it", "Search files")
    SettingsRowId.SearchEvents -> SettingsRowSpec(SettingsSectionId.Search, SettingKind.Toggle,
        "Let search find calendar events in the next 60 days by title. Needs calendar permission", "Search calendar events")
    SettingsRowId.NotificationBadges -> SettingsRowSpec(SettingsSectionId.Notifications, SettingKind.Toggle, "Mark apps that have unread notifications")
    SettingsRowId.BadgesInAllApps -> SettingsRowSpec(SettingsSectionId.Notifications, SettingKind.Toggle, "Also show unread marks in All apps")
    SettingsRowId.MenuNotifications -> SettingsRowSpec(SettingsSectionId.Notifications, SettingKind.Toggle, "Show an app's current notifications when you hold its icon", "Notifications in menus")
    SettingsRowId.MenuNotificationText -> SettingsRowSpec(SettingsSectionId.Notifications, SettingKind.Toggle, "Show the message line under each title; off shows titles only", "Show message text")
    SettingsRowId.Comms -> SettingsRowSpec(SettingsSectionId.Notifications, SettingKind.Page, "Comms apps, message history and quick replies", "Messages and quick replies")
    SettingsRowId.Focus -> SettingsRowSpec(SettingsSectionId.Focus, SettingKind.Page, "Choose a Mode, its apps, times and how strict to be")
    SettingsRowId.MarkWorkApps -> SettingsRowSpec(SettingsSectionId.Focus, SettingKind.Toggle, "Show a briefcase on work-profile apps")
    SettingsRowId.NotificationAccess -> SettingsRowSpec(SettingsSectionId.Permissions, SettingKind.Action, "Allow access for badges, Comms and notifications in menus", "Notification access")
    SettingsRowId.RerunOnboarding -> SettingsRowSpec(SettingsSectionId.Permissions, SettingKind.Page, "Review home app, usage, contacts and notification access", "Permission guidance")
    SettingsRowId.AndroidSettings -> SettingsRowSpec(SettingsSectionId.Permissions, SettingKind.Action, "Open the device settings app")
    SettingsRowId.Backup -> SettingsRowSpec(SettingsSectionId.Backup, SettingKind.Page, "Export or restore a layout file")
    SettingsRowId.Diagnostics -> SettingsRowSpec(SettingsSectionId.Backup, SettingKind.Page, "View scene details and the latest error")
    SettingsRowId.About -> SettingsRowSpec(SettingsSectionId.Backup, SettingKind.Page, "Version and local data")
}

internal fun filterSettingsRows(rows: List<SettingsRow>, query: String): List<SettingsRow> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return rows.filter { row -> words.all { word ->
        row.label.contains(word, ignoreCase = true) || row.description.contains(word, ignoreCase = true)
    } }
}

internal fun groupSettingsRows(rows: List<SettingsRow>, query: String): Map<SettingsSectionId, List<SettingsRow>> {
    val filtered = filterSettingsRows(rows, query)
    return SettingsSectionId.entries.mapNotNull { section ->
        filtered.filter { it.section == section }.takeIf { it.isNotEmpty() }?.let { section to it }
    }.toMap()
}

/** One step out of a nested settings page, shared by arrow and system Back. */
internal fun settingsParent(page: SettingsPage): SettingsPage = when (page) {
    SettingsPage.WhatsNew, SettingsPage.VersionHistory -> SettingsPage.About
    SettingsPage.CommsApps, SettingsPage.QuickReplies -> SettingsPage.Comms
    else -> SettingsPage.Root
}

internal val wallpaperRows = setOf(SettingsRowId.WallpaperReadability, SettingsRowId.ImageReadabilityScrim, SettingsRowId.WallpaperDim)
internal val skinRows = setOf(SettingsRowId.SkinStudio, SettingsRowId.WallpaperSkinMode, SettingsRowId.WallpaperSkinLook)

internal fun settingsRowVisible(id: SettingsRowId, look: com.brightblu.parc.model.LookSettings,
    wallpaperSkin: Boolean, stats: Boolean, badges: Boolean, menus: Boolean): Boolean = when (id) {
    SettingsRowId.ImageReadabilityScrim -> false // retained search/backup identity, one control
    SettingsRowId.WallpaperReadability, SettingsRowId.WallpaperDim -> look.wallpaper == com.brightblu.parc.model.WallpaperStyle.System || look.wallpaper == com.brightblu.parc.model.WallpaperStyle.Image
    SettingsRowId.WallpaperSkinMode, SettingsRowId.WallpaperSkinLook -> wallpaperSkin
    SettingsRowId.HubSize, SettingsRowId.HeaderTime, SettingsRowId.HubPages, SettingsRowId.Agenda, SettingsRowId.StatsAlerts -> stats
    SettingsRowId.BadgesInAllApps -> badges
    SettingsRowId.MenuNotificationText -> menus
    else -> true
}
internal fun settingsJump(id: SettingsRowId): SettingsRowId =
    if (id == SettingsRowId.ImageReadabilityScrim) SettingsRowId.WallpaperReadability else id

internal fun sectionDescription(id: SettingsSectionId): String = when (id) {
    SettingsSectionId.Appearance -> "Skin, Wallpaper, Icons and clock, Panel"
    SettingsSectionId.Layout -> "Arcs, Main apps, Folders, Locked folders, Hidden apps"
    SettingsSectionId.Feel -> "Joystick, hub taps, swipe up, haptics and sound"
    SettingsSectionId.Stats -> "Pages, order and each page’s options"
    SettingsSectionId.Search -> "Apps, contacts, files and calendar results"
    SettingsSectionId.Notifications -> "Badges, notification text, messages and quick replies"
    SettingsSectionId.Focus -> "Modes, schedules and work apps"
    SettingsSectionId.Permissions -> "Optional phone access and what it is for"
    SettingsSectionId.Backup -> "Backup, restore, version and What’s new"
}

/** Stable navigation identities; the scaffold does not move persisted keys. */
internal val editorDestinations = mapOf(
    com.brightblu.parc.feature.settings.ui.EditorSpec.MainApps to SettingsPage.MainApps,
    com.brightblu.parc.feature.settings.ui.EditorSpec.Folders to SettingsPage.Folders,
    com.brightblu.parc.feature.settings.ui.EditorSpec.HiddenApps to SettingsPage.Hidden,
    com.brightblu.parc.feature.settings.ui.EditorSpec.Rotation to SettingsPage.Rotation,
    com.brightblu.parc.feature.settings.ui.EditorSpec.HubPages to SettingsPage.HubPages,
    com.brightblu.parc.feature.settings.ui.EditorSpec.Agenda to SettingsPage.Agenda,
    com.brightblu.parc.feature.settings.ui.EditorSpec.Alerts to SettingsPage.StatsAlerts,
    com.brightblu.parc.feature.settings.ui.EditorSpec.Skin to SettingsPage.SkinStudio,
    com.brightblu.parc.feature.settings.ui.EditorSpec.CommsApps to SettingsPage.CommsApps,
    com.brightblu.parc.feature.settings.ui.EditorSpec.QuickReplies to SettingsPage.QuickReplies,
)

internal fun settingsGroupIcon(group: SettingsSectionId): String = when (group) {
    SettingsSectionId.Appearance -> "◐"
    SettingsSectionId.Layout -> "◉"
    SettingsSectionId.Feel -> "↟"
    SettingsSectionId.Stats -> "▥"
    SettingsSectionId.Search -> "⌕"
    SettingsSectionId.Notifications -> "✉"
    SettingsSectionId.Focus -> "◎"
    SettingsSectionId.Permissions -> "◇"
    SettingsSectionId.Backup -> "↥"
}
