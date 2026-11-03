package com.brightblu.parc

import com.brightblu.parc.core.data.HapticsLevel
import com.brightblu.parc.core.data.skins.WallpaperLook
import com.brightblu.parc.core.data.skins.WallpaperMode
import com.brightblu.parc.model.ArcFeel
import com.brightblu.parc.model.Bounce
import com.brightblu.parc.model.ClockStyle
import com.brightblu.parc.model.HubAppearance
import com.brightblu.parc.model.HubColour
import com.brightblu.parc.model.HubOpacity
import com.brightblu.parc.model.IconShape
import com.brightblu.parc.model.LayoutCounts
import com.brightblu.parc.model.LookRules
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Magnify
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SpinLimit
import com.brightblu.parc.model.WallpaperStyle
import com.brightblu.parc.ring.FeelPrefs
import com.brightblu.parc.ring.HapticPreset
import com.brightblu.parc.ring.SoundTheme

/*
 * T175 (SETTINGS-REWORK design 1 and 2): every setting is defined ONCE, here. The Settings pages, the search inside
 * Settings, the Parc search entries, "what Home draws" and the wiring tests are all generated from [SettingsRegistry.all].
 * Values are read from and written to one pure [SettingsSnapshot]; the Settings host persists a changed snapshot to the
 * stores ([SettingsWriter]), so the same write code runs in the app, the tests and the emulator audit.
 */

/** T177: the seven Settings pages, with the same names in Settings, search and the docs. */
internal enum class SettingsHome(val title: String, val summary: String, val icon: String, val intro: String) {
    Look("Look", "Skin, wallpaper, icons and clock", "◐",
        "How Parc looks: the skin, what sits behind it, and the icons and clock."),
    Hubs("Hubs", "Colour, opacity and glass of both hub cards", "▣",
        "The two cards on Home: the arc hub around your apps and the stats hub above it."),
    Layout("Layout", "Arcs, icons per arc, hub size, folders, hidden apps", "◉",
        "Where things go on Home: the arcs, how many icons they hold, and your folders."),
    Gestures("Gestures and feel", "Swipes, hub taps, vibration and sound", "↟",
        "What your thumb does: swipes, hub taps, and how Parc feels when you touch it."),
    StatsPages("Stats hub pages", "Which pages the stats hub shows", "▥",
        "The pages of the stats hub: which ones show, their order, and what each one shows."),
    SearchNotifications("Search and notifications", "Search sources, badges, messages, focus", "⌕",
        "What search finds, how unread messages show, and Focus modes."),
    BackupAbout("Backup and about", "Backup, version, permissions, help", "↥",
        "Save or restore your setup, see which build is running, and the phone access Parc uses."),
}

/** What a row is on screen. Every value kind stores an index into [SettingDef.options]. */
internal enum class SettingControl { Switch, Choice, Slider, Colour, Page, Action, Custom }

/**
 * What changing the value does. [Visual] changes what Home draws (checked by [HomeDraw] in tests and by a screenshot in
 * the emulator audit), [Logic] changes behaviour (checked through the saved value), [None] opens a page or runs an action.
 */
internal enum class SettingEffect { Visual, Logic, None }

/**
 * Everything the Settings pages read, as one immutable value. Context that is not itself a setting (the skin list, the
 * largest icons-per-arc the screen allows, summaries of editor pages) rides along so a row can label itself.
 */
