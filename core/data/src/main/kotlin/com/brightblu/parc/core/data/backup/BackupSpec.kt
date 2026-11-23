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
 *
 * T402: a [secret] store holds a credential (the DeepL key). It is written only into an encrypted
 * backup and is left out of every plain-text one.
 */
data class BackupStoreSpec(val id: String, val keys: List<BackupKey>, val secret: Boolean = false) {
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
 * Deliberately NOT listed: Comms history (it lives in its own `comms-history.db` and is never in a
 * backup), diagnostics and scene
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
    const val Reader = "reader"

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
        // T259: the aliases the user gave apps (stable app id to one line of text).
        listOf(b("recents_enabled"), t("web_engine", 64), b("source_settings"), b("source_files"), b("source_events"),
            t("aliases", 32_768)),
    )
    const val FocusModesKey = "focus_modes"
    // Identity, policies, schedules, T108/T112 friction options (including delay steps and unlimited passes), accountability secret and pass ledger live in this document.
    val focus = BackupStoreSpec(Focus, listOf(t(FocusModesKey, BackupKey.DocumentMaxLength)))
    val iconPack = BackupStoreSpec(IconPack, listOf(t("selected_pack", 256)))
    val statsAlerts = BackupStoreSpec(StatsAlerts, listOf(t("stats_alert_thresholds", 8_192)))
    val statsAlertUi = BackupStoreSpec(StatsAlertUi, listOf(b("enabled")))
    val hub = BackupStoreSpec(
        Hub,
        listOf(t("config_v1", BackupKey.DocumentMaxLength), t("last_page", 64), b("header_time"), t("hub_size", 16), t("clock_options", 256),
            // T185: the remembered period of the Network and Apps pages (a LedgerPeriod id each).
            t("period_network", 16), t("period_apps", 16),
            // T187: the Steps page's period, and its stride length and daily goal ("75,8000"; goal 0 is off).
            t("period_steps", 16), t("steps_options", 32),
            // T188: the Rhythm page's waking hours ("6,24").
            t("rhythm_options", 16),
            // T189: the Sky page's hand-typed place ("38.7200,-9.1400"; empty is none).
            t("sky_place", 32),
            // T190: the Year page's countdowns (one line each: "2026-12-23|1|Exam") and what colours its dots.
            t("year_countdowns", 8_192), t("year_metric", 16), t("year_colour", 16),
            // T192: whether Today leads with a quote on heavy days.
            t("today_gentle_lead", 32),
            // T200: the hidden arcade: unlocked game ids ("orbit"), best scores ("orbit:12") and the day the earned-unlock watch began.
            // A restore keeps what was unlocked; nothing here is shown anywhere while the list is empty.
            t("arcade_games", 128), t("arcade_best", 256), t("arcade_since", 16)),
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
            // T245: Lock layout. The layout it protects travels in the layout section.
            b("lock_layout"),
            // T261: Left hand (drawing only; the layout itself is unchanged).
            b("left_hand"),
            // T98: unlock timeout and hide-name. The folder lock flag itself travels in the layout section.
            t("lock_timeout", 32), b("lock_hide_name"), i("arcs_shown"), i("icons_per_arc"),
            // T312: per-arc "Maximum apps" ("A2=12;A4=8"; absent = Auto).
            t("arc_maximums", 256),
            // T100: Agenda page. Days ahead and the calendars left out (by display name, not by id).
            t("agenda_days", 32), t("agenda_excluded_calendars", 4_096),
        ),
    )

    // T113: local plain-text notes, including pin and checklist state.
    val notes = BackupStoreSpec("notes", listOf(t("notes_v1", com.brightblu.parc.model.Metrics.notesMaxChars)))

    // T185: the day ledger (data and screen time per day, per-app month sums). One JSON document, kept
    // forward-compatible; a restore replaces it and is read on the next refresh.
    val ledger = BackupStoreSpec("day_ledger", listOf(t("ledger_v1", 1_000_000)))

    // T191: personal quotes are separate from the immutable starter asset; visibility and order travel too.
    val quotes = BackupStoreSpec("quotes", listOf(t("own_v1", 1_000_000), t("hidden_v1", 131_072), t("order_v1", 131_072)))

    // M13 review (T184): the saved Weather places only (names and rounded coordinates). Cached forecasts are not backed up.
    // Only the weather build registers a store for it; other builds ignore the section.
    val weather = BackupStoreSpec("weather", listOf(t("places", 16_384)))

    // T205: selected book locations and reading positions. T236: bookmarks and highlights live
    // beside the list, so one library section carries both. Thumbnails and URI grants do not travel.
    val library = BackupStoreSpec("library", listOf(
        t(com.brightblu.parc.core.data.library.LibraryStore.KeyName,
            com.brightblu.parc.core.data.library.LibraryJson.MaxChars),
        t(com.brightblu.parc.core.data.library.LibraryStore.MarksKeyName,
            com.brightblu.parc.core.data.library.ReaderMarksLimits.MaxChars),
        // T251: the user's own shelf names and order. Book membership and finished marks are fields
        // inside books_v1, so one library section still carries the whole shelf state.
        t(com.brightblu.parc.core.data.library.LibraryStore.ShelvesKeyName,
            com.brightblu.parc.core.data.library.LibraryShelfLimits.MaxChars),
    ))

    // T207: the reader's Aa choices, one set for every book. Reading positions travel with the library.
    val reader = BackupStoreSpec(Reader, listOf(t(
        com.brightblu.parc.core.data.library.ReaderSettingsStore.KeyName,
        com.brightblu.parc.core.data.library.ReaderSettingsJson.MaxChars,
    )))

    val all: List<BackupStoreSpec> =
        listOf(settings, search, focus, iconPack, statsAlerts, statsAlertUi, hub, gestures, look, feel, uiPrefs, wallpaperImage, notes, ledger, quotes, weather, library, reader)

    private val byId: Map<String, BackupStoreSpec> = all.associateBy { it.id }
    fun byId(id: String): BackupStoreSpec? = byId[id]
}
