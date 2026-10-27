package com.brightblu.parc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.feature.settings.skins.SearchSettingsSection
import com.brightblu.parc.feature.settings.skins.MainAppsSettingsSection
import androidx.compose.foundation.layout.heightIn
import android.content.ComponentName
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService
import com.brightblu.parc.feature.settings.notifications.NotificationAccessLink
import com.brightblu.parc.feature.settings.notifications.NotificationAccessStatus
import androidx.activity.compose.BackHandler
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.ParcSettings
import com.brightblu.parc.core.data.backup.AndroidBackupFileStore
import com.brightblu.parc.feature.settings.backup.BackupController
import com.brightblu.parc.feature.settings.backup.LayoutBackupScreen
import com.brightblu.parc.feature.settings.backup.LayoutBackupService
import com.brightblu.parc.feature.settings.hidden.HiddenApps
import com.brightblu.parc.feature.settings.hidden.HiddenAppsController
import com.brightblu.parc.feature.settings.onboarding.AndroidOnboardingIntents
import com.brightblu.parc.feature.settings.onboarding.AndroidOnboardingProbe
import com.brightblu.parc.feature.settings.onboarding.Onboarding
import com.brightblu.parc.feature.settings.onboarding.OnboardingController
import com.brightblu.parc.feature.settings.onboarding.asOnboardingStore
import com.brightblu.parc.feature.settings.skins.ParcSettingsScreen
import com.brightblu.parc.feature.settings.skins.SettingsController
import com.brightblu.parc.feature.settings.skins.SkinStudioContent
import com.brightblu.parc.feature.settings.permissions.PermissionsContent
import com.brightblu.parc.feature.settings.skins.SkinStudioHost
import com.brightblu.parc.core.data.skins.SkinNaming
import com.brightblu.parc.core.data.skins.WallpaperLook
import com.brightblu.parc.core.data.skins.WallpaperMode
import com.brightblu.parc.feature.settings.skins.asSettingsStore
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.LookRules
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinSafety
import com.brightblu.parc.model.locked
import kotlinx.coroutines.launch

/** One row on the Parc Settings root page. Pure model so the row set is unit-testable. */
internal data class SettingsRow(val label: String, val detail: String, val id: SettingsRowId, val checked: Boolean? = null, val section: SettingsSectionId = SettingsSectionId.Appearance,
    val kind: SettingKind = SettingKind.Page, val description: String = "")

/** Stable identity of a root row; the host dispatches on it. */
internal enum class SettingsRowId {
    WallpaperImage, WallpaperImageBackup, ImageReadabilityScrim, WallpaperDim, ArcsShown, IconsPerArc, PanelOpacity, WallpaperReadability, SkinStudio, WallpaperSkinMode, WallpaperSkinLook, Feel, Permissions, Search, SearchPhoneSettings, SearchFiles, SearchEvents, MainApps, Rotation, About, StatsAlerts, Comms, HubDoubleTap, SwipeUp, HubLongPress, Appearance, Look, Focus, ArcLayout, StatsVisible, HubSize, Joystick, HiddenApps, Backup, RerunOnboarding, AndroidSettings, Folders, LockedFolders, NotificationAccess, NotificationBadges, BadgesInAllApps, MenuNotifications, MenuNotificationText, MarkWorkApps, HubPages, HeaderTime, Agenda, Diagnostics,
}

/** Sub-pages of the Parc Settings host. */
internal enum class SettingsPage { Panel, HubOptions, Wallpaper, WhatsNew, VersionHistory, LayoutCounts, CommsApps, QuickReplies, Search, MainApps, Rotation, About, Root, StatsAlerts, Appearance, Look, Focus, Hidden, Backup, Onboarding, Folders, LockedFolders, HubPages, Agenda, Comms, SwipeUp, HubLongPress, Diagnostics, SkinStudio, Feel, Permissions }

/**
 * The root rows in order (T30): everything the card names, built from the current state so the
 * detail column always reflects the stored value. T69 adds the two gesture rows after the hub
 * double-tap row.
 */