internal data class SettingsSnapshot(
    val skins: List<Skin> = listOf(Skin.Midnight),
    val skinId: String = skins.first().id,
    val look: LookSettings = LookSettings(wallpaper = WallpaperStyle.System),
    val hub: HubAppearance = HubAppearance(),
    val counts: LayoutCounts = LayoutCounts(),
    val maxIconsPerArc: Int = 7,
    val aligned: Boolean = false,
    val joystick: Boolean = true,
    val hubDoubleTap: Boolean = true,
    val gestures: GestureConfig = GestureConfig(),
    val haptics: HapticsLevel = HapticsLevel.Light,
    val feel: FeelPrefs = FeelPrefs(),
    val arcFeel: ArcFeel = ArcFeel(),
    val clockVisible: Boolean = true,
    /** Installed icon packs, package name to label. */
    val iconPacks: List<Pair<String, String>> = emptyList(),
    val iconPack: String? = null,
    val statsVisible: Boolean = true,
    val hubSize: HubSize = HubSize.Standard,
    val searchPhoneSettings: Boolean = true,
    val searchFiles: Boolean = true,
    val searchEvents: Boolean = true,
    val fileSearchAvailable: Boolean = true,
    val badges: Boolean = true,
    val badgesInAllApps: Boolean = false,
    val menuNotifications: Boolean = true,
    val menuNotificationText: Boolean = true,
    val markWorkApps: Boolean = true,
    val notificationAccess: Boolean = false,
    val wallpaperSkinSupported: Boolean = false,
    val wallpaperMode: WallpaperMode = WallpaperMode.System,
    val wallpaperLook: WallpaperLook = WallpaperLook.Midnight,
    val focusSummary: String = "No modes",
    val lockedFoldersSummary: String = "",
    val agendaSummary: String = "",
    val swipeUpLabel: String = "All apps",
    val hubLongPressLabel: String = "Edit home",
) {
    val skin: Skin get() = skins.firstOrNull { it.id == skinId } ?: skins.first()
    val glass: Boolean get() = hub.glassEnabled(skin)
    val hasWallpaper: Boolean get() = look.wallpaper == WallpaperStyle.System || look.wallpaper == WallpaperStyle.Image
}

/**
 * One setting. [read] returns the index into [options] of the current value; [write] returns the snapshot with that
 * index chosen. Page and action rows have neither; they [opens] a page or are handled by the host by [id].
 * [id] is stable: it is the old `SettingsRowId` name where one existed, so links and evidence keep working. Backups never
 * used these ids: they store the values under the stores' own keys, which this rework does not change.
 */
internal class SettingDef(
    val id: String,
    val title: String,
    val help: String,
    val page: SettingsHome,
    /** The block heading on the page; rows of one group are drawn in one card. */
    val group: String,
    val control: SettingControl,
    val effect: SettingEffect,
    val words: List<String> = emptyList(),
    val options: (SettingsSnapshot) -> List<String> = { emptyList() },
    val read: ((SettingsSnapshot) -> Int)? = null,
    val write: ((SettingsSnapshot, Int) -> SettingsSnapshot)? = null,
    /** Rule: a row may be hidden only while its value cannot change the screen (tested). */
    val shown: (SettingsSnapshot) -> Boolean = { true },
    val opens: SettingsPage? = null,
    val detail: ((SettingsSnapshot) -> String)? = null,
) {
    val hasValue: Boolean get() = read != null && write != null
    /** The title with its block where the title alone is shared ("Colour" becomes "Arc hub colour"), for search results. */
    val fullTitle: String get() = if (title == "Colour" || title == "Opacity") "$group ${title.lowercase()}" else title
    fun value(s: SettingsSnapshot): String = detail?.invoke(s)
        ?: read?.let { r -> options(s).getOrNull(r(s)) }.orEmpty()
}

private val OffOn = listOf("Off", "On")
private fun Boolean.index() = if (this) 1 else 0

internal object SettingsRegistry {
    private fun switch(id: String, title: String, help: String, page: SettingsHome, group: String, effect: SettingEffect,
        words: List<String> = emptyList(), shown: (SettingsSnapshot) -> Boolean = { true },
        get: (SettingsSnapshot) -> Boolean, set: (SettingsSnapshot, Boolean) -> SettingsSnapshot) =
        SettingDef(id, title, help, page, group, SettingControl.Switch, effect, words, { OffOn }, { get(it).index() },
            { s, i -> set(s, i == 1) }, shown)

    private fun page(id: String, title: String, help: String, page: SettingsHome, group: String, opens: SettingsPage,
        words: List<String> = emptyList(), shown: (SettingsSnapshot) -> Boolean = { true }, detail: ((SettingsSnapshot) -> String)? = null) =
        SettingDef(id, title, help, page, group, SettingControl.Page, SettingEffect.None, words, shown = shown, opens = opens, detail = detail)

