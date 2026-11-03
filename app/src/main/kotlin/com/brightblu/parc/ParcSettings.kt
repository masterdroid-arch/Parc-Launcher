package com.brightblu.parc

import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.brightblu.parc.core.data.ParcSettings
import com.brightblu.parc.core.data.backup.AndroidBackupFileStore
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService
import com.brightblu.parc.core.data.skins.SkinNaming
import com.brightblu.parc.feature.settings.backup.BackupController
import com.brightblu.parc.feature.settings.backup.LayoutBackupScreen
import com.brightblu.parc.feature.settings.backup.LayoutBackupService
import com.brightblu.parc.feature.settings.hidden.HiddenApps
import com.brightblu.parc.feature.settings.hidden.HiddenAppsController
import com.brightblu.parc.feature.settings.notifications.NotificationAccessLink
import com.brightblu.parc.feature.settings.notifications.NotificationAccessStatus
import com.brightblu.parc.feature.settings.onboarding.AndroidOnboardingIntents
import com.brightblu.parc.feature.settings.onboarding.AndroidOnboardingProbe
import com.brightblu.parc.feature.settings.onboarding.Onboarding
import com.brightblu.parc.feature.settings.onboarding.OnboardingController
import com.brightblu.parc.feature.settings.onboarding.asOnboardingStore
import com.brightblu.parc.feature.settings.permissions.PermissionsContent
import com.brightblu.parc.feature.settings.skins.MainAppsSettingsSection
import com.brightblu.parc.feature.settings.skins.SearchSettingsSection
import com.brightblu.parc.feature.settings.skins.SettingsController
import com.brightblu.parc.feature.settings.skins.SkinStudioContent
import com.brightblu.parc.feature.settings.skins.SkinStudioHost
import com.brightblu.parc.feature.settings.skins.asSettingsStore
import com.brightblu.parc.feature.settings.ui.EditorScaffold
import com.brightblu.parc.feature.settings.ui.EditorSpec
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsGroup
import com.brightblu.parc.feature.settings.ui.SettingsSearch
import com.brightblu.parc.feature.settings.ui.SettingsTheme
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.HubAppearance
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcPage
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinSafety
import com.brightblu.parc.model.Springs
import com.brightblu.parc.model.locked
import com.brightblu.parc.model.rememberPageInsets
import com.brightblu.parc.model.textFamily
import com.brightblu.parc.ring.FeelEvent
import com.brightblu.parc.ring.FeelPlayer
import com.brightblu.parc.ring.FeelPrefs
import com.brightblu.parc.ring.HapticPreset
import com.brightblu.parc.ring.SoundTheme
import kotlinx.coroutines.launch

/**
 * Pages of the Parc Settings host. The first eight are the root and the seven pages of T177 ([SettingsHome]); the rest are
 * editors and lists those pages open.
 */
internal enum class SettingsPage {
    Root, Look, Hubs, Layout, Gestures, StatsPages, SearchNotifications, BackupAbout,
    SkinStudio, MainApps, Rotation, Folders, LockedFolders, Hidden, HubPages, HubOptions, Agenda, StatsAlerts, Search,
    Comms, CommsApps, QuickReplies, Focus, Permissions, Onboarding, Diagnostics, Backup, WhatsNew, VersionHistory, SwipeUp, HubLongPress,
}

internal val SettingsHome.settingsPage: SettingsPage get() = SettingsPage.valueOf(name)
internal val SettingsPage.home: SettingsHome? get() = SettingsHome.entries.firstOrNull { it.name == name }

/** One step out, shared by the Back arrow and system Back: an editor goes back to the page whose row opened it. */
internal fun settingsParent(page: SettingsPage): SettingsPage = when (page) {
    SettingsPage.Root -> SettingsPage.Root
    SettingsPage.CommsApps, SettingsPage.QuickReplies -> SettingsPage.Comms
    SettingsPage.HubOptions -> SettingsPage.StatsPages
    SettingsPage.Onboarding -> SettingsPage.BackupAbout
    else -> if (page.home != null) SettingsPage.Root
        else SettingsRegistry.all.firstOrNull { it.opens == page }?.page?.settingsPage ?: SettingsPage.Root
}