internal fun settingsRootRows(
    skinName: String,
    alignedArcs: Boolean,
    statsVisible: Boolean,
    statsExpanded: Boolean,
    joystick: Boolean,
    notificationAccess: Boolean = false,
    badgesEnabled: Boolean = true,
    badgesInAllApps: Boolean = false,
    markWorkApps: Boolean = true,
    hubDoubleTap: Boolean = true,
    gestures: GestureConfig = GestureConfig(),
    appLabel: (AppId) -> String? = { null },
    look: LookSettings = LookSettings(),
    focusSummary: String = "No modes",
    /** T96: null on devices without dynamic colour, which hides the two Wallpaper skin rows. */
    wallpaperMode: WallpaperMode? = null,
    wallpaperLook: WallpaperLook = WallpaperLook.Midnight,
    /** T97: `Notifications in menus` and `Show message text`. */
    menuNotifications: Boolean = true,
    menuNotificationText: Boolean = true,
    /** T98: root row detail of `Locked folders`, for example `2 locked`. */
    lockedFoldersSummary: String = com.brightblu.parc.core.data.lock.LockedFolderText.summary(0),
    /** T100: root row detail of `Agenda`, for example `2 days`. */
    headerTime: Boolean = false,
    agendaSummary: String = AgendaSettingsText.summary(com.brightblu.parc.core.data.agenda.AgendaSettings()),
    /** T102: the three optional search sources. */
    searchPhoneSettings: Boolean = true,
    searchFiles: Boolean = true,
    searchEvents: Boolean = true,
    layoutCounts: com.brightblu.parc.model.LayoutCounts = com.brightblu.parc.model.LayoutCounts(),
): List<SettingsRow> = listOfNotNull(
    SettingsRow("Skin, haptics and clock", skinName, SettingsRowId.Appearance),
    SettingsRow("Look", LookRules.summary(look), SettingsRowId.Look),
    SettingsRow("Panel opacity", "${look.panelOpacityPercent} %", SettingsRowId.PanelOpacity),
    SettingsRow("Keep text readable", if (look.wallpaperReadability) "On" else "Off", SettingsRowId.WallpaperReadability, look.wallpaperReadability),
    SettingsRow("Wallpaper image", wallpaperChoiceLabel(look.wallpaper), SettingsRowId.WallpaperImage),
    SettingsRow("Include wallpaper in backups", if (look.includeWallpaperInBackups) "On" else "Off", SettingsRowId.WallpaperImageBackup, look.includeWallpaperInBackups),
    SettingsRow("Readability scrim", if (look.imageReadabilityScrim) "On" else "Off", SettingsRowId.ImageReadabilityScrim, look.imageReadabilityScrim),
    SettingsRow("Dim", "${look.wallpaperDimPercent}%", SettingsRowId.WallpaperDim),
    SettingsRow("Focus modes", focusSummary, SettingsRowId.Focus),
    SettingsRow("Skin editor", "Colours, import and export", SettingsRowId.SkinStudio),
    wallpaperMode?.let { SettingsRow("Wallpaper skin colours", it.label, SettingsRowId.WallpaperSkinMode) },
    wallpaperMode?.let { SettingsRow("Wallpaper skin based on", wallpaperLook.label, SettingsRowId.WallpaperSkinLook) },
    SettingsRow("Haptics and sound", "Vibration and click sounds", SettingsRowId.Feel),
    SettingsRow("Arcs shown", layoutCounts.arcs.toString(), SettingsRowId.ArcsShown),
    SettingsRow("Icons per arc", if (layoutCounts.icons == 0) "Auto" else layoutCounts.icons.toString(), SettingsRowId.IconsPerArc),
    SettingsRow("Arc layout", if (alignedArcs) "Aligned" else "Staggered", SettingsRowId.ArcLayout),
    SettingsRow("Stats", if (statsVisible) "Shown" else "Hidden", SettingsRowId.StatsVisible),
    SettingsRow("Hub size", "Compact / Standard / Tall", SettingsRowId.HubSize),
    SettingsRow("Hub double-tap", if (hubDoubleTap) "On" else "Off", SettingsRowId.HubDoubleTap, hubDoubleTap),
    SettingsRow("Swipe up", gestureActionLabel(gestures.swipeUp, appLabel), SettingsRowId.SwipeUp),
    SettingsRow("Hub long-press", gestureActionLabel(gestures.hubLongPress, appLabel), SettingsRowId.HubLongPress),
    SettingsRow("Joystick aim", if (joystick) "On" else "Off", SettingsRowId.Joystick),
    SettingsRow("Folders", "Create and edit", SettingsRowId.Folders),
    SettingsRow("Locked folders", lockedFoldersSummary, SettingsRowId.LockedFolders),
    SettingsRow("Hidden apps", "", SettingsRowId.HiddenApps),
    SettingsRow("Backup and restore", "", SettingsRowId.Backup),
    SettingsRow("Permissions", "Usage, notifications, contacts, calendar", SettingsRowId.Permissions),
    SettingsRow("Show setup again", "", SettingsRowId.RerunOnboarding),
    SettingsRow("Notification badges", if (notificationAccess) "Set" else "Not set", SettingsRowId.NotificationAccess),
    SettingsRow("Notification badges", if (badgesEnabled) "On" else "Off", SettingsRowId.NotificationBadges, badgesEnabled),
    SettingsRow("Badges in All apps", if (badgesInAllApps) "On" else "Off", SettingsRowId.BadgesInAllApps, badgesInAllApps),
    SettingsRow("Notifications in menus", if (menuNotifications) "On" else "Off", SettingsRowId.MenuNotifications, menuNotifications),
    SettingsRow("Show message text", if (menuNotificationText) "On" else "Off", SettingsRowId.MenuNotificationText, menuNotificationText),
    SettingsRow("Mark work apps", if (markWorkApps) "On" else "Off", SettingsRowId.MarkWorkApps, markWorkApps),
    SettingsRow("Comms", "On/off and Comms apps", SettingsRowId.Comms),
    SettingsRow("Stats alerts and history", "Thresholds and optional graphs", SettingsRowId.StatsAlerts),
    SettingsRow("Stats pages", "Show, hide or reorder", SettingsRowId.HubPages),
    SettingsRow("Time in the page header", if (headerTime) "On" else "Off", SettingsRowId.HeaderTime, headerTime),
    SettingsRow("Agenda", agendaSummary, SettingsRowId.Agenda),
    SettingsRow("Diagnostics", "Scene, capacities, error", SettingsRowId.Diagnostics),
    SettingsRow("Android Settings", "", SettingsRowId.AndroidSettings),
    SettingsRow("Search preferences", "", SettingsRowId.Search),
    SettingsRow("Search phone settings", if (searchPhoneSettings) "On" else "Off", SettingsRowId.SearchPhoneSettings, searchPhoneSettings),
    SettingsRow("Search files", if (searchFiles) "On" else "Off", SettingsRowId.SearchFiles, searchFiles),
    SettingsRow("Search calendar events", if (searchEvents) "On" else "Off", SettingsRowId.SearchEvents, searchEvents),
    SettingsRow("Main apps", "", SettingsRowId.MainApps),
    SettingsRow("Arc rotation", "", SettingsRowId.Rotation),
    SettingsRow("About Parc", "", SettingsRowId.About),
).filter { settingsRowVisible(it.id, look, skinName == "Wallpaper", statsVisible, badgesEnabled, menuNotifications) }.map { row ->
    val spec = settingsRowSpec(row.id)
    row.copy(label = spec.title ?: row.label, section = spec.section, kind = spec.kind,
        checked = when (row.id) {
            SettingsRowId.StatsVisible -> statsVisible
            SettingsRowId.Joystick -> joystick
            else -> row.checked
        }, description = when (row.id) {
            SettingsRowId.NotificationAccess -> "${if (notificationAccess) "Allowed" else "Not allowed"}. ${spec.description}"
            else -> spec.description
        })
}

/**
 * The Parc Settings host (T30): a full-screen surface reached from the gear. It hosts T16's skin,
 * haptics and clock screen, T17's hidden apps and backup screens, and T18's onboarding flow, and
 * adds the arc-alignment, stats and joystick rows the card names. Colours and sizes come only from
 * [Skin] and [Metrics]; every write goes through the store the owning module already exposes.
 */
