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
import com.brightblu.parc.feature.settings.ui.SettingsIcon
import com.brightblu.parc.ring.FeelPrefs
import com.brightblu.parc.ring.HapticPreset
import com.brightblu.parc.ring.SoundTheme

/*
 * T175 (SETTINGS-REWORK design 1 and 2): every setting is defined ONCE, here. The Settings pages, the search inside
 * Settings, the Parc search entries, "what Home draws" and the wiring tests are all generated from [SettingsRegistry.all].
 * Values are read from and written to one pure [SettingsSnapshot]; the Settings host persists a changed snapshot to the
 * stores ([SettingsWriter]), so the same write code runs in the app, the tests and the emulator audit.
 *
 * T220: the pages are now five groups with sub-pages. [SettingsGroup] is the root card, [SettingsPage] is a group
 * sub-page or an editor reached from one. Display titles may change; every [SettingDef.id], hub page id and backup key
 * stays exactly as it was.
 */

/** T220: the five root cards. Each group page lists its three or four sub-page cards. */
internal enum class SettingsGroup(val title: String, val summary: String, val intro: String, val icon: SettingsIcon) {
    Home("Home", "What is on the screen", "The arcs, the apps and folders on them, and what is hidden or locked.", SettingsIcon.Home),
    Appearance("Appearance", "How Parc looks", "The skin, the wallpaper, the icons and clock, and both hub cards.", SettingsIcon.Appearance),
    GesturesSearch("Gestures & search", "What your hands do", "Swipes, hub taps, the way Parc feels, and what search finds.", SettingsIcon.GesturesSearch),
    NotificationsFocus("Notifications & Focus", "What gets through", "Notification access and badges, Messages, and Focus modes.", SettingsIcon.NotificationsFocus),
    DataAbout("Data & about", "What is stored and this build", "Backups, the version, phone access and diagnostics.", SettingsIcon.DataAbout),
}

/** What a row is on screen. Every value kind stores an index into [SettingDef.options]. */
internal enum class SettingControl { Switch, Choice, Slider, Colour, Page, Action, Custom }

/**
 * What changing the value does. [Visual] changes what Home draws (checked by [HomeDraw] in tests and by a screenshot in
 * the emulator audit), [Logic] changes behaviour (checked through the saved value), [None] opens a page or runs an action.
 */
internal enum class SettingEffect { Visual, Logic, None }

/**
 * A group sub-page or an editor reached from one. [parent] is set only for a nested page; a group sub-page returns
 * to its [group] card. [aliases] preserve the old page-name search vocabulary ("Look", "Layout", "Stats hub pages").
 */
