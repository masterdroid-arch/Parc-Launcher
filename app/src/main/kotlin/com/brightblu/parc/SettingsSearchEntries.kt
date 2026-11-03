package com.brightblu.parc

import com.brightblu.parc.feature.search.extras.ParcSettingEntry

/**
 * OPUS8 (T133), rebuilt on the registry in T175: Parc settings in the Home search. Every entry comes from
 * [SettingsRegistry] (titles, help lines and search words are the ones Settings shows), plus the root and the seven pages.
 * Only fixed setting names are listed: no app label, folder name or count, so a locked folder is never revealed.
 */
internal object SettingsSearchEntries {
    const val RootKey = "root"
    private const val PagePrefix = "page:"
    private const val SettingPrefix = "setting:"

    /** Root first, then the seven pages, then every setting in registry order. */
    val all: List<ParcSettingEntry> by lazy {
        buildList {
            add(ParcSettingEntry(RootKey, HomeMenuRow.Settings.label,
                listOf("Settings", "Parc", "Launcher settings", "Home settings", "Preferences", "Options")))
            SettingsHome.entries.forEach { add(ParcSettingEntry(PagePrefix + it.name, it.title, listOf(it.summary))) }
            SettingsRegistry.all.forEach { def ->
                add(ParcSettingEntry(SettingPrefix + def.id, def.fullTitle, listOf(def.help, def.page.title, def.group) + def.words))
            }
        }
    }

    /** Where an entry's key leads; null for an unknown key. */
    fun target(key: String): SettingsTarget? = when {
        key == RootKey -> SettingsTarget(SettingsPage.Root)
        key.startsWith(PagePrefix) -> SettingsHome.entries.firstOrNull { it.name == key.removePrefix(PagePrefix) }
            ?.let { SettingsTarget(it.settingsPage) }
        key.startsWith(SettingPrefix) -> SettingsRegistry.byId[key.removePrefix(SettingPrefix)]
            ?.let { SettingsTarget(it.page.settingsPage, it.id) }
        else -> null
    }
}