@Composable
internal fun ParcSettings(
    services: LauncherServices,
    skin: Skin,
    alignedArcs: Boolean,
    joystick: Boolean,
    hubDoubleTap: Boolean,
    onSetHubDoubleTap: (Boolean) -> Unit,
    gestures: GestureConfig,
    onSetGesture: (GestureSlot, GestureAction) -> Unit,
    badgesEnabled: Boolean,
    badgesInAllApps: Boolean,
    onSetBadgesEnabled: (Boolean) -> Unit,
    onSetBadgesInAllApps: (Boolean) -> Unit,
    markWorkApps: Boolean = true,
    onSetMarkWorkApps: (Boolean) -> Unit = {},
    onSetAlignedArcs: (Boolean) -> Unit,
    onSetJoystick: (Boolean) -> Unit,
    onAndroidSettings: () -> Unit,
    look: LookSettings,
    onChangeLook: ((LookSettings) -> LookSettings) -> Unit,
    onClose: () -> Unit,
    initialPage: SettingsPage = SettingsPage.Root,
    reviewSection: SettingsSectionId? = null,
) {
    // OPUS6 (audit F7): Settings is always drawn readably, even when the active skin's text is not,
    // so the way back to another skin can always be found.
    @Suppress("NAME_SHADOWING") val skin = SkinSafety.forSafetyScreens(skin)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // T98: pickers and lists never name an app that a locked folder is hiding.
    val apps by services.unlockedApps.collectAsState()
    val lockGate = rememberFolderLockGate(services)
    val lockWindows by services.folderLock.windows.collectAsState()
    val lockedPickerPackages by services.lockedPackages.collectAsState()
    val settingsLayout by services.layout.layout.collectAsState()
    val hiddenFolderIds = lockWindows.let {
        settingsLayout.folders.filter { lockGate.isFolderLockedNow(it.id) }.map { it.id }.toSet()
    }
    val hubStore = remember(context) { HubConfigStore(context) }
    val hubConfig by hubStore.config.collectAsState(initial = HubConfig())
    val headerTime by hubStore.headerTime.collectAsState(initial = false)
    // T102: the three optional search sources live in the search store (and its backup).
    val searchPrefs = remember(context) { com.brightblu.parc.core.data.search.SearchPrefs.create(context) }
    val searchSnapshot by searchPrefs.snapshot.collectAsState(initial = com.brightblu.parc.core.data.search.SearchPrefsSnapshot())
    val appLabel: (AppId) -> String? = { id -> apps.firstOrNull { it.id == id }?.label }
    val access = remember(context) {
        NotificationAccessLink.of(context, ComponentName(context, ParcNotificationListenerService::class.java))
    }
    var notificationAccess by remember(access) { mutableStateOf(access.status == NotificationAccessStatus.Granted) }
    DisposableEffect(context, access) {
        val lifecycle = (context as LifecycleOwner).lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationAccess = access.status == NotificationAccessStatus.Granted
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val settings by services.prefs.settings.collectAsState(initial = ParcSettings())
    // T73: the focus page and its root-row detail share one source of truth (T72's repository).
    val focus by services.focus.state.collectAsState()
    val focusModes by services.focus.modes.collectAsState()
    var page by remember { mutableStateOf(initialPage) }
    var rootSection by remember { mutableStateOf(reviewSection) }
    var hubOptions by remember { mutableStateOf("system") }
    var searchTarget by remember { mutableStateOf<SettingsRowId?>(null) }
    // T97: the two menu-notification toggles live in the same app-local prefs as the badge toggles.
    val menuPrefs = remember(context) { ParcUiPrefs(context) }
    var menuNotifications by remember { mutableStateOf(menuPrefs.menuNotifications()) }
    var menuNotificationText by remember { mutableStateOf(menuPrefs.menuNotificationText()) }
    var agendaSettings by remember { mutableStateOf(menuPrefs.agenda()) }
    val counts by remember(menuPrefs) { menuPrefs.countsFlow }.collectAsState(initial = menuPrefs.layoutCounts())

    val wallpaperSkin by services.wallpaperSkin.skin.collectAsState()
    val customSkinCatalog by services.customSkins.skins.collectAsState()
    val settingsController = remember(services, wallpaperSkin, customSkinCatalog) {
        SettingsController(services.prefs.asSettingsStore(), services.skins, scope)
    }
    var wallpaperChoice by remember { mutableStateOf<SettingsRowId?>(null) }
    val settingsState by settingsController.state.collectAsState()
    val hiddenController = remember(services) {
        HiddenAppsController(services.layout, services.unlockedApps, scope)
    }
    val backupController = remember(services) {
        BackupController(LayoutBackupService(services.layout, AndroidBackupFileStore(context)), scope)
    }
    val onboardingController = remember(services) {
        OnboardingController(services.prefs.asOnboardingStore(), AndroidOnboardingProbe.of(context), scope)
    }
    val onboardingState by onboardingController.state.collectAsState()
    LaunchedEffect(onboardingState.completed) {
        if (onboardingState.completed && page == SettingsPage.Onboarding) page = SettingsPage.Root
    }

    val backTarget = remember { SettingsBackTarget() }
    var previewSkin by remember { mutableStateOf<Skin?>(null) }
    val goBack = { if (previewSkin != null) previewSkin = null else if (page == SettingsPage.Root) onClose() else page = settingsParent(page) }
    BackHandler { goBack() }
    var arcChoice by remember { mutableStateOf(false) }
    var wallpaperDimChoice by remember { mutableStateOf(false) }

    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsBackTarget provides backTarget) {
    Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt())).settingsExit {
        if (previewSkin != null) previewSkin = null else (backTarget.action ?: goBack).invoke()
    }) {
        if (wallpaperDimChoice) androidx.compose.material3.AlertDialog(
            onDismissRequest = { wallpaperDimChoice = false }, containerColor = Color(skin.colors.surface.toInt()),
            title = { BasicText("Dim", style = settingsText(skin)) },
            text = { androidx.compose.material3.Slider(value = look.wallpaperDimPercent.toFloat(), valueRange = 0f..80f, steps = 7,
                onValueChange = { value -> onChangeLook { it.copy(wallpaperDimPercent = (value / 10).toInt().coerceIn(0, 8) * 10) } }) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { wallpaperDimChoice = false }) {
                BasicText("Done · ${look.wallpaperDimPercent}%", style = settingsText(skin, skin.colors.accent))
            } })
        if (arcChoice) androidx.compose.material3.AlertDialog(
            onDismissRequest = { arcChoice = false }, containerColor = Color(skin.colors.surface.toInt()),
            title = { BasicText("Arc layout", style = settingsText(skin)) },
            text = { Column {
                listOf("Staggered", "Aligned").forEachIndexed { index, label ->
                    SettingRow(label, skin, SettingKind.Action, icon = if (alignedArcs == (index == 1)) "✓" else "○") {
                        onSetAlignedArcs(index == 1); arcChoice = false
                    }
                }
            } }, confirmButton = { androidx.compose.material3.TextButton(onClick = { arcChoice = false }) {
                BasicText("Back", style = settingsText(skin, skin.colors.accent))
            } })
        wallpaperChoice?.let { which ->
            val isMode = which == SettingsRowId.WallpaperSkinMode
            val labels = if (isMode) WallpaperMode.entries.map { it.label } else WallpaperLook.entries.map { it.label }
            val current = if (isMode) settings.wallpaperMode.ordinal else settings.wallpaperLook.ordinal
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { wallpaperChoice = null }, containerColor = Color(skin.colors.surface.toInt()),
                title = { BasicText(if (isMode) "Wallpaper skin colours" else "Wallpaper skin based on", style = settingsText(skin)) },
                text = { Column {
                    labels.forEachIndexed { index, label ->
                        SettingRow(label, skin, SettingKind.Action, icon = if (current == index) "✓" else "○") {
                            scope.launch {
                                if (isMode) services.prefs.setWallpaperMode(WallpaperMode.entries[index])
                                else services.prefs.setWallpaperLook(WallpaperLook.entries[index])
                            }
                            wallpaperChoice = null
                        }
                    }
                } }, confirmButton = { androidx.compose.material3.TextButton(onClick = { wallpaperChoice = null }) {
                    BasicText("Back", style = settingsText(skin, skin.colors.accent))
                } })
        }
        androidx.compose.animation.Crossfade(targetState = page,
            animationSpec = androidx.compose.animation.core.tween(skin.motion.durShort), label = "Settings page") { shownPage ->
        when (shownPage) {
            // OPUS1: the live home shows the hub panel at the chosen opacity over the real background.
            SettingsPage.Panel -> PreviewSettingsPage(skin, "Panel", onBack = { page = SettingsPage.Root },
                preview = { HomePreview(services, skin, look) }) {
                SettingsGroup("Hub panel", skin, footer = "Lower values let more of the wallpaper show through the panel.") {
                    SettingsVisualChoice("Panel opacity", Metrics.panelOpacitySteps.indexOf(look.panelOpacityPercent).coerceAtLeast(0),
                        Metrics.panelOpacitySteps.map { "$it %" }, skin, "How solid the stats panel above the arcs is",
                        divider = false) { index -> onChangeLook { it.copy(panelOpacityPercent = Metrics.panelOpacitySteps[index]) } }
                }
            }
            SettingsPage.HubOptions -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                HubPageOptionsContent(hubOptions, skin, hubConfig,
                    { change -> scope.launch { hubStore.update(change) } },
                    onFullSettings = { page = it }, modifier = Modifier.padding(horizontal = Metrics.textGutterDp.dp))
            }

            SettingsPage.Wallpaper -> WallpaperSettingsPage(skin, look, services, onChangeLook, searchTarget) { page = SettingsPage.Root }
            SettingsPage.LayoutCounts -> LayoutCountsPage(skin, services, look, counts, alignedArcs,
                onBack = { page = SettingsPage.Root }) { next ->
                menuPrefs.setLayoutCounts(next)
                scope.launch { services.layout.updateLayout { com.brightblu.parc.model.LayoutCounts.migrate(it, next.arcs) } }
            }
            SettingsPage.Search -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                SettingsContent(skin, "Search") { SearchSettingsSection(skin) }
            }
            SettingsPage.MainApps -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                Column(Modifier.fillMaxSize().padding(horizontal = Metrics.textGutterDp.dp)
                    .padding(bottom = Metrics.keepOutBottomDp.dp)) {
                    MainAppsSettingsSection(skin, lockedPickerPackages) { page = SettingsPage.Root }
                }
            }
            SettingsPage.Rotation -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                EditorScaffold(EditorSpec.Rotation, skin, Modifier.padding(horizontal = Metrics.textGutterDp.dp), empty = true,
                    preview = { HomePreview(services, skin, look, caption = "Your arcs as they are turned now") }) {
                    BasicText("Each arc remembers its position. Apps stay upright.", style = settingsText(skin))
                }
            }
            SettingsPage.VersionHistory -> SettingsPageFrame(skin, onBack = { page = SettingsPage.About }) {
                val entries = remember(context) { loadChangelog(context) }
                SettingsContent(skin, "Version history") {
                    entries.forEach { ReleaseEntry(skin, it) }
                }
            }
            SettingsPage.About, SettingsPage.WhatsNew -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                SettingsContent(skin, "About Parc") {
                    ReleaseText(skin, "Parc ${BuildConfig.VERSION_NAME}", true)
                    ReleaseText(skin, "Built ${BuildConfig.BUILD_DATE} (UTC)")
                    ReleaseAction(skin, "What's new") { page = SettingsPage.WhatsNew }
                    ReleaseAction(skin, "Version history") { page = SettingsPage.VersionHistory }
                    if (page == SettingsPage.WhatsNew) {
                        val entries = remember(context) { loadChangelog(context) }
                        WhatsNewSheet(skin, entries.first()) { page = SettingsPage.About }
                    }
                    BasicText("A home screen with thumb-reach arcs. Settings and layout stay on this device.", style = settingsText(skin, skin.colors.textSecondary))
                }
            }
            SettingsPage.Root -> RootPage(
                skin = skin,
                rows = settingsRootRows(skin.name, alignedArcs,
                    settings.statsVisible, settings.statsExpanded, joystick, notificationAccess, badgesEnabled, badgesInAllApps, markWorkApps, hubDoubleTap,
                    gestures, appLabel, look, FocusDisplay.summary(focus, focusModes),
                    menuNotifications = menuNotifications, menuNotificationText = menuNotificationText,
                    headerTime = headerTime,
                    searchPhoneSettings = searchSnapshot.phoneSettingsEnabled,
                    searchFiles = searchSnapshot.filesEnabled,
                    searchEvents = searchSnapshot.eventsEnabled,
                    layoutCounts = counts,
                    agendaSummary = AgendaSettingsText.summary(agendaSettings),
                    lockedFoldersSummary = com.brightblu.parc.core.data.lock.LockedFolderText.summary(
                        settingsLayout.folders.count { it.locked }),
                    wallpaperMode = settings.wallpaperMode.takeIf { services.wallpaperSkin.supported },
                    wallpaperLook = settings.wallpaperLook).map { if (it.id == SettingsRowId.HubSize) it.copy(detail = hubConfig.size.name) else it }.filter { BuildConfig.FILE_SEARCH_ENABLED || it.id != SettingsRowId.SearchFiles },
                onRow = { id ->
                    when (id) {
                        SettingsRowId.ArcsShown, SettingsRowId.IconsPerArc -> page = SettingsPage.LayoutCounts
                        SettingsRowId.Search -> page = SettingsPage.Search
                        SettingsRowId.SearchPhoneSettings -> scope.launch { searchPrefs.setPhoneSettingsEnabled(!searchSnapshot.phoneSettingsEnabled) }
                        SettingsRowId.SearchFiles -> scope.launch { searchPrefs.setFilesEnabled(!searchSnapshot.filesEnabled) }
                        SettingsRowId.SearchEvents -> scope.launch { searchPrefs.setEventsEnabled(!searchSnapshot.eventsEnabled) }
                        SettingsRowId.MainApps -> page = editorDestinations.getValue(EditorSpec.MainApps)
                        SettingsRowId.Rotation -> page = editorDestinations.getValue(EditorSpec.Rotation)
                        SettingsRowId.About -> page = SettingsPage.About
                        SettingsRowId.StatsAlerts -> page = editorDestinations.getValue(EditorSpec.Alerts)
                        SettingsRowId.Focus -> page = SettingsPage.Focus
                        SettingsRowId.HubDoubleTap -> onSetHubDoubleTap(!hubDoubleTap)
                        SettingsRowId.SwipeUp -> page = SettingsPage.SwipeUp
                        SettingsRowId.HubLongPress -> page = SettingsPage.HubLongPress
                        SettingsRowId.Comms -> page = SettingsPage.Comms
                        SettingsRowId.HubPages -> page = editorDestinations.getValue(EditorSpec.HubPages)
                        SettingsRowId.HeaderTime -> scope.launch { hubStore.setHeaderTime(!headerTime) }
                        SettingsRowId.Agenda -> { agendaSettings = menuPrefs.agenda(); page = SettingsPage.Agenda }
                        SettingsRowId.Diagnostics -> page = SettingsPage.Diagnostics
                        SettingsRowId.NotificationAccess -> context.startActivity(access.settingsIntent())
                        SettingsRowId.NotificationBadges -> onSetBadgesEnabled(!badgesEnabled)
                        SettingsRowId.BadgesInAllApps -> onSetBadgesInAllApps(!badgesInAllApps)
                        SettingsRowId.MenuNotifications -> {
                            menuNotifications = !menuNotifications
                            menuPrefs.setMenuNotifications(menuNotifications)
                            MenuNotifications.applyEnabled(menuNotifications)
                        }
                        SettingsRowId.MenuNotificationText -> {
                            menuNotificationText = !menuNotificationText
                            menuPrefs.setMenuNotificationText(menuNotificationText)
                        }
                        SettingsRowId.MarkWorkApps -> onSetMarkWorkApps(!markWorkApps)
                        SettingsRowId.Folders -> page = editorDestinations.getValue(EditorSpec.Folders)
                        SettingsRowId.LockedFolders -> page = SettingsPage.LockedFolders
                        SettingsRowId.Appearance -> page = SettingsPage.Appearance
                        SettingsRowId.PanelOpacity -> page = SettingsPage.Panel
                        SettingsRowId.WallpaperReadability -> onChangeLook { it.copy(keepTextReadable = !it.wallpaperReadability, wallpaperReadability = !it.wallpaperReadability, imageReadabilityScrim = !it.wallpaperReadability) }
                        SettingsRowId.WallpaperImageBackup -> onChangeLook { it.copy(includeWallpaperInBackups = !it.includeWallpaperInBackups) }
                        SettingsRowId.ImageReadabilityScrim -> onChangeLook { it.copy(imageReadabilityScrim = !it.imageReadabilityScrim) }
                        SettingsRowId.WallpaperImage -> page = SettingsPage.Wallpaper
                        SettingsRowId.WallpaperDim -> wallpaperDimChoice = true
                        SettingsRowId.Look -> page = SettingsPage.Look
                        SettingsRowId.SkinStudio -> page = editorDestinations.getValue(EditorSpec.Skin)
                        SettingsRowId.WallpaperSkinMode, SettingsRowId.WallpaperSkinLook -> wallpaperChoice = id
                        SettingsRowId.Feel -> page = SettingsPage.Feel
                        SettingsRowId.Permissions -> page = SettingsPage.Permissions
                        SettingsRowId.ArcLayout -> arcChoice = true
                        SettingsRowId.StatsVisible -> scope.launch { services.prefs.setStatsVisible(!settings.statsVisible) }
                        SettingsRowId.HubSize -> page = SettingsPage.HubPages
                        SettingsRowId.Joystick -> onSetJoystick(!joystick)
                        SettingsRowId.HiddenApps -> page = editorDestinations.getValue(EditorSpec.HiddenApps)
                        SettingsRowId.Backup -> page = SettingsPage.Backup
                        SettingsRowId.RerunOnboarding -> {
                            scope.launch { services.prefs.setOnboardingCompleted(false) }
                            onboardingController.goTo(com.brightblu.parc.feature.settings.onboarding.OnboardingStep.DefaultHome)
                            page = SettingsPage.Onboarding
                        }
                        SettingsRowId.AndroidSettings -> onAndroidSettings()
                    }
                },
                onClose = onClose,
                initialSection = rootSection,
                onSectionChange = { rootSection = it },
                onJump = { searchTarget = it },
                hubConfig = hubConfig.takeIf { settings.statsVisible },
                onHubPage = { id ->
                    hubOptions = id
                    page = SettingsPage.HubOptions
                },
                leadingFor = { id -> settingsRowSample(id, skin, look, counts) },
            )
            SettingsPage.StatsAlerts -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                StatsAlertSettings(services.statsAlerts, skin)
            }
            SettingsPage.Comms, SettingsPage.CommsApps, SettingsPage.QuickReplies -> SettingsPageFrame(skin, onBack = { page = settingsParent(page) }) {
                Box(Modifier.padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.gridDp.dp * 2)) {
                    CommsSettings(services.comms, skin, apps.map { it.packageName to it.label },
                        section = when (page) { SettingsPage.CommsApps -> CommsSettingsSection.Apps
                            SettingsPage.QuickReplies -> CommsSettingsSection.Replies
                            else -> CommsSettingsSection.Summary },
                        onOpenApps = { page = SettingsPage.CommsApps }, onOpenReplies = { page = SettingsPage.QuickReplies })
                }
            }
            SettingsPage.HubPages -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                Column(Modifier.padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.gridDp.dp * 2)) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalHubSettings provides { page = it }) {
                        HubPageSettings(skin, hubConfig) { change -> scope.launch { hubStore.update(change) } }
                    }
                }
            }
            SettingsPage.Agenda -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                Box(Modifier.padding(horizontal = Metrics.textGutterDp.dp)) {
                    AgendaSettingsContent(skin, agendaSettings) { next ->
                        agendaSettings = next
                        menuPrefs.setAgenda(next)
                    }
                }
            }
            // T69: one action list per gesture, sharing the page and its two pickers.
            SettingsPage.SwipeUp, SettingsPage.HubLongPress -> {
                val slot = if (page == SettingsPage.SwipeUp) GestureSlot.SwipeUp else GestureSlot.HubLongPress
                GestureSettingsPage(skin, slot, gestures.actionFor(slot), hubConfig.pages, apps,
                    onPick = { onSetGesture(slot, it) },
                    onBack = { page = SettingsPage.Root })
            }
            // T83: a screenshot from the phone shows what the scene builder actually produced.
            SettingsPage.Diagnostics -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                val stored by remember(context) { SceneDiagnosticsStore(context) }.last
                    .collectAsState(initial = SceneDiagnostic())
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.gridDp.dp * 2),
                    verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp * 2),
                ) {
                    BasicText("Diagnostics", style = style(skin, skin.colors.textPrimary, skin.typography.title))
                    sceneDiagnosticLines(stored).forEach { line ->
                        BasicText(line, style = style(skin, skin.colors.textSecondary, skin.typography.caption))
                    }
                    val diagnosticsExporter = rememberDiagnosticsExporter()
                    DiagnosticsExport(skin, diagnosticsExporter)
                }
            }
            SettingsPage.LockedFolders -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                SettingsContent(skin, "Locked folders") { LockedFoldersContent(services, skin, lockGate) }
            }
            SettingsPage.Folders -> FolderEditorHost(services, skin, settingsHost = true, onClose = { page = SettingsPage.Root })
            SettingsPage.Appearance -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                ParcSettingsScreen(
                    state = settingsState,
                    skin = skin,
                    onSelectSkin = settingsController::selectSkin,
                    onSelectHaptics = settingsController::selectHaptics,
                    onSetClockVisible = settingsController::setClockVisible,
                    topPaddingDp = Metrics.gridDp * 2,
                    gallery = {
                        SkinGallery(settingsState, skin, services, look,
                            onPreview = { previewSkin = it }, onEdit = { page = SettingsPage.SkinStudio })
                    },
                    extra = {
                        if (settingsState.selectedSkinId == "wallpaper") SettingsGroup("Wallpaper skin", skin) {
                            SettingsAnchor(SettingsRowId.WallpaperSkinMode, searchTarget) {
                                SettingRow("Colours", skin, SettingKind.Choice, "Dark or light colours taken from your wallpaper", value = settings.wallpaperMode.label) { wallpaperChoice = SettingsRowId.WallpaperSkinMode }
                            }
                            SettingsAnchor(SettingsRowId.WallpaperSkinLook, searchTarget) {
                                SettingRow("Based on", skin, SettingKind.Choice, "Which skin’s frames and shapes it uses", value = settings.wallpaperLook.label, divider = false) { wallpaperChoice = SettingsRowId.WallpaperSkinLook }
                            }
                        }
                        SettingsGroup("Your own skin", skin) {
                            SettingsAnchor(SettingsRowId.SkinStudio, searchTarget) {
                                SettingRow("Skin editor", skin, SettingKind.Page, "Change colours, import or export a skin",
                                    leading = { SkinSwatch(skin) }, divider = false) { page = SettingsPage.SkinStudio }
                            }
                        }
                    },
                )
            }
            SettingsPage.Focus -> FocusSettingsPage(
                skin = skin,
                focus = focus,
                modes = focusModes,
                repository = services.focus,
                folders = settingsLayout.folders.map { folder ->
                    com.brightblu.parc.core.data.lock.LockedApps.folderForDisplay(folder,
                        folder.id in hiddenFolderIds, lockGate.hideName())
                },
                skins = services.skins,
                apps = apps,
                onBack = { page = SettingsPage.Root },
            )
            SettingsPage.SkinStudio -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                val custom by services.customSkins.skins.collectAsState()
                Box(Modifier.padding(horizontal = Metrics.textGutterDp.dp)) {
                    SkinStudioContent(
                        SkinStudioHost(
                            builtIn = services.skinProvider.available.filter { !SkinNaming.isCustomId(it.id) },
                            custom = custom,
                            activeId = settingsState.selectedSkinId,
                            saveSkin = { services.customSkins.save(it) },
                            deleteSkin = { services.customSkins.delete(it) },
                            useSkin = settingsController::selectSkin,
                        ), skin)
                }
            }
            SettingsPage.Feel -> FeelSettingsScreen(skin, hapticsEnabled = settings.haptics != com.brightblu.parc.core.data.HapticsLevel.Off, master = {
                SettingRow("Haptics", skin, SettingKind.Toggle, "Vibrate for taps and gestures", checked = settings.haptics != com.brightblu.parc.core.data.HapticsLevel.Off) {
                    settingsController.selectHaptics(if (settings.haptics == com.brightblu.parc.core.data.HapticsLevel.Off) com.brightblu.parc.core.data.HapticsLevel.Light else com.brightblu.parc.core.data.HapticsLevel.Off)
                }
            }) { page = SettingsPage.Root }
            SettingsPage.Permissions -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                SettingsContent(skin, "Permissions") { PermissionsContent(skin) }
            }
            SettingsPage.Look -> LookSettingsPage(
                skin = skin,
                look = look,
                iconPacks = services.appIndex.iconPacks,
                clockVisible = settings.clockVisible,
                onSetClockVisible = settingsController::setClockVisible,
                onChange = onChangeLook,
                onBack = { page = SettingsPage.Root },
                services = services,
            )
            SettingsPage.Hidden -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                HiddenApps(hiddenController, skin, Modifier.fillMaxSize(), topPaddingDp = Metrics.gridDp * 2)
            }
            SettingsPage.Backup -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                Column {
                    SettingRow("Include wallpaper in backups", skin, SettingKind.Toggle, "Add the private picture to exported backups", value = if (look.includeWallpaperInBackups) "On" else "Off", checked = look.includeWallpaperInBackups) { onChangeLook { it.copy(includeWallpaperInBackups = !it.includeWallpaperInBackups) } }
                    LayoutBackupScreen(backupController, skin, Modifier.weight(1f), topPaddingDp = Metrics.gridDp * 2)
                }
            }
            SettingsPage.Onboarding -> SettingsPageFrame(skin, onBack = { page = SettingsPage.Root }) {
                Onboarding(onboardingController, skin,
                    onOpenTarget = { AndroidOnboardingIntents.open(context, it) },
                    modifier = Modifier.fillMaxSize())
            }
        }
        }
        previewSkin?.let { candidate -> SkinFullPreview(candidate, services, look,
            onUse = { settingsController.selectSkin(candidate.id); previewSkin = null },
            onBack = { previewSkin = null }) }
    }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun RootPage(
    skin: Skin, rows: List<SettingsRow>, onRow: (SettingsRowId) -> Unit, onClose: () -> Unit,
    initialSection: SettingsSectionId? = null,
    onSectionChange: (SettingsSectionId?) -> Unit = {},
    onJump: (SettingsRowId) -> Unit = {},
    hubConfig: HubConfig? = null,
    onHubPage: (String) -> Unit = {},
    /** OPUS1: a small live sample shown at the start of visual rows (skin, wallpaper, icons...). */
    leadingFor: (SettingsRowId) -> (@Composable () -> Unit)? = { null },
) {
    var query by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(initialSection) }
    LaunchedEffect(section) { onSectionChange(section) }
    var target by remember { mutableStateOf<SettingsRowId?>(null) }
    val scroll = rememberScrollState()
    var more by remember(section) { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(section) { if (target == null) scroll.scrollTo(0) }
    BackHandler(enabled = section != null) { section = null; target = null }
    SettingsPageFrame(skin, onBack = { if (section != null) { section = null; target = null } else onClose() }) {
        Column(Modifier.fillMaxSize().padding(horizontal = Metrics.textGutterDp.dp)) {
            BasicText(section?.title ?: "Parc Settings", style = style(skin, skin.colors.textPrimary, skin.typography.title))
            SettingsSearch(query, skin) { query = it }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(bottom = Metrics.keepOutBottomDp.dp)) {
                if (query.isNotBlank()) {
                    val found = filterSettingsRows(rows, query)
                    found.forEach { row ->
                        SettingRow(row.label, skin, SettingKind.Page, row.description, value = row.detail) {
                            query = ""
                            keyboard?.hide()
                            focusManager.clearFocus()
                            val id = settingsJump(row.id)
                            onJump(id)
                            when {
                                id in wallpaperRows -> onRow(SettingsRowId.WallpaperImage)
                                id in skinRows -> onRow(SettingsRowId.Appearance)
                                id == SettingsRowId.WallpaperImageBackup -> onRow(SettingsRowId.Backup)
                                else -> { section = row.section; target = id }
                            }
                        }
                    }
                    val pagesFound = hubConfig?.orderedPages.orEmpty().filter { it.contains(query.trim(), true) }
                    pagesFound.forEach { id -> SettingRow(id.replaceFirstChar { it.uppercase() }, skin, SettingKind.Page, "Change this hub page’s options", value = if (id in hubConfig!!.hiddenPages) "Hidden" else "Shown") { keyboard?.hide(); focusManager.clearFocus(); query = ""; onHubPage(id) } }
                    if (found.isEmpty() && pagesFound.isEmpty()) BasicText("No settings match", style = settingsText(skin))
                } else if (section == null) {
                    SettingsSectionId.entries.forEach { group ->
                        val groupRows = rows.filter { it.section == group }
                        fun value(id: SettingsRowId) = groupRows.firstOrNull { it.id == id }?.detail.orEmpty()
                        val summary = when (group) {
                            SettingsSectionId.Appearance -> value(SettingsRowId.Appearance)
                            SettingsSectionId.Layout -> "${value(SettingsRowId.ArcsShown)} arcs"
                            SettingsSectionId.Feel -> "Hub double-tap: ${value(SettingsRowId.HubDoubleTap)}"
                            SettingsSectionId.Stats -> value(SettingsRowId.StatsVisible)
                            SettingsSectionId.Search -> "${groupRows.count { it.checked == true }} extra sources enabled"
                            SettingsSectionId.Notifications -> "Badges: ${value(SettingsRowId.NotificationBadges)}"
                            SettingsSectionId.Focus -> value(SettingsRowId.Focus)
                            SettingsSectionId.Permissions -> "Notification access: ${value(SettingsRowId.NotificationAccess)}"
                            SettingsSectionId.Backup -> "Parc ${BuildConfig.VERSION_NAME}"
                        }
                        SettingsCard(skin) {
                            SettingRow(group.title, skin, SettingKind.Page, sectionDescription(group), value = summary,
                                icon = settingsGroupIcon(group)) { section = group; target = null }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = Metrics.gridDp.dp * 3))
                    }
                } else {
                    val optional = setOf(SettingsRowId.Diagnostics, SettingsRowId.AndroidSettings, SettingsRowId.MarkWorkApps, SettingsRowId.ArcLayout, SettingsRowId.Rotation, SettingsRowId.HeaderTime)
                    val allRows = rows.filter { it.section == section && it.id !in wallpaperRows && it.id !in skinRows && it.id != SettingsRowId.WallpaperImageBackup && !(hubConfig != null && it.id == SettingsRowId.Agenda) }.sortedBy { if (section == SettingsSectionId.Appearance) listOf(SettingsRowId.Appearance, SettingsRowId.WallpaperImage, SettingsRowId.Look, SettingsRowId.PanelOpacity).indexOf(it.id) else 0 }
                    val showMore = more || target in optional
                    val groupRows = allRows.filter { it.id !in optional || showMore }
                    val hasMore = allRows.any { it.id in optional }
                    // OPUS1: one explanation line, one card of rows, "More options" as the card's last row.
                    BasicText(sectionDescription(section!!), style = settingsText(skin, skin.colors.textSecondary, true),
                        modifier = Modifier.padding(start = Metrics.gridDp.dp * 4, top = Metrics.gridDp.dp * 2))
                    SettingsGroup(null, skin) {
                        groupRows.forEachIndexed { index, row ->
                            SettingsAnchor(row.id, target) {
                                SettingRow(row.label, skin, row.kind, row.description, value = row.detail,
                                    checked = row.checked == true, icon = if (row.id == SettingsRowId.AndroidSettings) "↗" else "+",
                                    leading = leadingFor(row.id), divider = index < groupRows.lastIndex || hasMore) { onRow(row.id) }
                            }
                        }
                        if (hasMore) SettingRow(if (showMore) "Fewer options" else "More options", skin, SettingKind.Action,
                            if (showMore) "Hide the less used controls" else "Show less used controls",
                            icon = if (showMore) "−" else "+", divider = false) { more = !showMore; target = null }
                    }
                    if (section == SettingsSectionId.Stats && hubConfig != null) SettingsGroup("Pages", skin,
                        footer = "Tap a page to change what it shows. Swipe the hub on Home to move between pages.") {
                        hubConfig.orderedPages.forEachIndexed { index, id ->
                            SettingRow(id.replaceFirstChar { it.uppercase() }, skin, SettingKind.Page, "Change what this hub page shows",
                                value = if (id in hubConfig.hiddenPages) "Hidden" else "Shown",
                                divider = index < hubConfig.orderedPages.lastIndex) { onHubPage(id) }
                        }
                    }
                }
            }
        }
    }
}