internal enum class SettingsPage(
    val group: SettingsGroup?,
    val title: String,
    val summary: String,
    val intro: String,
    val parent: SettingsPage? = null,
    val aliases: List<String> = emptyList(),
) {
    Root(null, "Parc Settings", "", "", null),

    // Home
    HomeArcs(SettingsGroup.Home, "Arcs", "Rings, icons per arc, layout and rotation",
        "How many rings of apps, how many icons each holds, and how they line up.", aliases = listOf("Layout", "Home")),
    HomeApps(SettingsGroup.Home, "Apps & folders", "Main apps, folders and work apps",
        "The apps in the inner arc, the folders on Home, and the work-app mark.", aliases = listOf("Layout", "Home")),
    HiddenLocked(SettingsGroup.Home, "Hidden & locked", "Hide apps and folders, or lock them",
        "Hide is permanent; Focus is temporary; Lock asks for the phone's confirmation.", aliases = listOf("Layout", "Home", "Visibility")),

    // Appearance
    AppearanceSkin(SettingsGroup.Appearance, "Skin", "Colours, shapes and the skin editor",
        "The colours and shapes of Parc, and your own saved skins.", aliases = listOf("Look", "Appearance")),
    AppearanceWallpaper(SettingsGroup.Appearance, "Wallpaper", "What sits behind Parc and how it is shaded",
        "Your phone's wallpaper, a picture you choose, or just the skin's colour.", aliases = listOf("Look", "Appearance")),
    AppearanceIcons(SettingsGroup.Appearance, "Icons & clock", "Icon shape and size, and the clock lines",
        "The outline every icon is cut to, the icon pack, and the time and date lines.", aliases = listOf("Look", "Appearance")),
    Hubs(SettingsGroup.Appearance, "Hubs", "Glass, colour and opacity of both hub cards",
        "The two cards on Home: the arc hub around your apps and the stats hub above it.", aliases = listOf("Look", "Stats hub pages", "Hub pages")),

    // Gestures & search
    Gestures(SettingsGroup.GesturesSearch, "Gestures", "Swipes, hub long-press, double-tap and joystick",
        "What your thumb does: swipes, hub taps, and how the joystick aims.", aliases = listOf("Gestures and feel")),
    Feel(SettingsGroup.GesturesSearch, "Feel", "Vibration, sound and arc feel",
        "How Parc feels when you touch it: vibration, click sounds, spin, bounce and magnification.", aliases = listOf("Gestures and feel")),
    Search(SettingsGroup.GesturesSearch, "Search", "Search sources and phone settings",
        "What search finds, and whether it can open phone settings, files and calendar events.", aliases = listOf("Search and notifications", "Search")),

    SearchPreferences(SettingsGroup.GesturesSearch, "Search preferences", "Web engine and recent searches",
        "The web engine search uses and the recent searches it remembers.", parent = Search, aliases = listOf("Search", "Search and notifications")),

    // Notifications & Focus
    Notifications(SettingsGroup.NotificationsFocus, "Notifications", "Access, badges and notification menus",
        "Which apps may show unread marks, and what a long-press shows.", aliases = listOf("Search and notifications", "Notifications")),
    Messages(SettingsGroup.NotificationsFocus, "Messages", "Live messages, stored history and quick replies",
        "Messages, its on-phone history, the apps that may post, and saved reply text.", aliases = listOf("Search and notifications", "Comms", "Messages")),
    Focus(SettingsGroup.NotificationsFocus, "Focus", "Modes, apps, schedules and strictness",
        "Choose a mode, its apps, times and how strict to be.", aliases = listOf("Search and notifications", "Focus")),

    // Data & about
    DataBackup(SettingsGroup.DataAbout, "Backup", "Save or restore your setup",
        "Export or restore your layout and settings in one file.", aliases = listOf("Backup and about", "Backup")),
    DataBuild(SettingsGroup.DataAbout, "This build", "Version, what's new and version history",
        "Which build is running and what changed in it.", aliases = listOf("Backup and about", "This build", "About")),
    DataPermissions(SettingsGroup.DataAbout, "Permissions", "Optional phone access and setup",
        "See which optional phone access is on, turn it on, or review setup.", aliases = listOf("Backup and about", "Permissions")),
    DataHelp(SettingsGroup.DataAbout, "Help", "Diagnostics for a bug report",
        "Scene details and the latest error, for bug reports.", aliases = listOf("Backup and about", "Help")),

    // Nested pages and editors (parent chains below)
    HubPages(SettingsGroup.Appearance, "Pages & alerts", "Hub pages, their order, size, Agenda and alerts",
        "Every hub page, its visibility and order, plus the two page-level settings.", parent = Hubs,
        aliases = listOf("Stats hub pages", "Hub pages", "Pages")),
    HubOptions(SettingsGroup.Appearance, "Hub page options", "One page's tiles and behaviour",
        "The tiles, order and behaviour of one hub page.", parent = HubPages),
    Agenda(SettingsGroup.Appearance, "Agenda", "Calendars and days ahead",
        "Choose calendars and how many days to show.", parent = HubPages, aliases = listOf("Stats hub pages")),
    StatsAlerts(SettingsGroup.Appearance, "Stats alerts and history", "Warning levels and history graphs",
        "Set warning levels and show history graphs.", parent = HubPages, aliases = listOf("Stats hub pages")),
    SkinStudio(SettingsGroup.Appearance, "Skin editor", "Make, import and export skin files",
        "Make your own skin, import or export skin files.", parent = AppearanceSkin, aliases = listOf("Look")),
    MainApps(SettingsGroup.Home, "Main apps", "Choose and order the four inner-arc apps",
        "Choose and order the four inner-arc apps.", parent = HomeApps, aliases = listOf("Layout")),
    Rotation(SettingsGroup.Home, "Arc rotation", "How each arc is turned and positioned",
        "How each arc is turned and positioned.", parent = HomeArcs, aliases = listOf("Layout")),
    Folders(SettingsGroup.Home, "Folders", "Create, rename and organise folders",
        "Create, rename and organise folders.", parent = HomeApps, aliases = listOf("Layout")),
    Hidden(SettingsGroup.Home, "Hidden apps", "Hide apps or bring them back",
        "Hide apps or bring them back.", parent = HiddenLocked, aliases = listOf("Layout")),
    HiddenFolders(SettingsGroup.Home, "Hidden folders", "Folders that leave Home with their apps",
        "Folders you hide leave Home with their apps.", parent = HiddenLocked, aliases = listOf("Layout")),
    LockedFolders(SettingsGroup.Home, "Locked folders", "Locked until it is you",
        "Hide the apps in chosen folders until you confirm it is you.", parent = HiddenLocked, aliases = listOf("Layout")),
    CommsApps(SettingsGroup.NotificationsFocus, "Messages apps", "Which apps may show messages",
        "Choose which apps can show messages.", parent = Messages, aliases = listOf("Comms")),
    QuickReplies(SettingsGroup.NotificationsFocus, "Quick replies", "Saved reply text",
        "Add or remove saved reply text.", parent = Messages, aliases = listOf("Comms")),
    Backup(SettingsGroup.DataAbout, "Backup and restore", "Export or restore one file",
        "Export or restore your layout and settings in one file.", parent = DataBackup, aliases = listOf("Backup and about")),
    Permissions(SettingsGroup.DataAbout, "Permissions", "Optional phone access",
        "See which optional phone access is on and turn it on.", parent = DataPermissions, aliases = listOf("Backup and about")),
    Onboarding(SettingsGroup.DataAbout, "Show setup again", "Review the first-run steps",
        "Review home app, usage, contacts and notification access.", parent = DataPermissions, aliases = listOf("Backup and about")),
    Diagnostics(SettingsGroup.DataAbout, "Diagnostics", "Scene details and the latest error",
        "Scene details and the latest error, for bug reports.", parent = DataHelp, aliases = listOf("Backup and about")),
    WhatsNew(SettingsGroup.DataAbout, "What's new", "What changed in this version",
        "What changed in this version.", parent = DataBuild, aliases = listOf("Backup and about")),
    VersionHistory(SettingsGroup.DataAbout, "Version history", "Every version and what it changed",
        "Every version and what it changed.", parent = DataBuild, aliases = listOf("Backup and about")),
    SwipeUp(SettingsGroup.GesturesSearch, "Swipe up", "What a swipe up on Home opens",
        "Choose what a swipe up on Home opens.", parent = Gestures, aliases = listOf("Gestures and feel")),
    HubLongPress(SettingsGroup.GesturesSearch, "Hub long-press", "What holding the hub opens",
        "Choose what holding the hub opens.", parent = Gestures, aliases = listOf("Gestures and feel")),
    ;

    /** Group · page, or the full chain for a nested page: Appearance · Hubs · Pages & alerts. */
    val breadcrumb: String
        get() {
            val parents = buildList {
                var page = parent
                while (page != null) { add(page.title); page = page.parent }
            }.asReversed()
            return (listOfNotNull(group?.title) + parents + title).joinToString(" · ")
        }
}

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
    /** T245: the arcs refuse moves, adds and removes while this is on. */
    val layoutLocked: Boolean = false,
    /** T261: the hub and arcs are drawn mirrored. */
    val leftHand: Boolean = false,
    val notificationAccess: Boolean = false,
    val wallpaperSkinSupported: Boolean = false,
    val wallpaperMode: WallpaperMode = WallpaperMode.System,
    val wallpaperLook: WallpaperLook = WallpaperLook.Midnight,
    val focusSummary: String = "No modes",
    val hiddenFoldersSummary: String = "None",
    val lockedFoldersSummary: String = "",
    val agendaSummary: String = "",
    val swipeUpLabel: String = "All apps",
    val hubLongPressLabel: String = "Edit home",
    /** T220: the new Messages rows read the same repository values the page uses. */
    val commsEnabled: Boolean = true,
    val commsRecordHistory: Boolean = true,
    val commsStoredCount: Int = 0,
    val commsStorageLine: String = "",
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
    val page: SettingsPage,
    /** The card heading on the sub-page; rows of one [card] are drawn in one card. */
    val card: String,
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
    /** The title with its card where the title alone is shared ("Colour" becomes "Arc hub colour"), for search results. */
    val fullTitle: String get() = if (title == "Colour" || title == "Opacity") "$card ${title.lowercase()}" else title
    fun value(s: SettingsSnapshot): String = detail?.invoke(s)
        ?: read?.let { r -> options(s).getOrNull(r(s)) }.orEmpty()
}