    private fun action(id: String, title: String, help: String, page: SettingsHome, group: String,
        words: List<String> = emptyList(), detail: ((SettingsSnapshot) -> String)? = null) =
        SettingDef(id, title, help, page, group, SettingControl.Action, SettingEffect.None, words, detail = detail)

    private fun <T> choice(id: String, title: String, help: String, page: SettingsHome, group: String, effect: SettingEffect,
        values: (SettingsSnapshot) -> List<T>, label: (T) -> String, get: (SettingsSnapshot) -> T,
        set: (SettingsSnapshot, T) -> SettingsSnapshot, control: SettingControl = SettingControl.Choice,
        words: List<String> = emptyList(), shown: (SettingsSnapshot) -> Boolean = { true }) =
        SettingDef(id, title, help, page, group, control, effect, words, { s -> values(s).map(label) },
            { s -> values(s).indexOf(get(s)).coerceAtLeast(0) }, { s, i -> set(s, values(s)[i.coerceIn(values(s).indices)]) }, shown)

    private fun hubOpacityLabel(p: Int) = "$p %"

    val all: List<SettingDef> = listOf(
        // ---------------- Look ----------------
        SettingDef("Appearance", "Skin", "The colours and shapes of Parc. Swipe the cards and tap one to try it",
            SettingsHome.Look, "Skin", SettingControl.Custom, SettingEffect.Visual, listOf("theme", "colours", "style"),
            options = { s -> s.skins.map { it.name } }, read = { s -> s.skins.indexOfFirst { it.id == s.skinId }.coerceAtLeast(0) },
            write = { s, i -> s.copy(skinId = s.skins[i.coerceIn(s.skins.indices)].id) }),
        page("SkinStudio", "Skin editor", "Make your own skin, import or export skin files", SettingsHome.Look, "Skin",
            SettingsPage.SkinStudio, listOf("custom skin", "import", "export")),
        choice("WallpaperSkinMode", "Wallpaper skin colours", "Dark or light colours taken from your wallpaper",
            SettingsHome.Look, "Skin", SettingEffect.Visual, { WallpaperMode.entries }, { it.label }, { it.wallpaperMode },
            { s, v -> s.copy(wallpaperMode = v) }, shown = { it.wallpaperSkinSupported && it.skinId == "wallpaper" }),
        choice("WallpaperSkinLook", "Wallpaper skin based on", "Which skin's frames and shapes the Wallpaper skin uses",
            SettingsHome.Look, "Skin", SettingEffect.Visual, { WallpaperLook.entries }, { it.label }, { it.wallpaperLook },
            { s, v -> s.copy(wallpaperLook = v) }, shown = { it.wallpaperSkinSupported && it.skinId == "wallpaper" }),
        SettingDef("WallpaperImage", "What sits behind Parc", "Your phone's wallpaper, a picture you choose, or just the skin's colour",
            SettingsHome.Look, "Wallpaper", SettingControl.Custom, SettingEffect.Visual, listOf("wallpaper", "background", "picture", "photo", "image"),
            options = { WallpaperChoices.map(::wallpaperChoiceLabel) }, read = { s -> wallpaperChoiceIndex(s.look.wallpaper) },
            write = { s, i -> s.copy(look = s.look.copy(wallpaper = WallpaperChoices[i.coerceIn(WallpaperChoices.indices)])) }),
        choice("WallpaperDim", "Dim", "Darkens the wallpaper or picture so icons stand out. Glass cards darken with it",
            SettingsHome.Look, "Wallpaper", SettingEffect.Visual, { LookRules.DimSteps }, { "$it %" }, { it.look.wallpaperDimPercent },
            { s, v -> s.copy(look = s.look.copy(wallpaperDimPercent = v)) }, SettingControl.Slider, listOf("darken", "dark", "wallpaper"),
            shown = { it.hasWallpaper }),
        switch("WallpaperReadability", "Keep text readable", "Shades the picture behind text and keeps the hub cards readable",
            SettingsHome.Look, "Wallpaper", SettingEffect.Visual, listOf("readability", "scrim", "contrast", "shade"), shown = { it.hasWallpaper },
            get = { it.look.wallpaperReadability }) { s, on ->
            s.copy(look = s.look.copy(keepTextReadable = on, wallpaperReadability = on, imageReadabilityScrim = on))
        },
        choice("IconShape", "Icon shape", "The outline every app icon is cut to", SettingsHome.Look, "Icons and clock",
            SettingEffect.Visual, { IconShape.entries }, LookRules::iconShapeLabel, { it.look.iconShape },
            { s, v -> s.copy(look = s.look.copy(iconShape = v)) }, words = listOf("icons", "round", "square")),
        choice("IconSize", "Icon size", "Larger icons are easier to hit; arcs keep their spacing", SettingsHome.Look, "Icons and clock",
            SettingEffect.Visual, { LookRules.IconSizeSteps }, { "$it dp" }, { it.look.iconSizeDp },
            { s, v -> s.copy(look = s.look.copy(iconSizeDp = v)) }, SettingControl.Slider, listOf("icons", "bigger", "smaller")),
        choice("IconPack", "Icon pack", "Use icons from an installed icon pack", SettingsHome.Look, "Icons and clock",
            SettingEffect.Visual, { s -> listOf<String?>(null) + s.iconPacks.map { it.first } },
            { pkg -> pkg ?: "None" }, { it.iconPack }, { s, v -> s.copy(iconPack = v) }, words = listOf("icons", "theme"),
            shown = { it.iconPacks.isNotEmpty() || it.iconPack != null }),
        switch("ClockVisible", "Show clock", "Show the time on the Clock hub page", SettingsHome.Look, "Icons and clock",
            SettingEffect.Visual, listOf("time"), get = { it.clockVisible }) { s, on -> s.copy(clockVisible = on) },
        choice("ClockStyle", "Clock style", "How the time is drawn", SettingsHome.Look, "Icons and clock", SettingEffect.Visual,
            { ClockStyle.entries }, LookRules::clockStyleLabel, { it.look.clockStyle }, { s, v -> s.copy(look = s.look.copy(clockStyle = v)) },
            words = listOf("time", "font"), shown = { it.clockVisible }),
        // T181 (audit): these two lines belong to the small clock Home shows while the stats hub is off. The stats hub's Clock
        // page always shows the date and the next alarm, so with the stats hub on the rows changed nothing and are not shown.
        switch("ShowDate", "Date line", "Today's date under the small clock shown when the stats hub is off", SettingsHome.Look,
            "Icons and clock", SettingEffect.Visual, listOf("date", "clock"), shown = { it.clockVisible && !it.statsVisible },
            get = { it.look.showDate }) { s, on -> s.copy(look = s.look.copy(showDate = on)) },
        switch("ShowAlarm", "Next alarm", "Your next alarm under the small clock shown when the stats hub is off", SettingsHome.Look,
            "Icons and clock", SettingEffect.Visual, listOf("alarm", "clock"), shown = { it.clockVisible && !it.statsVisible },
            get = { it.look.showAlarm }) { s, on -> s.copy(look = s.look.copy(showAlarm = on)) },

        // ---------------- Hubs ----------------
        switch("GlassEffect", "Glass effect", "Both hub cards become frosted glass over your wallpaper or picture",
            SettingsHome.Hubs, "Glass, both hubs", SettingEffect.Visual, listOf("frosted", "blur", "transparent"),
            get = { it.glass }) { s, on -> s.copy(hub = s.hub.copy(glass = on)) },
        switch("GlassRefraction", "Glass refraction", "Bends the picture at the glass card edge, like a thick rim",
            SettingsHome.Hubs, "Glass, both hubs", SettingEffect.Visual, listOf("lens", "edge", "glass"), shown = { it.glass },
            get = { it.hub.refraction }) { s, on -> s.copy(hub = s.hub.copy(refraction = on)) },
        choice("ArcHubColour", "Colour", "The colour of the arc hub card", SettingsHome.Hubs, "Arc hub", SettingEffect.Visual,
            { HubColour.entries }, { it.label }, { it.hub.arc }, { s, v -> s.copy(hub = s.hub.copy(arc = v)) }, SettingControl.Colour,
            listOf("arc hub colour", "card colour")),
        choice("ArcHubOpacity", "Opacity", "How solid the arc hub card is. Lower lets more of the wallpaper show",
            SettingsHome.Hubs, "Arc hub", SettingEffect.Visual, { s -> HubOpacity.stepsFor(s.glass) }, ::hubOpacityLabel,
            { s -> s.hub.arcOpacityPercent(glass = s.glass) }, { s, v -> s.copy(hub = s.hub.copy(arcOpacity = v)) },
            SettingControl.Slider, listOf("arc hub opacity", "transparent", "see-through", "panel")),
        choice("StatsHubColour", "Colour", "The colour of the stats hub card", SettingsHome.Hubs, "Stats hub", SettingEffect.Visual,
            { HubColour.entries }, { it.label }, { it.hub.stats }, { s, v -> s.copy(hub = s.hub.copy(stats = v)) }, SettingControl.Colour,
            listOf("stats hub colour", "card colour"), shown = { it.statsVisible }),
        choice("StatsHubOpacity", "Opacity", "How solid the stats hub card is. Lower lets more of the wallpaper show",
            SettingsHome.Hubs, "Stats hub", SettingEffect.Visual, { s -> HubOpacity.stepsFor(s.glass) }, ::hubOpacityLabel,
            { s -> s.hub.statsOpacityPercent(glass = s.glass) }, { s, v -> s.copy(hub = s.hub.copy(statsOpacity = v)) },
            SettingControl.Slider, listOf("stats hub opacity", "transparent", "see-through", "panel"), shown = { it.statsVisible }),

        // ---------------- Layout ----------------
        choice("ArcsShown", "Arcs shown", "How many rings of apps and folders. Items on a removed arc move to the next ring",
            SettingsHome.Layout, "Arcs", SettingEffect.Visual, { listOf(3, 4) }, { it.toString() }, { it.counts.arcs },
            { s, v -> s.copy(counts = s.counts.copy(arcs = v)) }, words = listOf("rings", "arcs")),
        choice("IconsPerArc", "Icons per arc", "Auto fits as many as the screen allows. Turn an arc to reach the rest",
            SettingsHome.Layout, "Arcs", SettingEffect.Visual, { s -> listOf(0) + (3..s.maxIconsPerArc.coerceAtLeast(3)).toList() },
            { if (it == 0) "Auto" else it.toString() }, { it.counts.icons }, { s, v -> s.copy(counts = s.counts.copy(icons = v)) },
            words = listOf("slots", "apps per ring")),
        choice("ArcLayout", "Arc layout", "Line up icons across arcs or stagger them between arcs", SettingsHome.Layout, "Arcs",
            SettingEffect.Visual, { listOf(false, true) }, { if (it) "Aligned" else "Staggered" }, { it.aligned },
            { s, v -> s.copy(aligned = v) }, words = listOf("align", "stagger")),
        choice("HubSize", "Stats hub size", "Compact is short. Standard and Tall are the same height; Tall shows every tile of a page", SettingsHome.Layout, "Arcs",
            SettingEffect.Visual, { HubSize.entries }, { it.name }, { it.hubSize }, { s, v -> s.copy(hubSize = v) },
            words = listOf("hub height", "tall", "compact"), shown = { it.statsVisible }),
        page("MainApps", "Main apps", "Choose and order the four inner-arc apps", SettingsHome.Layout, "Apps", SettingsPage.MainApps,
            listOf("pinned", "favourites")),
        page("Rotation", "Arc rotation", "How each arc is turned and positioned", SettingsHome.Layout, "Apps", SettingsPage.Rotation,
            listOf("turn", "rotate")),
        page("Folders", "Folders", "Create, rename and organise folders", SettingsHome.Layout, "Apps", SettingsPage.Folders),
        page("LockedFolders", "Locked folders", "Hide the apps in chosen folders until you confirm it is you",
            SettingsHome.Layout, "Apps", SettingsPage.LockedFolders, listOf("private", "fingerprint", "lock"), detail = { it.lockedFoldersSummary }),
        page("HiddenApps", "Hidden apps", "Hide apps or bring them back", SettingsHome.Layout, "Apps", SettingsPage.Hidden, listOf("hide")),

        // ---------------- Gestures and feel ----------------
        page("SwipeUp", "Swipe up", "Choose what a swipe up on Home opens", SettingsHome.Gestures, "Gestures", SettingsPage.SwipeUp,
            listOf("gesture"), detail = { it.swipeUpLabel }),
        page("HubLongPress", "Hub long-press", "Choose what holding the hub opens", SettingsHome.Gestures, "Gestures",
            SettingsPage.HubLongPress, listOf("gesture", "hold"), detail = { it.hubLongPressLabel }),
        switch("HubDoubleTap", "Hub double-tap", "Open the last app with two hub taps", SettingsHome.Gestures, "Gestures",
            SettingEffect.Logic, listOf("last app"), get = { it.hubDoubleTap }) { s, on -> s.copy(hubDoubleTap = on) },
        switch("Joystick", "Joystick aim", "Drag from the hub to aim at an app", SettingsHome.Gestures, "Gestures",
            SettingEffect.Logic, listOf("aim", "drag"), get = { it.joystick }) { s, on -> s.copy(joystick = on) },
        switch("Haptics", "Vibration", "Vibrate for taps and gestures", SettingsHome.Gestures, "Vibration and sound",
            SettingEffect.Logic, listOf("haptics", "vibrate"), get = { it.haptics != HapticsLevel.Off }) { s, on ->
            s.copy(haptics = if (on) HapticsLevel.Light else HapticsLevel.Off)
        },
        choice("HapticPreset", "Vibration style", "How the vibration feels", SettingsHome.Gestures, "Vibration and sound",
            SettingEffect.Logic, { HapticPreset.entries.filter { it != HapticPreset.Off } }, { it.label }, { it.feel.haptic },
            { s, v -> s.copy(feel = s.feel.copy(haptic = v)) }, words = listOf("haptics"), shown = { it.haptics != HapticsLevel.Off }),
        choice("SoundTheme", "Click sounds", "A short sound for taps. Silent when the phone is on silent or vibrate",
            SettingsHome.Gestures, "Vibration and sound", SettingEffect.Logic, { SoundTheme.entries }, { it.label }, { it.feel.sound },
            { s, v -> s.copy(feel = s.feel.copy(sound = v)) }, words = listOf("sound", "click", "tick")),
        choice("SoundVolume", "Sound volume", "How loud the click sounds are", SettingsHome.Gestures, "Vibration and sound",
            SettingEffect.Logic, { FeelUi.VolumeSteps }, { "$it %" }, { it.feel.volumePercent },
            { s, v -> s.copy(feel = s.feel.copy(volumePercent = v)) }, SettingControl.Slider, shown = { it.feel.sound != SoundTheme.Off }),
        choice("SpinLimit", "Spin limit", "How far one flick can turn the arc", SettingsHome.Gestures, "Arc feel", SettingEffect.Logic,
            { SpinLimit.entries }, { listOf("Tight", "Balanced", "Free")[it.ordinal] }, { it.arcFeel.spinLimit },
            { s, v -> s.copy(arcFeel = s.arcFeel.copy(spinLimit = v)) }, words = listOf("arc", "flick")),
        choice("Bounce", "Bounce", "How much the arc bounces when it lands", SettingsHome.Gestures, "Arc feel", SettingEffect.Logic,
            { Bounce.entries }, { listOf("Calm", "Balanced", "Playful")[it.ordinal] }, { it.arcFeel.bounce },
            { s, v -> s.copy(arcFeel = s.arcFeel.copy(bounce = v)) }, words = listOf("arc", "spring")),
        choice("Magnify", "Magnify under thumb", "How big the slot under your thumb grows", SettingsHome.Gestures, "Arc feel",
            SettingEffect.Logic, { Magnify.entries }, { listOf("Off", "Subtle", "Dock-like")[it.ordinal] }, { it.arcFeel.magnify },
            { s, v -> s.copy(arcFeel = s.arcFeel.copy(magnify = v)) }, words = listOf("arc", "zoom")),

        // ---------------- Stats hub pages ----------------
        switch("StatsVisible", "Show stats hub", "Show the stats hub card above the arcs", SettingsHome.StatsPages, "Stats hub",
            SettingEffect.Visual, listOf("stats", "panel", "hide hub"), get = { it.statsVisible }) { s, on -> s.copy(statsVisible = on) },
        page("HubPages", "Hub pages", "Add, remove or move pages; each page has its own options", SettingsHome.StatsPages, "Pages",
            SettingsPage.HubPages, listOf("pages", "order", "24-hour", "seconds", "time zone"), shown = { it.statsVisible }),
        page("Agenda", "Agenda", "Choose calendars and how many days to show", SettingsHome.StatsPages, "Pages", SettingsPage.Agenda,
            listOf("calendar", "events"), shown = { it.statsVisible }, detail = { it.agendaSummary }),
        page("StatsAlerts", "Stats alerts and history", "Set warning levels and show history graphs", SettingsHome.StatsPages, "Pages",
            SettingsPage.StatsAlerts, listOf("battery", "temperature", "warning", "graph"), shown = { it.statsVisible }),

        // ---------------- Search and notifications ----------------
        page("Search", "Search preferences", "Web engine and recent searches", SettingsHome.SearchNotifications, "Search",
            SettingsPage.Search, listOf("web", "google", "recent")),
        switch("SearchPhoneSettings", "Search phone settings", "Let search open phone settings pages such as Wi-Fi or Battery",
            SettingsHome.SearchNotifications, "Search", SettingEffect.Logic, get = { it.searchPhoneSettings }) { s, on -> s.copy(searchPhoneSettings = on) },
        switch("SearchFiles", "Search files", "Let search find photos, videos and music by name. Needs permission",
            SettingsHome.SearchNotifications, "Search", SettingEffect.Logic, listOf("photos", "music"), shown = { it.fileSearchAvailable },
            get = { it.searchFiles }) { s, on -> s.copy(searchFiles = on) },
        switch("SearchEvents", "Search calendar events", "Let search find calendar events in the next 60 days. Needs calendar permission",
            SettingsHome.SearchNotifications, "Search", SettingEffect.Logic, listOf("calendar"), get = { it.searchEvents }) { s, on -> s.copy(searchEvents = on) },
        switch("NotificationBadges", "Notification badges", "Mark apps that have unread notifications", SettingsHome.SearchNotifications,
            "Notifications", SettingEffect.Visual, listOf("dots", "unread"), get = { it.badges }) { s, on -> s.copy(badges = on) },
        switch("BadgesInAllApps", "Badges in All apps", "Also show unread marks in All apps", SettingsHome.SearchNotifications,
            "Notifications", SettingEffect.Logic, listOf("dots"), shown = { it.badges }, get = { it.badgesInAllApps }) { s, on -> s.copy(badgesInAllApps = on) },
        switch("MenuNotifications", "Notifications in menus", "Show an app's current notifications when you hold its icon",
            SettingsHome.SearchNotifications, "Notifications", SettingEffect.Logic, listOf("long-press"), get = { it.menuNotifications }) { s, on ->
            s.copy(menuNotifications = on)
        },
        switch("MenuNotificationText", "Show message text", "Show the message line under each title; off shows titles only",
            SettingsHome.SearchNotifications, "Notifications", SettingEffect.Logic, shown = { it.menuNotifications },
            get = { it.menuNotificationText }) { s, on -> s.copy(menuNotificationText = on) },
        action("NotificationAccess", "Notification access", "Allow access for badges, Comms and notifications in menus",
            SettingsHome.SearchNotifications, "Notifications", listOf("permission"), detail = { if (it.notificationAccess) "Allowed" else "Not allowed" }),
        page("Comms", "Messages and quick replies", "Comms apps, message history and quick replies", SettingsHome.SearchNotifications,
            "Notifications", SettingsPage.Comms, listOf("whatsapp", "telegram", "reply")),
        page("Focus", "Focus modes", "Choose a mode, its apps, times and how strict to be", SettingsHome.SearchNotifications, "Focus",
            SettingsPage.Focus, listOf("work", "sleep", "do not disturb"), detail = { it.focusSummary }),
        switch("MarkWorkApps", "Mark work apps", "Show a briefcase on work-profile apps", SettingsHome.SearchNotifications, "Focus",
            SettingEffect.Visual, listOf("work profile", "briefcase"), get = { it.markWorkApps }) { s, on -> s.copy(markWorkApps = on) },

        // ---------------- Backup and about ----------------
        page("Backup", "Backup and restore", "Export or restore your layout and settings in one file", SettingsHome.BackupAbout, "Backup",
            SettingsPage.Backup, listOf("export", "import", "restore", "save")),
        switch("WallpaperImageBackup", "Include wallpaper in backups", "Add the private picture to exported backups, maximum 2 MB",
            SettingsHome.BackupAbout, "Backup", SettingEffect.Logic, listOf("picture"), get = { it.look.includeWallpaperInBackups }) { s, on ->
            s.copy(look = s.look.copy(includeWallpaperInBackups = on))
        },
        SettingDef("About", "This build", "Version, version code, build time and code commit", SettingsHome.BackupAbout, "About",
            SettingControl.Custom, SettingEffect.None, listOf("version", "build", "about", "stamp")),
        page("WhatsNew", "What's new", "What changed in this version", SettingsHome.BackupAbout, "About", SettingsPage.WhatsNew,
            listOf("changelog", "release notes")),
        page("VersionHistory", "Version history", "Every version and what it changed", SettingsHome.BackupAbout, "About",
            SettingsPage.VersionHistory, listOf("changelog")),
        page("Permissions", "Permissions", "See which optional phone access is on and turn it on", SettingsHome.BackupAbout,
            "Phone access", SettingsPage.Permissions, listOf("privacy", "usage", "contacts", "calendar")),
        action("RerunOnboarding", "Show setup again", "Review home app, usage, contacts and notification access",
            SettingsHome.BackupAbout, "Phone access", listOf("onboarding", "setup")),
        action("AndroidSettings", "Android settings", "Open the phone's own settings app", SettingsHome.BackupAbout, "Phone access"),
        page("Diagnostics", "Diagnostics", "Scene details and the latest error, for bug reports", SettingsHome.BackupAbout, "Help",
            SettingsPage.Diagnostics, listOf("error", "crash", "debug")),
    )