/** Stable navigation identities of the editor scaffolds. */
internal val editorDestinations = mapOf(
    EditorSpec.MainApps to SettingsPage.MainApps, EditorSpec.Folders to SettingsPage.Folders,
    EditorSpec.HiddenApps to SettingsPage.Hidden, EditorSpec.Rotation to SettingsPage.Rotation,
    EditorSpec.HubPages to SettingsPage.HubPages, EditorSpec.Agenda to SettingsPage.Agenda,
    EditorSpec.Alerts to SettingsPage.StatsAlerts, EditorSpec.Skin to SettingsPage.SkinStudio,
    EditorSpec.CommsApps to SettingsPage.CommsApps, EditorSpec.QuickReplies to SettingsPage.QuickReplies,
)

/**
 * The Parc Settings host (T30, reworked in T175 to T178): a root with the seven pages and one search box, every page
 * generated from [SettingsRegistry] over one [SettingsSnapshot], and every change saved by [SettingsWriter].
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
    initialRow: String? = null,
) {
    // OPUS6 (audit F7): Settings is always drawn readably, even when the active skin's text is not.
    val activeSkin = skin
    @Suppress("NAME_SHADOWING") val skin = SkinSafety.forSafetyScreens(skin)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val apps by services.unlockedApps.collectAsState()
    val lockGate = rememberFolderLockGate(services)
    val lockWindows by services.folderLock.windows.collectAsState()
    val lockedPickerPackages by services.lockedPackages.collectAsState()
    val settingsLayout by services.layout.layout.collectAsState()
    val hiddenFolderIds = lockWindows.let { settingsLayout.folders.filter { lockGate.isFolderLockedNow(it.id) }.map { it.id }.toSet() }
    val hubStore = remember(context) { HubConfigStore(context) }
    val appearanceStore = remember(context) { HubAppearanceStore(context) }
    val hubAppearance by appearanceStore.appearance.collectAsState(initial = HubAppearance())
    val hubConfig by hubStore.config.collectAsState(initial = HubConfig())
    val searchPrefs = remember(context) { com.brightblu.parc.core.data.search.SearchPrefs.create(context) }
    val searchSnapshot by searchPrefs.snapshot.collectAsState(initial = com.brightblu.parc.core.data.search.SearchPrefsSnapshot())
    val feelStore = remember(context) { FeelStore(context) }
    val feelPrefs by feelStore.prefs.collectAsState(initial = FeelPrefs())
    val arcFeel by feelStore.arcFeel.collectAsState(initial = com.brightblu.parc.model.ArcFeel())
    val emptyPacks = remember { kotlinx.coroutines.flow.MutableStateFlow<List<com.brightblu.parc.core.data.icons.IconPack>>(emptyList()) }
    val noPack = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val iconPacks by (services.appIndex.iconPacks?.packs ?: emptyPacks).collectAsState()
    val iconPack by (services.appIndex.iconPacks?.selection ?: noPack).collectAsState()
    LaunchedEffect(services) { services.appIndex.iconPacks?.refresh() }
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
    val focus by services.focus.state.collectAsState()
    val focusModes by services.focus.modes.collectAsState()
    var page by remember { mutableStateOf(initialPage) }
    var target by remember { mutableStateOf(initialRow) }
    var hubOptions by remember { mutableStateOf("system") }
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
    val settingsState by settingsController.state.collectAsState()
    val hiddenController = remember(services) { HiddenAppsController(services.layout, services.unlockedApps, scope) }
    val backupController = remember(services) {
        BackupController(LayoutBackupService(services.layout, AndroidBackupFileStore(context)), scope)
    }
    val onboardingController = remember(services) {
        OnboardingController(services.prefs.asOnboardingStore(), AndroidOnboardingProbe.of(context), scope)
    }
    val onboardingState by onboardingController.state.collectAsState()
    LaunchedEffect(onboardingState.completed) {
        if (onboardingState.completed && page == SettingsPage.Onboarding) page = SettingsPage.BackupAbout
    }
    val maxIcons = remember(services.geometry, alignedArcs) {
        val g = services.geometry
        com.brightblu.parc.ring.RingLayout(g.widthDp, g.heightDp, g.insets, g.options.copy(alignAcrossArcs = alignedArcs))
            .arcs.maxOf { it.slotCapacity }.coerceAtLeast(3)
    }

    // T175: everything the pages read, as one value.
    val snapshot = SettingsSnapshot(
        skins = settingsState.skins, skinId = settingsState.selectedSkinId, look = look, hub = hubAppearance, counts = counts,
        maxIconsPerArc = maxIcons, aligned = alignedArcs, joystick = joystick, hubDoubleTap = hubDoubleTap, gestures = gestures,
        haptics = settings.haptics, feel = feelPrefs, arcFeel = arcFeel, clockVisible = settings.clockVisible,
        iconPacks = iconPacks.map { it.packageName to it.label }, iconPack = iconPack, statsVisible = settings.statsVisible,
        hubSize = hubConfig.size, searchPhoneSettings = searchSnapshot.phoneSettingsEnabled,
        searchFiles = searchSnapshot.filesEnabled, searchEvents = searchSnapshot.eventsEnabled,
        fileSearchAvailable = BuildConfig.FILE_SEARCH_ENABLED, badges = badgesEnabled, badgesInAllApps = badgesInAllApps,
        menuNotifications = menuNotifications, menuNotificationText = menuNotificationText, markWorkApps = markWorkApps,
        notificationAccess = notificationAccess, wallpaperSkinSupported = services.wallpaperSkin.supported,
        wallpaperMode = settings.wallpaperMode, wallpaperLook = settings.wallpaperLook,
        focusSummary = FocusDisplay.summary(focus, focusModes),
        lockedFoldersSummary = com.brightblu.parc.core.data.lock.LockedFolderText.summary(settingsLayout.folders.count { it.locked }),
        agendaSummary = AgendaSettingsText.summary(agendaSettings),
        swipeUpLabel = gestureActionLabel(gestures.swipeUp, appLabel), hubLongPressLabel = gestureActionLabel(gestures.hubLongPress, appLabel),
    )
    val lookStore = remember(context) { ParcLookStore(context) }
    val writer = remember(services) {
        SettingsWriter(look = { change -> onChangeLook(change) }, hub = { change -> appearanceStore.update(change) },
            prefs = services.prefs, ui = menuPrefs, hubStore = hubStore, search = searchPrefs, feel = feelStore,
            iconPacks = services.appIndex.iconPacks, layout = services.layout,
            live = LiveSinks(onSetAlignedArcs, onSetJoystick, onSetHubDoubleTap, onSetBadgesEnabled, onSetBadgesInAllApps, onSetMarkWorkApps))
    }
    val player = remember { FeelPlayer() }
    DisposableEffect(player) { onDispose { player.release() } }
    val onValue: (SettingDef, Int) -> Unit = { def, index ->
        val next = def.write?.invoke(snapshot, index) ?: snapshot
        // Live mirrors of values this host keeps itself.
        menuNotifications = next.menuNotifications; menuNotificationText = next.menuNotificationText
        if (next.skinId != snapshot.skinId) settingsController.selectSkin(next.skinId)
        if (next.feel != snapshot.feel) player.play(view, if (next.feel.sound != snapshot.feel.sound || next.feel.volumePercent != snapshot.feel.volumePercent)
            FeelEvent.Tick else FeelEvent.Confirm, if (next.feel.haptic != snapshot.feel.haptic) next.feel.copy(sound = SoundTheme.Off) else next.feel.copy(haptic = HapticPreset.Off))
        scope.launch { writer.persist(snapshot, next.copy(skinId = if (next.skinId != snapshot.skinId) snapshot.skinId else next.skinId)) }
    }
    var chooseWallpaper by remember { mutableStateOf(0) }
    val onOpen: (SettingDef) -> Unit = { def ->
        when (def.id) {
            "NotificationAccess" -> context.startActivity(access.settingsIntent())
            "AndroidSettings" -> onAndroidSettings()
            "RerunOnboarding" -> {
                scope.launch { services.prefs.setOnboardingCompleted(false) }
                onboardingController.goTo(com.brightblu.parc.feature.settings.onboarding.OnboardingStep.DefaultHome)
                page = SettingsPage.Onboarding
            }
            "Agenda" -> { agendaSettings = menuPrefs.agenda(); page = SettingsPage.Agenda }
            else -> def.opens?.let { page = it }
        }
    }

    val backTarget = remember { SettingsBackTarget() }
    var previewSkin by remember { mutableStateOf<Skin?>(null) }
    val goBack = { if (previewSkin != null) previewSkin = null else if (page == SettingsPage.Root) onClose() else { page = settingsParent(page) } }
    BackHandler { goBack() }

    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsBackTarget provides backTarget) {
    Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt())).settingsExit {
        if (previewSkin != null) previewSkin = null else (backTarget.action ?: goBack).invoke()
    }) {
        androidx.compose.animation.Crossfade(targetState = page,
            animationSpec = androidx.compose.animation.core.spring(Springs.sheet.damping, Springs.sheet.stiffness), label = "Settings page") { shownPage ->
        val back = { page = settingsParent(shownPage) }
        val home = shownPage.home
        if (home != null) {
            // T177/T178: Look, Hubs and Layout carry the live Home preview, drawn by Home's own code with the values being edited.
            val body: @Composable () -> Unit = {
                SettingsIntro(skin, home.intro)
                RegistryRows(home, snapshot, skin, target, onValue, onOpen,
                    custom = { def -> RegistryCustom(def, snapshot, skin, services, look, onChangeLook, chooseWallpaper,
                        onChoose = { chooseWallpaper++ }, onValue = onValue,
                        onPreviewSkin = { previewSkin = it }, onEditSkin = { page = SettingsPage.SkinStudio },
                        onWhatsNew = { page = SettingsPage.WhatsNew }) },
                    footer = { group -> when (group) {
                        "Glass, both hubs" -> "One glass for both cards, in every skin. Off gives flat cards at their opacity."
                        "Wallpaper" -> if (look.wallpaper == com.brightblu.parc.model.WallpaperStyle.System)
                            "Parc cannot read the phone wallpaper's picture, so glass frosts its main colour." else ""
                        else -> ""
                    } })
                if (home == SettingsHome.StatsPages && settings.statsVisible) SettingsGroup("Pages", skin,
                    footer = "Tap a page to change what it shows. Swipe the hub on Home to move between pages.") {
                    hubConfig.orderedPages.forEachIndexed { index, id ->
                        SettingRow(id.replaceFirstChar { it.uppercase() }, skin, SettingKind.Page, "Change what this hub page shows",
                            value = if (id in hubConfig.hiddenPages) "Hidden" else "Shown",
                            divider = index < hubConfig.orderedPages.lastIndex) { hubOptions = id; page = SettingsPage.HubOptions }
                    }
                }
            }
            if (home == SettingsHome.Look || home == SettingsHome.Hubs || home == SettingsHome.Layout) {
                PreviewSettingsPage(skin, home.title, back, preview = {
                    HomePreview(services, activeSkin, look, counts = counts, aligned = alignedArcs, appearance = hubAppearance)
                }) { body() }
            } else SettingsPageFrame(skin, onBack = back) { SettingsContent(skin, home.title) { body() } }
        } else when (shownPage) {
            SettingsPage.Root -> RootPage(skin, snapshot, hubConfig.takeIf { settings.statsVisible },
                onOpen = { home -> target = null; page = home.settingsPage },
                onJump = { def -> target = def.id; page = def.page.settingsPage },
                onHubPage = { id -> hubOptions = id; page = SettingsPage.HubOptions }, onClose = onClose)
            SettingsPage.HubOptions -> SettingsPageFrame(skin, onBack = back) {
                ParcPage(safeTop = false, scroll = false, padBottom = false) {
                    HubPageOptionsContent(hubOptions, skin, hubConfig, { change -> scope.launch { hubStore.update(change) } },
                        onFullSettings = { page = it })
                }
            }
            SettingsPage.Search -> SettingsPageFrame(skin, onBack = back) { SettingsContent(skin, "Search") { SearchSettingsSection(skin) } }
            SettingsPage.MainApps -> SettingsPageFrame(skin, onBack = back) {
                ParcPage(safeTop = false, scroll = false, padBottom = false) { MainAppsSettingsSection(skin, lockedPickerPackages) { back() } }
            }
            SettingsPage.Rotation -> SettingsPageFrame(skin, onBack = back) {
                ParcPage(safeTop = false, scroll = false, padBottom = false) {
                    EditorScaffold(EditorSpec.Rotation, skin, empty = true,
                        preview = { HomePreview(services, activeSkin, look, caption = "Your arcs as they are turned now", appearance = hubAppearance) }) {
                        BasicText("Each arc remembers its position. Apps stay upright.", style = settingsText(skin))
                    }
                }
            }
            SettingsPage.VersionHistory -> SettingsPageFrame(skin, onBack = back) {
                val entries = remember(context) { loadChangelog(context) }
                SettingsContent(skin, "Version history") { entries.forEach { ReleaseEntry(skin, it) } }
            }
            SettingsPage.WhatsNew -> SettingsPageFrame(skin, onBack = back) {
                val entries = remember(context) { loadChangelog(context) }
                SettingsContent(skin, "What's new") { ReleaseEntry(skin, entries.last()) }
            }
            SettingsPage.StatsAlerts -> SettingsPageFrame(skin, onBack = back) {
                ParcPage(safeTop = false, scroll = false, padBottom = false) { StatsAlertSettings(services.statsAlerts, skin) }
            }
            SettingsPage.Comms, SettingsPage.CommsApps, SettingsPage.QuickReplies -> SettingsPageFrame(skin, onBack = back) {
                ParcPage(safeTop = false, scroll = false, padBottom = false) {
                    CommsSettings(services.comms, skin, apps.map { it.packageName to it.label },
                        section = when (shownPage) { SettingsPage.CommsApps -> CommsSettingsSection.Apps
                            SettingsPage.QuickReplies -> CommsSettingsSection.Replies
                            else -> CommsSettingsSection.Summary },
                        onOpenApps = { page = SettingsPage.CommsApps }, onOpenReplies = { page = SettingsPage.QuickReplies })
                }
            }
            SettingsPage.HubPages -> SettingsPageFrame(skin, onBack = back) {
                ParcPage(safeTop = false, scroll = false, padBottom = false) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalHubSettings provides { page = it }) {
                        HubPageSettings(skin, hubConfig) { change -> scope.launch { hubStore.update(change) } }
                    }
                }
            }
            SettingsPage.Agenda -> SettingsPageFrame(skin, onBack = back) {
                ParcPage(safeTop = false, scroll = false, padBottom = false) {
                    AgendaSettingsContent(skin, agendaSettings) { next -> agendaSettings = next; menuPrefs.setAgenda(next) }
                }
            }
            SettingsPage.SwipeUp, SettingsPage.HubLongPress -> {
                val slot = if (shownPage == SettingsPage.SwipeUp) GestureSlot.SwipeUp else GestureSlot.HubLongPress
                GestureSettingsPage(skin, slot, gestures.actionFor(slot), hubConfig.pages, apps,
                    onPick = { onSetGesture(slot, it) }, onBack = back)
            }
            SettingsPage.Diagnostics -> SettingsPageFrame(skin, onBack = back) {
                val stored by remember(context) { SceneDiagnosticsStore(context) }.last.collectAsState(initial = SceneDiagnostic())
                ParcPage(safeTop = false, blockGapDp = Metrics.itemGapDp) {
                    BasicText("Diagnostics", style = style(skin, skin.colors.textPrimary, skin.typography.title))
                    sceneDiagnosticLines(stored).forEach { line ->
                        BasicText(line, style = style(skin, skin.colors.textSecondary, skin.typography.caption))
                    }
                    DiagnosticsExport(skin, rememberDiagnosticsExporter())
                }
            }
            SettingsPage.LockedFolders -> SettingsPageFrame(skin, onBack = back) {
                SettingsContent(skin, "Locked folders") { LockedFoldersContent(services, skin, lockGate) }
            }
            SettingsPage.Folders -> FolderEditorHost(services, skin, settingsHost = true, onClose = back)
            SettingsPage.Focus -> FocusSettingsPage(skin = skin, focus = focus, modes = focusModes, repository = services.focus,
                folders = settingsLayout.folders.map { folder ->
                    com.brightblu.parc.core.data.lock.LockedApps.folderForDisplay(folder, folder.id in hiddenFolderIds, lockGate.hideName())
                }, skins = services.skins, apps = apps, onBack = back)
            SettingsPage.SkinStudio -> SettingsPageFrame(skin, onBack = back) {
                val custom by services.customSkins.skins.collectAsState()
                ParcPage(safeTop = false, scroll = false, padBottom = false) {
                    SkinStudioContent(SkinStudioHost(
                        builtIn = services.skinProvider.available.filter { !SkinNaming.isCustomId(it.id) }, custom = custom,
                        activeId = settingsState.selectedSkinId, saveSkin = { services.customSkins.save(it) },
                        deleteSkin = { services.customSkins.delete(it) }, useSkin = settingsController::selectSkin), skin)
                }
            }
            SettingsPage.Permissions -> SettingsPageFrame(skin, onBack = back) { SettingsContent(skin, "Permissions") { PermissionsContent(skin) } }
            SettingsPage.Hidden -> SettingsPageFrame(skin, onBack = back) { HiddenApps(hiddenController, skin, Modifier.fillMaxSize(), topPaddingDp = 0f) }
            SettingsPage.Backup -> SettingsPageFrame(skin, onBack = back) {
                LayoutBackupScreen(backupController, skin, Modifier.fillMaxSize(), topPaddingDp = Metrics.rowGapDp)
            }
            SettingsPage.Onboarding -> SettingsPageFrame(skin, onBack = back) {
                Onboarding(onboardingController, skin, onOpenTarget = { AndroidOnboardingIntents.open(context, it) },
                    modifier = Modifier.fillMaxSize(), safeTop = false)
            }
            else -> Unit
        }
        }
        previewSkin?.let { candidate -> SkinFullPreview(candidate, services, look,
            onUse = { settingsController.selectSkin(candidate.id); previewSkin = null },
            onBack = { previewSkin = null }) }
    }
    }
}

/** The few rows that are not a plain control: the skin carousel, the background chooser and the build stamp. */
@Composable
private fun RegistryCustom(def: SettingDef, s: SettingsSnapshot, skin: Skin, services: LauncherServices, look: LookSettings,
    onChangeLook: ((LookSettings) -> LookSettings) -> Unit, chooseRequest: Int, onChoose: () -> Unit,
    onValue: (SettingDef, Int) -> Unit, onPreviewSkin: (Skin) -> Unit, onEditSkin: () -> Unit, onWhatsNew: () -> Unit) {
    when (def.id) {
        "Appearance" -> Column(Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp)) {
            val state = com.brightblu.parc.feature.settings.skins.SettingsUiState(s.skins, s.skinId)
            SkinGallery(state, skin, services, look, onPreview = onPreviewSkin, onEdit = onEditSkin)
        }
        "WallpaperImage" -> WallpaperChooser(def, s, skin, look, onChangeLook, chooseRequest, onChoose, onValue)
        "About" -> Column(Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp, vertical = Metrics.rowGapDp.dp)) {
            ReleaseText(skin, "Parc ${BuildConfig.VERSION_NAME}", true)
            BuildStamp.lines().forEach { (label, value) -> ReleaseText(skin, "$label: $value") }
            BasicText("A home screen with thumb-reach arcs. Settings and layout stay on this device.",
                style = settingsText(skin, skin.colors.textSecondary, true))
        }
    }
}

