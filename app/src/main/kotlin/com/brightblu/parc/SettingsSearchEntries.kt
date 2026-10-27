package com.brightblu.parc

import com.brightblu.parc.feature.search.extras.ParcSettingEntry

/**
 * OPUS8 (T133): Parc settings and its pages, findable in search. Titles and descriptions come from
 * the Settings catalogue itself ([settingsRootRows] and [settingsRowSpec]), so search uses the same
 * words as Settings. Only fixed page names are listed: no app label, folder name or count, so a
 * locked folder is never revealed. Rows that act at once (switches, "Show setup again") are not
 * listed; only places to go.
 */
internal object SettingsSearchEntries {
    const val RootKey = "root"
    private const val PagePrefix = "page:"
    private const val SectionPrefix = "section:"

    /** Root rows that open a page, and the page they open (same as the Settings root's row taps). */
    private val rowPages: Map<SettingsRowId, SettingsPage> = linkedMapOf(
        SettingsRowId.Appearance to SettingsPage.Appearance,
        SettingsRowId.WallpaperImage to SettingsPage.Wallpaper,
        SettingsRowId.Look to SettingsPage.Look,
        SettingsRowId.PanelOpacity to SettingsPage.Panel,
        SettingsRowId.SkinStudio to SettingsPage.SkinStudio,
        SettingsRowId.Feel to SettingsPage.Feel,
        SettingsRowId.MainApps to SettingsPage.MainApps,
        SettingsRowId.ArcsShown to SettingsPage.LayoutCounts,
        SettingsRowId.Rotation to SettingsPage.Rotation,
        SettingsRowId.SwipeUp to SettingsPage.SwipeUp,
        SettingsRowId.HubLongPress to SettingsPage.HubLongPress,
        SettingsRowId.Folders to SettingsPage.Folders,
        SettingsRowId.LockedFolders to SettingsPage.LockedFolders,
        SettingsRowId.HiddenApps to SettingsPage.Hidden,
        SettingsRowId.HubPages to SettingsPage.HubPages,
        SettingsRowId.Agenda to SettingsPage.Agenda,
        SettingsRowId.StatsAlerts to SettingsPage.StatsAlerts,
        SettingsRowId.Search to SettingsPage.Search,
        SettingsRowId.Comms to SettingsPage.Comms,
        SettingsRowId.Focus to SettingsPage.Focus,
        SettingsRowId.Permissions to SettingsPage.Permissions,
        SettingsRowId.Backup to SettingsPage.Backup,
        SettingsRowId.Diagnostics to SettingsPage.Diagnostics,
        SettingsRowId.About to SettingsPage.About,
    )

    /** Every entry, root first, then the section groups, then the pages in Settings order. */
    val all: List<ParcSettingEntry> by lazy {
        val rows = settingsRootRows("", alignedArcs = false, statsVisible = true, statsExpanded = false, joystick = true)
        buildList {
            add(ParcSettingEntry(RootKey, HomeMenuRow.Settings.label,
                listOf("Settings", "Parc", "Launcher settings", "Home settings", "Preferences", "Options")))
            SettingsSectionId.entries.forEach { section ->
                add(ParcSettingEntry(SectionPrefix + section.name, section.title, listOf(sectionDescription(section))))
            }
            rows.filter { it.id in rowPages }.forEach { row ->
                add(ParcSettingEntry(PagePrefix + row.id.name, row.label, listOf(row.description, row.section.title)))
            }
        }.distinctBy { it.key }
    }

    /** Where an entry's key leads; null for an unknown key. */
    fun target(key: String): SettingsTarget? = when {
        key == RootKey -> SettingsTarget(SettingsPage.Root)
        key.startsWith(SectionPrefix) -> SettingsSectionId.entries.firstOrNull { it.name == key.removePrefix(SectionPrefix) }
            ?.let { SettingsTarget(SettingsPage.Root, it) }
        key.startsWith(PagePrefix) -> SettingsRowId.entries.firstOrNull { it.name == key.removePrefix(PagePrefix) }
            ?.let(rowPages::get)?.let { SettingsTarget(it) }
        else -> null
    }
}