private val OffOn = listOf("Off", "On")
private fun Boolean.index() = if (this) 1 else 0

internal object SettingsRegistry {
    private fun switch(id: String, title: String, help: String, page: SettingsPage, card: String, effect: SettingEffect,
        words: List<String> = emptyList(), shown: (SettingsSnapshot) -> Boolean = { true },
        get: (SettingsSnapshot) -> Boolean, set: (SettingsSnapshot, Boolean) -> SettingsSnapshot) =
        SettingDef(id, title, help, page, card, SettingControl.Switch, effect, words, { OffOn }, { get(it).index() },
            { s, i -> set(s, i == 1) }, shown)

    private fun page(id: String, title: String, help: String, page: SettingsPage, card: String, opens: SettingsPage,
        words: List<String> = emptyList(), shown: (SettingsSnapshot) -> Boolean = { true }, detail: ((SettingsSnapshot) -> String)? = null) =
        SettingDef(id, title, help, page, card, SettingControl.Page, SettingEffect.None, words, shown = shown, opens = opens, detail = detail)

    private fun action(id: String, title: String, help: String, page: SettingsPage, card: String,
        words: List<String> = emptyList(), detail: ((SettingsSnapshot) -> String)? = null) =
        SettingDef(id, title, help, page, card, SettingControl.Action, SettingEffect.None, words, detail = detail)

    private fun <T> choice(id: String, title: String, help: String, page: SettingsPage, card: String, effect: SettingEffect,
        values: (SettingsSnapshot) -> List<T>, label: (T) -> String, get: (SettingsSnapshot) -> T,
        set: (SettingsSnapshot, T) -> SettingsSnapshot, control: SettingControl = SettingControl.Choice,
        words: List<String> = emptyList(), shown: (SettingsSnapshot) -> Boolean = { true }) =
        SettingDef(id, title, help, page, card, control, effect, words, { s -> values(s).map(label) },
            { s -> values(s).indexOf(get(s)).coerceAtLeast(0) }, { s, i -> set(s, values(s)[i.coerceIn(values(s).indices)]) }, shown)

    private fun hubOpacityLabel(p: Int) = "$p %"