    val byId: Map<String, SettingDef> = all.associateBy { it.id }

    fun on(page: SettingsHome): List<SettingDef> = all.filter { it.page == page }

    /** The rows a page shows for this snapshot, in registry order. */
    fun shown(page: SettingsHome, s: SettingsSnapshot): List<SettingDef> = on(page).filter { it.shown(s) }

    /** Search inside Settings: every word must appear in the title, help, page, group or search words. Hidden rows are found too. */
    fun search(query: String): List<SettingDef> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.filter { def ->
            val text = (listOf(def.title, def.help, def.page.title, def.group) + def.words).joinToString(" ")
            words.all { text.contains(it, ignoreCase = true) }
        }
    }

    /** Applies one value change by id; unknown ids and non-value rows leave the snapshot unchanged. */
    fun set(s: SettingsSnapshot, id: String, index: Int): SettingsSnapshot = byId[id]?.write?.invoke(s, index) ?: s
}

internal val WallpaperChoices = listOf(WallpaperStyle.System, WallpaperStyle.Image, WallpaperStyle.Solid)
internal fun wallpaperChoiceIndex(style: WallpaperStyle) = when (style) {
    WallpaperStyle.System -> 0
    WallpaperStyle.Image -> 1
    else -> 2
}

internal fun wallpaperChoiceLabel(style: WallpaperStyle): String = when (style) {
    WallpaperStyle.System -> "My phone’s wallpaper"
    WallpaperStyle.Image -> "A picture I choose"
    else -> "Just the skin’s colour"
}

/** Slider steps for the click sound volume, in percent. */
internal object FeelUi {
    val VolumeSteps = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
}