/**
 * The Settings root: the seven pages, one search box over every setting (by title, help, page and search words), and the
 * stats hub page names.
 */
@Composable
internal fun RootPage(skin: Skin, snapshot: SettingsSnapshot, hubConfig: HubConfig?, onOpen: (SettingsHome) -> Unit,
    onJump: (SettingDef) -> Unit, onHubPage: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    SettingsPageFrame(skin, onBack = onClose) {
        ParcPage(safeTop = false, scrollState = rememberScrollState(), header = {
            BasicText("Parc Settings", style = style(skin, skin.colors.textPrimary, skin.typography.title))
            Spacer(Modifier.height(Metrics.rowGapDp.dp))
            SettingsSearch(query, skin) { query = it }
            Spacer(Modifier.height(Metrics.rowGapDp.dp))
        }) {
            if (query.isNotBlank()) {
                val found = SettingsRegistry.search(query)
                val pagesFound = hubConfig?.orderedPages.orEmpty().filter { it.contains(query.trim(), true) }
                if (found.isEmpty() && pagesFound.isEmpty()) BasicText("No settings match", style = settingsText(skin),
                    modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
                else SettingsCard(skin) {
                    found.forEach { def ->
                        val hidden = !def.shown(snapshot)
                        SettingRow(def.fullTitle, skin, SettingKind.Page,
                            "${def.page.title} · ${def.group}" + if (hidden) " · not shown now, it has no effect here" else "",
                            value = def.value(snapshot)) {
                            query = ""; keyboard?.hide(); focusManager.clearFocus(); onJump(def)
                        }
                    }
                    pagesFound.forEach { id ->
                        SettingRow(id.replaceFirstChar { it.uppercase() }, skin, SettingKind.Page, "Stats hub page",
                            value = if (id in hubConfig!!.hiddenPages) "Hidden" else "Shown") {
                            keyboard?.hide(); focusManager.clearFocus(); query = ""; onHubPage(id)
                        }
                    }
                }
            } else SettingsHome.entries.forEach { home ->
                SettingsCard(skin) {
                    SettingRow(home.title, skin, SettingKind.Page, home.summary,
                        value = if (home == SettingsHome.BackupAbout) BuildConfig.VERSION_NAME else "",
                        icon = home.icon, divider = false) { onOpen(home) }
                }
                Spacer(Modifier.height(Metrics.rowGapDp.dp))
            }
        }
    }
}

/**
 * The same leading Back arrow stays outside each screen's scroll and clear of its title. The Back row owns the
 * status-bar and cutout space (ParcSpacing.pageTopDp); the page body below it is a [ParcPage] with `safeTop = false` (T166).
 */
@Composable
internal fun SettingsPageFrame(skin: Skin, onBack: () -> Unit, content: @Composable () -> Unit) {
    RegisterSettingsBack(onBack)
    SettingsTheme(skin) {
    Column(Modifier.fillMaxSize()) {
        val insets = rememberPageInsets()
        Row(Modifier.fillMaxWidth().padding(top = insets.topDp.dp, start = (insets.startDp - Metrics.screenGutterDp).dp)
            .heightIn(min = Metrics.touchTargetDp.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = Metrics.actionEdgeGapDp.dp).heightIn(min = Metrics.touchTargetDp.dp)
                .clickable(onClick = onBack).padding(horizontal = Metrics.itemGapDp.dp),
                contentAlignment = Alignment.Center) {
                BasicText("←  Back", style = settingsText(skin, skin.colors.accent))
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
    }
}

@Composable
internal fun SettingsContent(skin: Skin, title: String, content: @Composable () -> Unit) {
    ParcPage(safeTop = false) {
        BasicText(title, style = style(skin, skin.colors.textPrimary, skin.typography.title))
        content()
    }
}

private fun style(skin: Skin, tint: Long, token: com.brightblu.parc.model.TypeToken): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = Color(tint.toInt()), fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight), letterSpacing = token.trackingSp.sp,
)

/** Scrolls a row into view when a search result or a Home menu entry points at it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SettingsAnchor(id: String, target: String?, content: @Composable () -> Unit) {
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var measured by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { Metrics.keepOutBottomDp.dp.toPx() }
    Column(Modifier.bringIntoViewRequester(requester).onSizeChanged { measured = it }) { content() }
    LaunchedEffect(target, measured) {
        if (target == id && measured.height > 0) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            requester.bringIntoView(androidx.compose.ui.geometry.Rect(0f, 0f, measured.width.toFloat(), measured.height + margin))
        }
    }
}
