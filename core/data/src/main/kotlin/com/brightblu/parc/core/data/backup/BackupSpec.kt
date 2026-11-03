package com.brightblu.parc.core.data.backup

/** Value shapes a setting key can hold in a backup (T87). */
enum class BackupValueType { Bool, Int, Long, Text, TextSet }

/**
 * One persisted setting that a backup may carry. [maxLength] bounds a Text value (or every entry of
 * a TextSet), so a hostile document cannot push megabytes into a preferences file.
 */
data class BackupKey(
    val name: String,
    val type: BackupValueType,
    val maxLength: Int = DefaultMaxLength,
) {
    companion object {
        const val DefaultMaxLength = 4_096
        const val DocumentMaxLength = 65_536
        const val MaxSetEntries = 256
    }
}

/**
 * The allow-list of keys one store contributes. Only keys named here are ever written to a backup or
 * read back from one; history, diagnostics and learned state are simply not listed (T87). Immutable.
 */
data class BackupStoreSpec(val id: String, val keys: List<BackupKey>) {
    private val byName: Map<String, BackupKey> = keys.associateBy { it.name }
    fun key(name: String): BackupKey? = byName[name]
}

private fun i(name: String) = BackupKey(name, BackupValueType.Int)
private fun b(name: String) = BackupKey(name, BackupValueType.Bool)
private fun t(name: String, max: Int = BackupKey.DefaultMaxLength) = BackupKey(name, BackupValueType.Text, max)

/**
 * Every store whose settings the backup covers, by stable id. The key names are the persisted ones;
 * a drift test in `:app` checks the app-local names against the stores' own constants.
 *
 * Deliberately NOT listed: Comms history and notification text (memory only), diagnostics and scene
 * errors, the usage log and learned slots, the stats history archive, thermal memory, recent
 * searches, the manual focus override, onboarding and seed markers, main-app revisions and the
 * transient UI state in `parc_ui` (mode, rotations, hint flags). Layout, folders, pins, hidden apps
 * and the arc arrangement travel in the layout section instead.
 */
object BackupSpecs {
    const val Settings = "settings"
    const val Search = "search"
    const val Focus = "focus"
    const val IconPack = "icon_pack"
    const val StatsAlerts = "stats_alerts"
    const val StatsAlertUi = "stats_alert_ui"
    const val Hub = "hub"
    const val Gestures = "gestures"
    const val Look = "look"
    const val Feel = "feel"
    const val UiPrefs = "ui_prefs"

    val settings = BackupStoreSpec(
        Settings,
        listOf(
            t("skin_id", 128), t("wallpaper_skin_mode", 32), t("wallpaper_skin_look", 32), t("haptics", 32), b("clock_visible"), b("stats_visible"),
            b("stats_expanded"), b("comms_enabled"),
            BackupKey("comms_apps", BackupValueType.TextSet, 256),
            t("comms_canned_replies", 8_192), b("comms_keep_history"), t("main_apps", 8_192),
        ),
    )
    val search = BackupStoreSpec(
        Search,
        // T102: the three source switches. The one-time permission-row marker is deliberately not backed up.
        listOf(b("recents_enabled"), t("web_engine", 64), b("source_settings"), b("source_files"), b("source_events")),
    )
    const val FocusModesKey = "focus_modes"
    // Identity, policies, schedules, T108/T112 friction options (including delay steps and unlimited passes), accountability secret and pass ledger live in this document.
    val focus = BackupStoreSpec(Focus, listOf(t(FocusModesKey, BackupKey.DocumentMaxLength)))
    val iconPack = BackupStoreSpec(IconPack, listOf(t("selected_pack", 256)))
    val statsAlerts = BackupStoreSpec(StatsAlerts, listOf(t("stats_alert_thresholds", 8_192)))
    val statsAlertUi = BackupStoreSpec(StatsAlertUi, listOf(b("enabled")))
    val hub = BackupStoreSpec(
        Hub,
        listOf(t("config_v1", BackupKey.DocumentMaxLength), t("last_page", 64), b("header_time"), t("hub_size", 16), t("clock_options", 256)),
    )
    val gestures = BackupStoreSpec(Gestures, listOf(t("swipe_up", 256), t("hub_long_press", 256)))
    // look_v1 includes panel opacity and wallpaper readability; older documents use model defaults.
    val look = BackupStoreSpec(Look, listOf(t("look_v1", BackupKey.DocumentMaxLength)))
    val wallpaperImage = BackupStoreSpec("wallpaper_image", listOf(t("jpeg", 2_796_204)))
    val feel = BackupStoreSpec(
        Feel,
        // T147: the arc feel choices (stored ids). A backup made before T147 lacks them and restores the defaults.
        listOf(
            t("haptic_preset", 64), t("sound_theme", 64), BackupKey("sound_volume", BackupValueType.Int),
            t("arc_spin_limit", 32), t("arc_bounce", 32), t("arc_magnify", 32), t("arc_home_arcs", 32),
        ),
    )
    val uiPrefs = BackupStoreSpec(
        UiPrefs,
        listOf(
            b("align_across_arcs"), b("joystick_aim"), b("hub_double_tap"), b("notification_badges"),
            b("badges_in_all_apps"), b("mark_work_apps"), b("menu_notifications"), b("menu_notification_text"),
            // T98: unlock timeout and hide-name. The folder lock flag itself travels in the layout section.
            t("lock_timeout", 32), b("lock_hide_name"), i("arcs_shown"), i("icons_per_arc"),
            // T100: Agenda page. Days ahead and the calendars left out (by display name, not by id).
            t("agenda_days", 32), t("agenda_excluded_calendars", 4_096),
        ),
    )

    // T113: local plain-text notes, including pin and checklist state.
    val notes = BackupStoreSpec("notes", listOf(t("notes_v1", 1_000_000)))

    val all: List<BackupStoreSpec> =
        listOf(settings, search, focus, iconPack, statsAlerts, statsAlertUi, hub, gestures, look, feel, uiPrefs, wallpaperImage, notes)

    private val byId: Map<String, BackupStoreSpec> = all.associateBy { it.id }
    fun byId(id: String): BackupStoreSpec? = byId[id]
}