    val all: List<SettingDef> = listOf(
        // ---------------- Home > Arcs ----------------
        choice("ArcsShown", "Arcs shown", "How many rings of apps and folders. Items on a removed arc move to the next ring",
            SettingsPage.HomeArcs, "Arcs", SettingEffect.Visual, { listOf(3, 4) }, { it.toString() }, { it.counts.arcs },
            { s, v -> s.copy(counts = s.counts.copy(arcs = v)) }, words = listOf("rings", "arcs")),
        choice("IconsPerArc", "Icons per arc", "Auto fits as many as the screen allows. Turn an arc to reach the rest",
            SettingsPage.HomeArcs, "Arcs", SettingEffect.Visual, { s -> listOf(0) + (3..s.maxIconsPerArc.coerceAtLeast(3)).toList() },
            { if (it == 0) "Auto" else it.toString() }, { it.counts.icons }, { s, v -> s.copy(counts = s.counts.copy(icons = v)) },
            words = listOf("slots", "apps per ring")),
        choice("ArcLayout", "Arc layout", "Line up icons across arcs or stagger them between arcs", SettingsPage.HomeArcs, "Arcs",
            SettingEffect.Visual, { listOf(false, true) }, { if (it) "Aligned" else "Staggered" }, { it.aligned },
            { s, v -> s.copy(aligned = v) }, words = listOf("align", "stagger")),
        switch("LockLayout", "Lock layout", "Stop apps and folders on the arcs from being moved, added or removed",
            SettingsPage.HomeArcs, "Arcs", SettingEffect.Logic, listOf("freeze", "arrange", "move", "remove"),
            get = { it.layoutLocked }) { s, on -> s.copy(layoutLocked = on) },
        switch("LeftHand", "Left hand", "Move the hub to the bottom left and mirror every arc. Your layout is not changed",
            SettingsPage.HomeArcs, "Arcs", SettingEffect.Logic, listOf("left-handed", "mirror", "side", "lefty"),
            get = { it.leftHand }) { s, on -> s.copy(leftHand = on) },
        page("Rotation", "Arc rotation", "How each arc is turned and positioned", SettingsPage.HomeArcs, "Arcs", SettingsPage.Rotation,
            listOf("turn", "rotate")),

        // ---------------- Home > Apps & folders ----------------
        page("MainApps", "Main apps", "Choose and order the four inner-arc apps", SettingsPage.HomeApps, "Apps", SettingsPage.MainApps,
            listOf("pinned", "favourites")),
        page("Folders", "Folders", "Create, rename and organise folders", SettingsPage.HomeApps, "Folders", SettingsPage.Folders),
        switch("MarkWorkApps", "Mark work apps", "Show a briefcase on work-profile apps", SettingsPage.HomeApps, "Work apps",
            SettingEffect.Visual, listOf("work profile", "briefcase"), get = { it.markWorkApps }) { s, on -> s.copy(markWorkApps = on) },

        // ---------------- Home > Hidden & locked ----------------
        page("HiddenApps", "Hidden apps", "Hide apps or bring them back", SettingsPage.HiddenLocked, "Hidden apps", SettingsPage.Hidden,
            listOf("hide")),
        page("HiddenFolders", "Hidden folders", "Folders you hide leave Home with their apps",
            SettingsPage.HiddenLocked, "Hidden folders", SettingsPage.HiddenFolders, listOf("hide", "folder"), detail = { it.hiddenFoldersSummary }),
        page("LockedFolders", "Locked folders", "Hide the apps in chosen folders until you confirm it is you",
            SettingsPage.HiddenLocked, "Locked", SettingsPage.LockedFolders, listOf("private", "fingerprint", "lock"), detail = { it.lockedFoldersSummary }),

        // ---------------- Appearance > Skin ----------------
        SettingDef("Appearance", "Skin", "The colours and shapes of Parc. Swipe the cards and tap one to try it",
            SettingsPage.AppearanceSkin, "Skin", SettingControl.Custom, SettingEffect.Visual, listOf("theme", "colours", "style"),
            options = { s -> s.skins.map { it.name } }, read = { s -> s.skins.indexOfFirst { it.id == s.skinId }.coerceAtLeast(0) },
            write = { s, i -> s.copy(skinId = s.skins[i.coerceIn(s.skins.indices)].id) }),
        page("SkinStudio", "Skin editor", "Make your own skin, import or export skin files", SettingsPage.AppearanceSkin, "Skin",
            SettingsPage.SkinStudio, listOf("custom skin", "import", "export")),

        // ---------------- Appearance > Wallpaper ----------------
        choice("WallpaperSkinMode", "Wallpaper skin colours", "Dark or light colours taken from your wallpaper",
            SettingsPage.AppearanceWallpaper, "Wallpaper skin", SettingEffect.Visual, { WallpaperMode.entries }, { it.label }, { it.wallpaperMode },
            { s, v -> s.copy(wallpaperMode = v) }, shown = { it.wallpaperSkinSupported && it.skinId == "wallpaper" }),
        choice("WallpaperSkinLook", "Wallpaper skin based on", "Which skin's frames and shapes the Wallpaper skin uses",
            SettingsPage.AppearanceWallpaper, "Wallpaper skin", SettingEffect.Visual, { WallpaperLook.entries }, { it.label }, { it.wallpaperLook },
            { s, v -> s.copy(wallpaperLook = v) }, shown = { it.wallpaperSkinSupported && it.skinId == "wallpaper" }),
        SettingDef("WallpaperImage", "What sits behind Parc", "Your phone's wallpaper, a picture you choose, or just the skin's colour",
            SettingsPage.AppearanceWallpaper, "Wallpaper", SettingControl.Custom, SettingEffect.Visual,
            listOf("wallpaper", "background", "picture", "photo", "image"),
            options = { WallpaperChoices.map(::wallpaperChoiceLabel) }, read = { s -> wallpaperChoiceIndex(s.look.wallpaper) },
            write = { s, i -> s.copy(look = s.look.copy(wallpaper = WallpaperChoices[i.coerceIn(WallpaperChoices.indices)])) }),
        choice("WallpaperDim", "Dim", "Darkens the wallpaper or picture so icons stand out. Glass cards darken with it",
            SettingsPage.AppearanceWallpaper, "Wallpaper", SettingEffect.Visual, { LookRules.DimSteps }, { "$it %" }, { it.look.wallpaperDimPercent },
            { s, v -> s.copy(look = s.look.copy(wallpaperDimPercent = v)) }, SettingControl.Slider, listOf("darken", "dark", "wallpaper"),
            shown = { it.hasWallpaper }),
        switch("WallpaperReadability", "Keep text readable", "Shades the picture behind text and keeps the hub cards readable",
            SettingsPage.AppearanceWallpaper, "Wallpaper", SettingEffect.Visual, listOf("readability", "scrim", "contrast", "shade"),
            shown = { it.hasWallpaper }, get = { it.look.wallpaperReadability }) { s, on ->
            s.copy(look = s.look.copy(keepTextReadable = on, wallpaperReadability = on, imageReadabilityScrim = on))
        },

        // ---------------- Appearance > Icons & clock ----------------
        choice("IconShape", "Icon shape", "The outline every app icon is cut to", SettingsPage.AppearanceIcons, "Icons",
            SettingEffect.Visual, { IconShape.entries }, LookRules::iconShapeLabel, { it.look.iconShape },
            { s, v -> s.copy(look = s.look.copy(iconShape = v)) }, words = listOf("icons", "round", "square")),
        choice("IconSize", "Icon size", "Larger icons are easier to hit; arcs keep their spacing", SettingsPage.AppearanceIcons, "Icons",
            SettingEffect.Visual, { LookRules.IconSizeSteps }, { "$it dp" }, { it.look.iconSizeDp },
            { s, v -> s.copy(look = s.look.copy(iconSizeDp = v)) }, SettingControl.Slider, listOf("icons", "bigger", "smaller")),
        choice("IconPack", "Icon pack", "Use icons from an installed icon pack", SettingsPage.AppearanceIcons, "Icons",
            SettingEffect.Visual, { s -> listOf<String?>(null) + s.iconPacks.map { it.first } },
            { pkg -> pkg ?: "None" }, { it.iconPack }, { s, v -> s.copy(iconPack = v) }, words = listOf("icons", "theme"),
            shown = { it.iconPacks.isNotEmpty() || it.iconPack != null }),
        switch("ClockVisible", "Show clock", "Show the time on the Clock hub page", SettingsPage.AppearanceIcons, "Clock",
            SettingEffect.Visual, listOf("time"), get = { it.clockVisible }) { s, on -> s.copy(clockVisible = on) },
        choice("ClockStyle", "Clock style", "How the time is drawn", SettingsPage.AppearanceIcons, "Clock", SettingEffect.Visual,
            { ClockStyle.entries }, LookRules::clockStyleLabel, { it.look.clockStyle }, { s, v -> s.copy(look = s.look.copy(clockStyle = v)) },
            words = listOf("time", "font"), shown = { it.clockVisible }),
        switch("ShowDate", "Date line", "Today's date under the small clock shown when the stats hub is off", SettingsPage.AppearanceIcons,
            "Clock", SettingEffect.Visual, listOf("date", "clock"), shown = { it.clockVisible && !it.statsVisible },
            get = { it.look.showDate }) { s, on -> s.copy(look = s.look.copy(showDate = on)) },
        switch("ShowAlarm", "Next alarm", "Your next alarm under the small clock shown when the stats hub is off", SettingsPage.AppearanceIcons,
            "Clock", SettingEffect.Visual, listOf("alarm", "clock"), shown = { it.clockVisible && !it.statsVisible },
            get = { it.look.showAlarm }) { s, on -> s.copy(look = s.look.copy(showAlarm = on)) },

        // ---------------- Appearance > Hubs ----------------
        switch("GlassEffect", "Glass effect", "Both hub cards become frosted glass over your wallpaper or picture",
            SettingsPage.Hubs, "Both hubs", SettingEffect.Visual, listOf("frosted", "blur", "transparent"),
            get = { it.glass }) { s, on -> s.copy(hub = s.hub.copy(glass = on)) },
        switch("GlassRefraction", "Glass refraction", "Bends the picture at the glass card edge, like a thick rim",
            SettingsPage.Hubs, "Both hubs", SettingEffect.Visual, listOf("lens", "edge", "glass"), shown = { it.glass },
            get = { it.hub.refraction }) { s, on -> s.copy(hub = s.hub.copy(refraction = on)) },
        choice("ArcHubColour", "Colour", "The colour of the arc hub card", SettingsPage.Hubs, "Arc hub", SettingEffect.Visual,
            { HubColour.entries }, { it.label }, { it.hub.arc }, { s, v -> s.copy(hub = s.hub.copy(arc = v)) }, SettingControl.Colour,
            listOf("arc hub colour", "card colour")),
        choice("ArcHubOpacity", "Opacity", "How solid the arc hub card is. Lower lets more of the wallpaper show",
            SettingsPage.Hubs, "Arc hub", SettingEffect.Visual, { s -> HubOpacity.stepsFor(s.glass) }, ::hubOpacityLabel,
            { s -> s.hub.arcOpacityPercent(glass = s.glass) }, { s, v -> s.copy(hub = s.hub.copy(arcOpacity = v)) },
            SettingControl.Slider, listOf("arc hub opacity", "transparent", "see-through", "panel")),
        choice("StatsHubColour", "Colour", "The colour of the stats hub card", SettingsPage.Hubs, "Stats hub", SettingEffect.Visual,
            { HubColour.entries }, { it.label }, { it.hub.stats }, { s, v -> s.copy(hub = s.hub.copy(stats = v)) }, SettingControl.Colour,
            listOf("stats hub colour", "card colour"), shown = { it.statsVisible }),
        choice("StatsHubOpacity", "Opacity", "How solid the stats hub card is. Lower lets more of the wallpaper show",
            SettingsPage.Hubs, "Stats hub", SettingEffect.Visual, { s -> HubOpacity.stepsFor(s.glass) }, ::hubOpacityLabel,
            { s -> s.hub.statsOpacityPercent(glass = s.glass) }, { s, v -> s.copy(hub = s.hub.copy(statsOpacity = v)) },
            SettingControl.Slider, listOf("stats hub opacity", "transparent", "see-through", "panel"), shown = { it.statsVisible }),
        switch("StatsVisible", "Show stats hub", "Show the stats hub card above the arcs", SettingsPage.Hubs, "Stats hub",
            SettingEffect.Visual, listOf("stats", "panel", "hide hub"), get = { it.statsVisible }) { s, on -> s.copy(statsVisible = on) },
        page("HubPages", "Pages & alerts", "Add, remove or move pages; each page has its own options", SettingsPage.Hubs, "Pages & alerts",
            SettingsPage.HubPages, listOf("pages", "order", "24-hour", "seconds", "time zone"), shown = { it.statsVisible }),

        // ---------------- Appearance > Hubs > Pages & alerts ----------------
        choice("HubSize", "Stats hub size", "Compact is short. Standard and Tall are the same height; Tall shows every tile of a page",
            SettingsPage.HubPages, "Stats hub", SettingEffect.Visual, { HubSize.entries }, { it.name }, { it.hubSize },
            { s, v -> s.copy(hubSize = v) }, words = listOf("hub height", "tall", "compact"), shown = { it.statsVisible }),
        page("Agenda", "Agenda", "Choose calendars and how many days to show", SettingsPage.HubPages, "Pages", SettingsPage.Agenda,
            listOf("calendar", "events"), shown = { it.statsVisible }, detail = { it.agendaSummary }),
        page("StatsAlerts", "Stats alerts and history", "Set warning levels and show history graphs", SettingsPage.HubPages, "Alerts",
            SettingsPage.StatsAlerts, listOf("battery", "temperature", "warning", "graph"), shown = { it.statsVisible }),

        // ---------------- Gestures & search > Gestures ----------------
        page("SwipeUp", "Swipe up", "Choose what a swipe up on Home opens", SettingsPage.Gestures, "Gestures", SettingsPage.SwipeUp,
            listOf("gesture"), detail = { it.swipeUpLabel }),
        page("HubLongPress", "Hub long-press", "Choose what holding the hub opens", SettingsPage.Gestures, "Gestures",
            SettingsPage.HubLongPress, listOf("gesture", "hold"), detail = { it.hubLongPressLabel }),
        action("ShowHintsAgain", "Show hints again", "Replay the short tips for turning arcs, search, the hub, editing and page names",
            SettingsPage.Gestures, "Gestures", listOf("tips", "help", "first run")),
        switch("HubDoubleTap", "Hub double-tap", "Open the last app with two hub taps", SettingsPage.Gestures, "Gestures",
            SettingEffect.Logic, listOf("last app"), get = { it.hubDoubleTap }) { s, on -> s.copy(hubDoubleTap = on) },
        switch("Joystick", "Joystick aim", "Drag from the hub to aim at an app", SettingsPage.Gestures, "Gestures",
            SettingEffect.Logic, listOf("aim", "drag"), get = { it.joystick }) { s, on -> s.copy(joystick = on) },

        // ---------------- Gestures & search > Feel ----------------
        switch("Haptics", "Vibration", "Vibrate for taps and gestures", SettingsPage.Feel, "Feel",
            SettingEffect.Logic, listOf("haptics", "vibrate"), get = { it.haptics != HapticsLevel.Off }) { s, on ->
            s.copy(haptics = if (on) HapticsLevel.Light else HapticsLevel.Off)
        },
        choice("HapticPreset", "Vibration style", "How the vibration feels", SettingsPage.Feel, "Feel",
            SettingEffect.Logic, { HapticPreset.entries.filter { it != HapticPreset.Off } }, { it.label }, { it.feel.haptic },
            { s, v -> s.copy(feel = s.feel.copy(haptic = v)) }, words = listOf("haptics"), shown = { it.haptics != HapticsLevel.Off }),
        choice("SoundTheme", "Click sounds", "A short sound for taps. Silent when the phone is on silent or vibrate",
            SettingsPage.Feel, "Feel", SettingEffect.Logic, { SoundTheme.entries }, { it.label }, { it.feel.sound },
            { s, v -> s.copy(feel = s.feel.copy(sound = v)) }, words = listOf("sound", "click", "tick")),
        choice("SoundVolume", "Sound volume", "How loud the click sounds are", SettingsPage.Feel, "Feel",
            SettingEffect.Logic, { FeelUi.VolumeSteps }, { "$it %" }, { it.feel.volumePercent },
            { s, v -> s.copy(feel = s.feel.copy(volumePercent = v)) }, SettingControl.Slider, shown = { it.feel.sound != SoundTheme.Off }),
        choice("SpinLimit", "Spin limit", "How far one flick can turn the arc", SettingsPage.Feel, "Feel", SettingEffect.Logic,
            { SpinLimit.entries }, { listOf("Tight", "Balanced", "Free")[it.ordinal] }, { it.arcFeel.spinLimit },
            { s, v -> s.copy(arcFeel = s.arcFeel.copy(spinLimit = v)) }, words = listOf("arc", "flick")),
        choice("Bounce", "Bounce", "How much the arc bounces when it lands", SettingsPage.Feel, "Feel", SettingEffect.Logic,
            { Bounce.entries }, { listOf("Calm", "Balanced", "Playful")[it.ordinal] }, { it.arcFeel.bounce },
            { s, v -> s.copy(arcFeel = s.arcFeel.copy(bounce = v)) }, words = listOf("arc", "spring")),
        choice("Magnify", "Magnify under thumb", "How big the slot under your thumb grows", SettingsPage.Feel, "Feel",
            SettingEffect.Logic, { Magnify.entries }, { listOf("Off", "Subtle", "Dock-like")[it.ordinal] }, { it.arcFeel.magnify },
            { s, v -> s.copy(arcFeel = s.arcFeel.copy(magnify = v)) }, words = listOf("arc", "zoom")),

        // ---------------- Gestures & search > Search ----------------
        page("Search", "Search preferences", "Web engine and recent searches", SettingsPage.Search, "Search",
            SettingsPage.SearchPreferences, listOf("web", "google", "recent")),
        switch("SearchPhoneSettings", "Search phone settings", "Let search open phone settings pages such as Wi-Fi or Battery",
            SettingsPage.Search, "Search", SettingEffect.Logic, get = { it.searchPhoneSettings }) { s, on -> s.copy(searchPhoneSettings = on) },
        switch("SearchFiles", "Search files", "Let search find photos, videos and music by name. Needs permission",
            SettingsPage.Search, "Search", SettingEffect.Logic, listOf("photos", "music"), shown = { it.fileSearchAvailable },
            get = { it.searchFiles }) { s, on -> s.copy(searchFiles = on) },
        switch("SearchEvents", "Search calendar events", "Let search find calendar events in the next 60 days. Needs calendar permission",
            SettingsPage.Search, "Search", SettingEffect.Logic, listOf("calendar"), get = { it.searchEvents }) { s, on -> s.copy(searchEvents = on) },

        // ---------------- Notifications & Focus > Notifications ----------------
        action("NotificationAccess", "Notification access", "Allow access for badges, Messages and notifications in menus",
            SettingsPage.Notifications, "Access", listOf("permission"), detail = { if (it.notificationAccess) "Allowed" else "Not allowed" }),

        switch("NotificationBadges", "Notification badges", "Mark apps that have unread notifications", SettingsPage.Notifications,
            "Badges", SettingEffect.Visual, listOf("dots", "unread"), get = { it.badges }) { s, on -> s.copy(badges = on) },
        switch("BadgesInAllApps", "Badges in All apps", "Also show unread marks in All apps", SettingsPage.Notifications,
            "Badges", SettingEffect.Logic, listOf("dots"), shown = { it.badges }, get = { it.badgesInAllApps }) { s, on -> s.copy(badgesInAllApps = on) },
        switch("MenuNotifications", "Notifications in menus", "Show an app's current notifications when you hold its icon",
            SettingsPage.Notifications, "Menus", SettingEffect.Logic, listOf("long-press"), get = { it.menuNotifications }) { s, on ->
            s.copy(menuNotifications = on)
        },
        switch("MenuNotificationText", "Show message text", "Show the message line under each title; off shows titles only",
            SettingsPage.Notifications, "Menus", SettingEffect.Logic, shown = { it.menuNotifications },
            get = { it.menuNotificationText }) { s, on -> s.copy(menuNotificationText = on) },
        // ---------------- Notifications & Focus > Messages ----------------
        page("Comms", "Messages", "Messages, on-phone history and quick replies", SettingsPage.Messages, "Messages",
            SettingsPage.Messages, listOf("comms", "messages and quick replies", "whatsapp", "telegram", "reply")),
        switch("CommsEnabled", "Messages on/off", "Live cards and recording; turning this off keeps stored history",
            SettingsPage.Messages, "Messages", SettingEffect.Logic, listOf("comms", "live"), get = { it.commsEnabled }) { s, on ->
            s.copy(commsEnabled = on)
        },
        switch("CommsRecordHistory", "Record message history",
            "Store messages from WhatsApp, Telegram and Instagram on this phone: text and links only, no images, not in backups",
            SettingsPage.Messages, "History", SettingEffect.Logic, listOf("comms", "keep history", "storage"),
            get = { it.commsRecordHistory }) { s, on -> s.copy(commsRecordHistory = on) },
        page("CommsApps", "Messages apps", "Choose which apps can show messages", SettingsPage.Messages, "Apps",
            SettingsPage.CommsApps, listOf("comms", "whatsapp", "telegram", "instagram")),
        page("QuickReplies", "Quick replies", "Add or remove saved reply text", SettingsPage.Messages, "Replies",
            SettingsPage.QuickReplies, listOf("comms", "canned")),
        action("CommsClearHistory", "Clear message history", "Delete every stored message; this cannot be undone",
            SettingsPage.Messages, "History", listOf("comms", "delete", "storage"), detail = { it.commsStorageLine }),

        // ---------------- Notifications & Focus > Focus ----------------
        page("Focus", "Focus modes", "Choose a mode, its apps, times and how strict to be", SettingsPage.Focus, "Focus",
            SettingsPage.Focus, listOf("work", "sleep", "do not disturb"), detail = { it.focusSummary }),

        // ---------------- Data & about > Backup ----------------
        page("Backup", "Backup and restore", "Export or restore your layout and settings in one file", SettingsPage.DataBackup, "Backup",
            SettingsPage.Backup, listOf("export", "import", "restore", "save", "weekly", "automatic", "folder")),
        switch("WallpaperImageBackup", "Include wallpaper in backups", "Add the private picture to exported backups, maximum 2 MB",
            SettingsPage.DataBackup, "Backup", SettingEffect.Logic, listOf("picture"), get = { it.look.includeWallpaperInBackups }) { s, on ->
            s.copy(look = s.look.copy(includeWallpaperInBackups = on))
        },

        // ---------------- Data & about > This build ----------------
        SettingDef("About", "This build", "Version, version code, build time and code commit", SettingsPage.DataBuild, "About",
            SettingControl.Custom, SettingEffect.None, listOf("version", "build", "about", "stamp")),
        page("WhatsNew", "What's new", "What changed in this version", SettingsPage.DataBuild, "About", SettingsPage.WhatsNew,
            listOf("changelog", "release notes")),
        page("VersionHistory", "Version history", "Every version and what it changed", SettingsPage.DataBuild, "About",
            SettingsPage.VersionHistory, listOf("changelog")),

        // ---------------- Data & about > Permissions ----------------
        page("Permissions", "Permissions", "See which optional phone access is on and turn it on", SettingsPage.DataPermissions,
            "Access", SettingsPage.Permissions, listOf("privacy", "usage", "contacts", "calendar")),
        action("RerunOnboarding", "Show setup again", "Review home app, usage, contacts and notification access",
            SettingsPage.DataPermissions, "Access", listOf("onboarding", "setup")),
        action("AndroidSettings", "Android settings", "Open the phone's own settings app", SettingsPage.DataPermissions, "Access"),

        // ---------------- Data & about > Help ----------------
        page("Diagnostics", "Diagnostics", "Scene details and the latest error, for bug reports", SettingsPage.DataHelp, "Help",
            SettingsPage.Diagnostics, listOf("error", "crash", "debug")),
    )