/** OPUS1: the live sample beside a visual row; null for rows that have nothing to show. */
internal fun settingsRowSample(id: SettingsRowId, skin: Skin, look: LookSettings,
    counts: com.brightblu.parc.model.LayoutCounts): (@Composable () -> Unit)? = when (id) {
    SettingsRowId.Appearance -> { { SkinSwatch(skin) } }
    SettingsRowId.WallpaperImage -> { { WallpaperSwatch(skin, look) } }
    SettingsRowId.Look -> { { IconSample(skin, look.iconShape, SettingsSampleRules.iconFraction(look.iconSizeDp)) } }
    SettingsRowId.PanelOpacity -> { { PanelSample(skin, look.panelOpacityPercent) } }
    SettingsRowId.ArcsShown, SettingsRowId.IconsPerArc, SettingsRowId.Rotation -> { { ArcsSample(skin, counts.arcs, counts.icons) } }
    else -> null
}

/** The same leading Back arrow stays outside each screen's scroll and clear of its title. */
@Composable
internal fun SettingsPageFrame(skin: Skin, onBack: () -> Unit, content: @Composable () -> Unit) {
    RegisterSettingsBack(onBack)
    SettingsTheme(skin) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = Metrics.keepOutTopDp.dp, start = Metrics.gridDp.dp)
            .heightIn(min = Metrics.touchTargetDp.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.heightIn(min = Metrics.touchTargetDp.dp).clickable(onClick = onBack)
                .padding(horizontal = Metrics.gridDp.dp * 4), contentAlignment = Alignment.Center) {
                BasicText("←  Back", style = settingsText(skin, skin.colors.accent))
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
    }
}

@Composable
internal fun SettingsContent(skin: Skin, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = Metrics.textGutterDp.dp).padding(bottom = Metrics.keepOutBottomDp.dp)) {
        BasicText(title, style = style(skin, skin.colors.textPrimary, skin.typography.title))
        content()
    }
}

private fun style(skin: Skin, tint: Long, token: com.brightblu.parc.model.TypeToken): TextStyle = TextStyle(
    fontFamily = if (skin.typography.fallbackFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else null,
    color = Color(tint.toInt()), fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight), letterSpacing = token.trackingSp.sp,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SettingsAnchor(id: SettingsRowId, target: SettingsRowId?, content: @Composable () -> Unit) {
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var measured by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { Metrics.keepOutBottomDp.dp.toPx() }
    Column(Modifier.bringIntoViewRequester(requester).onSizeChanged { measured = it }) { content() }
    LaunchedEffect(target, measured) {
        if (target == id && measured.height > 0) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            requester.bringIntoView(androidx.compose.ui.geometry.Rect(0f, 0f,
                measured.width.toFloat(), measured.height + margin))
        }
    }
}
