package com.brightblu.parc

/**
 * OPUS8 (T133): where a Home-menu row or a Settings search result opens Parc settings. [row]
 * is the registry id of the row to scroll to (T175); null shows the page from its top.
 */
internal data class SettingsTarget(val page: SettingsPage, val row: String? = null)

/** OPUS8 (T133): the small Home menu, anchored where the finger held empty space (dp). */
internal data class HomeMenuModel(val anchorXDp: Float, val anchorYDp: Float)

/** The four rows, in order, with the words the owner approved (UXREVIEW answer 6). */
internal enum class HomeMenuRow(val label: String, val target: SettingsTarget?) {
    EditHome("Edit home", null),
    Look("Wallpaper and look", SettingsTarget(SettingsPage.Look, "WallpaperImage")),
    HubPages("Hub pages", SettingsTarget(SettingsPage.HubPages)),
    Settings("Parc settings", SettingsTarget(SettingsPage.Root)),
}

/** OPUS8 (T133): the edit-mode instruction bar and the empty-spot sheet words, in one place. */
internal object EditModeText {
    const val Instructions = "Drag an app to move it. Tap + to add. Tap Done when finished."
    const val NewFolder = "New folder"
    const val AddApp = "Add app"
    const val Close = "Close"

    /**
     * The empty-spot sheet title. Arcs count from the hub: the last arc shown is the outer arc, the
     * first the inner arc, the ones between are the second and third arcs.
     */
    fun emptySpotTitle(arcIndex: Int, arcsShown: Int): String = "Empty spot on the " + arcName(arcIndex, arcsShown)

    fun arcName(arcIndex: Int, arcsShown: Int): String = when {
        arcsShown > 0 && arcIndex >= arcsShown - 1 -> "outer arc"
        arcIndex <= 0 -> "inner arc"
        arcIndex == 1 -> "second arc"
        else -> "third arc"
    }
}

/** OPUS8 (T133): what the Home key does to the hub. */
internal object HomeKey {
    /** The first page the hub shows (same list and order as the hub itself), or null when none. */
    fun firstHubPage(demo: Boolean, configured: List<String>, allowed: (String) -> Boolean = { true }): String? =
        hubPagesFor(demo, configured, allowed).firstOrNull()
}