    val byId: Map<String, SettingDef> = all.associateBy { it.id }

    /** T219: the three rows the Hidden & locked page owns; their ids and destinations do not change. */
    val hiddenLockedRowIds: Set<String> = setOf("HiddenApps", "HiddenFolders", "LockedFolders")

    /** The two rows that are page entries: searchable and jumpable, hidden on their own page. */
    val selfEntryIds: Set<String> = setOf("Comms", "Focus")

    /** Rows on one group sub-page or editor, in registry order. */
    fun on(page: SettingsPage): List<SettingDef> = all.filter { it.page == page }

    /** The rows a page draws for this snapshot. A self-entry row is not repeated on its own page. */
    fun shown(page: SettingsPage, s: SettingsSnapshot): List<SettingDef> {
        val ownEntry = when (page) {
            SettingsPage.Messages -> "Comms"
            SettingsPage.Focus -> "Focus"
            else -> null
        }
        return on(page).filter { it.id != ownEntry && it.shown(s) }
    }

    /**
     * Search inside Settings: every word must appear in the title, help, card, page title, group title, parent title,
     * page aliases or search words. Hidden rows are found too, and the old page names still match.
     */
    fun search(query: String): List<SettingDef> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.filter { def ->
            val text = (listOf(def.title, def.help, def.page.title, def.card, def.page.group?.title.orEmpty(),
                def.page.parent?.title.orEmpty()) + def.page.aliases + def.words).joinToString(" ")
            words.all { text.contains(it, ignoreCase = true) }
        }
    }

    /** Applies one value change by id; unknown ids and non-value rows leave the snapshot unchanged. */
    fun set(s: SettingsSnapshot, id: String, index: Int): SettingsSnapshot = byId[id]?.write?.invoke(s, index) ?: s
}

/** T220: the one-line subtitle of a Settings search result, with the hidden-row note. */
internal fun settingsSearchSubtitle(def: SettingDef, hidden: Boolean): String =
    def.page.breadcrumb + if (hidden) " · not shown now, it has no effect here" else ""

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
