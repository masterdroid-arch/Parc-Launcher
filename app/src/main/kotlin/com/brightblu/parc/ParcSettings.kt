package com.brightblu.parc

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.brightblu.parc.core.data.editMainApp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.brightblu.parc.core.data.comms.CommsPackages
import com.brightblu.parc.core.data.backup.AndroidBackupFileStore
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService
import com.brightblu.parc.core.data.skins.SkinNaming
import com.brightblu.parc.feature.settings.backup.BackupController
import com.brightblu.parc.feature.settings.backup.LayoutBackupScreen
import com.brightblu.parc.feature.settings.backup.LayoutBackupService
import com.brightblu.parc.feature.settings.hidden.HiddenApps
import com.brightblu.parc.feature.settings.hidden.HiddenAppsController
import com.brightblu.parc.feature.settings.hidden.HiddenFolders
import com.brightblu.parc.feature.settings.hidden.HiddenFoldersController
import com.brightblu.parc.feature.settings.hidden.HiddenFoldersText
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
import com.brightblu.parc.feature.settings.ui.SettingsGroupGlyph
import com.brightblu.parc.feature.settings.ui.SettingsSearch
import com.brightblu.parc.feature.settings.ui.SettingsTheme
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.HubAppearance
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcPage
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinSafety
import com.brightblu.parc.model.Springs
import com.brightblu.parc.model.hidden
import com.brightblu.parc.model.locked
import com.brightblu.parc.model.rememberPageInsets
import com.brightblu.parc.model.textFamily
import com.brightblu.parc.ring.FeelEvent
import com.brightblu.parc.ring.FeelPlayer
import com.brightblu.parc.ring.FeelPrefs
import com.brightblu.parc.ring.HapticPreset
import com.brightblu.parc.ring.SoundTheme
import kotlinx.coroutines.launch

/** T220: the Settings host is one of these three screens. The group/page enums hold the content. */
internal sealed interface SettingsScreen {
    data object Root : SettingsScreen
    data class Group(val group: SettingsGroup) : SettingsScreen
    data class Page(val page: SettingsPage) : SettingsScreen
}

/** One step out, shared by the Back arrow and system Back. The nested chain is page → page → group → root. */
internal fun settingsParent(screen: SettingsScreen): SettingsScreen = when (screen) {
    SettingsScreen.Root -> SettingsScreen.Root
    is SettingsScreen.Group -> SettingsScreen.Root
    is SettingsScreen.Page -> when {
        screen.page.parent != null -> SettingsScreen.Page(screen.page.parent)
        screen.page.group != null -> SettingsScreen.Group(screen.page.group)
        else -> SettingsScreen.Root
    }
}

internal fun settingsParent(page: SettingsPage): SettingsScreen = settingsParent(SettingsScreen.Page(page))

/** The registry row's actual destination. Hidden apps/folders/locked folders are own pages under Hidden & locked. */
internal fun settingsPageForRow(def: SettingDef): SettingsPage = def.page

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
    /** T245: Lock layout lives in Settings > Home and is mirrored by Home at once. */
    layoutLocked: Boolean = false,
    onSetLayoutLocked: (Boolean) -> Unit = {},
    /** T261: Left hand mirrors Home at once. */
    leftHand: Boolean = false,
    onSetLeftHand: (Boolean) -> Unit = {},
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
    // T213: installed messaging/social apps are offered first, then the fixed opt-ins, then the rest.
    val commsApps = remember(apps, context) {
        val packageManager = context.packageManager
        apps.map { app ->
            val known = app.packageName in CommsPackages.Known
            val social = runCatching {
                packageManager.getApplicationInfo(app.packageName, 0).category == ApplicationInfo.CATEGORY_SOCIAL
            }.getOrDefault(false)
            CommsAppEntry(app.packageName, app.label, known || social)
        }
    }
    val lockGate = rememberFolderLockGate(services)
    val lockWindows by services.folderLock.windows.collectAsState()
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
    val commsEnabled by services.comms.enabled.collectAsState()
    val commsRecordHistory by services.comms.keepHistory.collectAsState()
    val commsStored by services.comms.storedMessages.collectAsState()
    val focus by services.focus.state.collectAsState()
    val focusModes by services.focus.modes.collectAsState()
    var screen by remember { mutableStateOf<SettingsScreen>(
        if (initialPage == SettingsPage.Root) SettingsScreen.Root else SettingsScreen.Page(initialPage)) }
    var target by remember { mutableStateOf(initialRow) }
    var hubOptions by remember { mutableStateOf(TodayPage.Id) }
    // T262: the Hub pages list keeps one scroll state for the whole Settings visit, so returning
    // from a page's options does not move the rows under the finger.
    val hubPagesScroll = rememberScrollState()
    var hubPagesViewport by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val menuPrefs = remember(context) { ParcUiPrefs(context) }
    var menuNotifications by remember { mutableStateOf(menuPrefs.menuNotifications()) }
    var menuNotificationText by remember { mutableStateOf(menuPrefs.menuNotificationText()) }
    var agendaSettings by remember { mutableStateOf(menuPrefs.agenda()) }
    val counts by remember(menuPrefs) { menuPrefs.countsFlow }.collectAsState(initial = menuPrefs.layoutCounts())
    val arcMaximums by remember(menuPrefs) { menuPrefs.arcMaximumsFlow }.collectAsState(initial = menuPrefs.arcMaximums())
    val wallpaperSkin by services.wallpaperSkin.skin.collectAsState()
    val customSkinCatalog by services.customSkins.skins.collectAsState()
    val settingsController = remember(services, wallpaperSkin, customSkinCatalog) {
        SettingsController(services.prefs.asSettingsStore(), services.skins, scope)
    }
    val settingsState by settingsController.state.collectAsState()
    val hiddenController = remember(services) { HiddenAppsController(services.layout, services.unlockedApps, scope) }
    val hiddenAppsState by hiddenController.state.collectAsState()
    val hiddenFoldersController = remember(services) { HiddenFoldersController(services.layout, scope) }
    val backupController = remember(services) {
        BackupController(LayoutBackupService(services.layout, AndroidBackupFileStore(context)), scope, BackupPassphraseStore(context))
    }
    val weeklyBackupStore = remember(context) { WeeklyBackupStore(context) }
    val weeklyBackup by weeklyBackupStore.state.collectAsState(initial = weeklyBackupStore.read())
    val weeklyBackupUi = com.brightblu.parc.feature.settings.backup.WeeklyBackupUiState(
        enabled = weeklyBackup.enabled,
        folderName = weeklyBackup.folderName,
        lastBackupLabel = WeeklyBackupText.lastBackup(
            weeklyBackup.lastSuccessMillis, System.currentTimeMillis(), java.time.ZoneId.systemDefault()),
        failureLabel = weeklyBackup.lastFailure?.let(WeeklyBackupText::failure),
    )
    val onboardingController = remember(services) {
        OnboardingController(services.prefs.asOnboardingStore(), AndroidOnboardingProbe.of(context), scope)
    }
    val onboardingState by onboardingController.state.collectAsState()
    LaunchedEffect(onboardingState.completed) {
        if (onboardingState.completed && screen == SettingsScreen.Page(SettingsPage.Onboarding)) {
            screen = SettingsScreen.Page(SettingsPage.DataPermissions)
        }
    }
    val maxIcons = remember(services.geometry, alignedArcs) {
        val g = services.geometry
        com.brightblu.parc.ring.RingLayout(g.widthDp, g.heightDp, g.insets, g.options.copy(alignAcrossArcs = alignedArcs))
            .arcs.maxOf { it.slotCapacity }.coerceAtLeast(3)
    }
    // T312: the arcs' visible capacities (same geometry Home draws), for the "over by n" detail.
    val sceneArcs = remember(services.geometry, counts, alignedArcs, leftHand) {
        val g = services.geometry
        com.brightblu.parc.ring.RingLayout(g.widthDp, g.heightDp, g.insets,
            g.options.copy(alignAcrossArcs = alignedArcs, arcsShown = counts.arcs, iconsPerArc = counts.icons,
                mirrored = leftHand)).arcs
    }
    val arcOver = remember(settingsLayout, sceneArcs, arcMaximums) {
        val newFolderSpot = ArcChooser.newFolderSpot(settingsLayout, sceneArcs.lastOrNull()?.id ?: "",
            settingsLayout.folders.firstOrNull { it.id == FolderId(NewFolderKey) }?.hidden == true)
        ArcChooser.choices(settingsLayout, sceneArcs, arcMaximums, newFolderSpot)
            .filter { it.over > 0 }.associate { it.arcId to it.over }
    }

    // T175: everything the pages read, as one value.
    val snapshot = SettingsSnapshot(
        skins = settingsState.skins, skinId = settingsState.selectedSkinId, look = look, hub = hubAppearance, counts = counts,
        arcMaximums = arcMaximums, arcOver = arcOver,
        maxIconsPerArc = maxIcons, aligned = alignedArcs, joystick = joystick, hubDoubleTap = hubDoubleTap, gestures = gestures,
        haptics = settings.haptics, feel = feelPrefs, arcFeel = arcFeel, clockVisible = settings.clockVisible,
        iconPacks = iconPacks.map { it.packageName to it.label }, iconPack = iconPack, statsVisible = settings.statsVisible,
        hubSize = hubConfig.size, hubFullScreen = hubConfig.fullScreenEnabled,
        searchPhoneSettings = searchSnapshot.phoneSettingsEnabled,
        searchFiles = searchSnapshot.filesEnabled, searchEvents = searchSnapshot.eventsEnabled,
        fileSearchAvailable = BuildConfig.FILE_SEARCH_ENABLED, badges = badgesEnabled, badgesInAllApps = badgesInAllApps,
        menuNotifications = menuNotifications, menuNotificationText = menuNotificationText, markWorkApps = markWorkApps,
        layoutLocked = layoutLocked, leftHand = leftHand, notificationAccess = notificationAccess, wallpaperSkinSupported = services.wallpaperSkin.supported,
        wallpaperMode = settings.wallpaperMode, wallpaperLook = settings.wallpaperLook,
        focusSummary = FocusDisplay.summary(focus, focusModes),
        hiddenFoldersSummary = HiddenFoldersText.summary(settingsLayout.folders.count { it.hidden }),
        lockedFoldersSummary = com.brightblu.parc.core.data.lock.LockedFolderText.summary(settingsLayout.folders.count { it.locked }),
        agendaSummary = AgendaSettingsText.summary(agendaSettings),
        swipeUpLabel = gestureActionLabel(gestures.swipeUp, appLabel), hubLongPressLabel = gestureActionLabel(gestures.hubLongPress, appLabel),
        commsEnabled = commsEnabled, commsRecordHistory = commsRecordHistory,
        commsStoredCount = commsStored.size,
        commsStorageLine = CommsExtrasPresentation.storageLine(commsStored.size, CommsExtrasPresentation.storageBytes(commsStored)),
    )
    val lookStore = remember(context) { ParcLookStore(context) }
    val writer = remember(services) {
        SettingsWriter(look = { change -> onChangeLook(change) }, hub = { change -> appearanceStore.update(change) },
            prefs = services.prefs, ui = menuPrefs, hubStore = hubStore, search = searchPrefs, feel = feelStore,
            iconPacks = services.appIndex.iconPacks, layout = services.layout, comms = services.comms,
            live = LiveSinks(onSetAlignedArcs, onSetJoystick, onSetHubDoubleTap, onSetBadgesEnabled, onSetBadgesInAllApps,
                onSetMarkWorkApps, onSetLayoutLocked, onSetLeftHand))
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
    var previewSkin by remember { mutableStateOf<Skin?>(null) }
    val onOpen: (SettingDef) -> Unit = { def ->
        when (def.id) {
            "NotificationAccess" -> context.startActivity(access.settingsIntent())
            "AndroidSettings" -> onAndroidSettings()
            "ShowHintsAgain" -> {
                com.brightblu.parc.ParcUiPrefs(context).resetGestureHints()
                android.widget.Toast.makeText(context, "Hints will show again.", android.widget.Toast.LENGTH_SHORT).show()
            }
            "CommsClearHistory" -> scope.launch { services.comms.clearHistory() }
            "RerunOnboarding" -> {
                scope.launch { services.prefs.setOnboardingCompleted(false) }
                onboardingController.goTo(com.brightblu.parc.feature.settings.onboarding.OnboardingStep.DefaultHome)
                screen = SettingsScreen.Page(SettingsPage.Onboarding)
            }
            "Agenda" -> { agendaSettings = menuPrefs.agenda(); screen = SettingsScreen.Page(SettingsPage.Agenda) }
            else -> def.opens?.let { screen = SettingsScreen.Page(it) }
        }
    }
    val customRow: @Composable (SettingDef) -> Unit = { def ->
        if (def.id == "ArcMaximums") {
            // T312: per-arc "Maximum apps" rows, persisted like every other setting.
            ArcMaximumsSection(snapshot, skin, onSet = { map ->
                scope.launch { writer.persist(snapshot, snapshot.copy(arcMaximums = map)) }
            })
        } else {
            RegistryCustom(def, snapshot, skin, services, look, onChangeLook, chooseWallpaper,
                onChoose = { chooseWallpaper++ }, onValue = onValue,
                onPreviewSkin = { previewSkin = it }, onEditSkin = { screen = SettingsScreen.Page(SettingsPage.SkinStudio) },
                onWhatsNew = { screen = SettingsScreen.Page(SettingsPage.WhatsNew) })
        }
    }

    val backTarget = remember { SettingsBackTarget() }
    val goBack = {
        if (previewSkin != null) previewSkin = null
        else when (val shown = screen) {
            SettingsScreen.Root -> onClose()
            else -> screen = settingsParent(shown)
        }
    }
    BackHandler { goBack() }

    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsBackTarget provides backTarget) {
    Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
        androidx.compose.animation.Crossfade(targetState = screen,
            animationSpec = androidx.compose.animation.core.spring(Springs.sheet.damping, Springs.sheet.stiffness), label = "Settings page") { shown ->
        val back = { screen = settingsParent(shown) }
        when (shown) {
            SettingsScreen.Root -> RootPage(skin, snapshot, hubConfig.takeIf { settings.statsVisible },
                onOpen = { group -> target = null; screen = SettingsScreen.Group(group) },
                onJump = { def -> target = if (def.id in SettingsRegistry.selfEntryIds) null else def.id
                    screen = SettingsScreen.Page(settingsPageForRow(def)) },
                onHubPage = { id -> hubOptions = id; screen = SettingsScreen.Page(SettingsPage.HubOptions) }, onClose = onClose)
            is SettingsScreen.Group -> SettingsGroupPage(shown.group, skin, back,
                onOpenPage = { page -> target = null; screen = SettingsScreen.Page(page) })
            is SettingsScreen.Page -> when (val page = shown.page) {
                SettingsPage.HubOptions -> SettingsPageFrame(skin, onBack = back) {
                    ParcPage(safeTop = false, scroll = false, padBottom = false) {
                        HubPageOptionsContent(hubOptions, skin, hubConfig, { change -> scope.launch { hubStore.update(change) } },
                            onFullSettings = { screen = SettingsScreen.Page(it) }, showEditPages = false)
                    }
                }
                SettingsPage.HubPages -> {
                    SettingsPageFrame(skin, onBack = back) {
                        ParcPage(Modifier.onGloballyPositioned { hubPagesViewport = it.boundsInWindow() }, safeTop = false, scrollState = hubPagesScroll) {
                            BasicText(page.title, style = style(skin, skin.colors.textPrimary, skin.typography.title))
                            SettingsIntro(skin, page.intro)
                            if (settings.statsVisible) SettingsAnchor("HubPages", target) {
                                Column(verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                                    HubPagesMainList(skin, hubConfig, { change -> scope.launch { hubStore.update(change) } },
                                        hubPagesScroll, { hubPagesViewport }) { id -> hubOptions = id; screen = SettingsScreen.Page(SettingsPage.HubOptions) }
                                }
                            }
                            RegistryRows(SettingsPage.HubPages, snapshot, skin, target, onValue, onOpen, customRow)
                        }
                    }
                }
                SettingsPage.Search -> SettingsRegistryPage(page, snapshot, skin, target, back, onValue, onOpen, customRow)
                SettingsPage.SearchPreferences -> SettingsPageFrame(skin, onBack = back) {
                    SettingsContent(skin, page.title) { SearchSettingsSection(skin) }
                }
                SettingsPage.Messages -> SettingsRegistryPage(page, snapshot, skin, target, back, onValue, onOpen, customRow,
                    footerFor = { card -> if (card == "History") "Messages from WhatsApp, Telegram and Instagram are stored on this phone. Not in backups. Clear them any time." else "" })
                SettingsPage.MainApps -> SettingsPageFrame(skin, onBack = back) {
                    ParcPage(safeTop = false, scroll = false, padBottom = false) {
                        MainAppsSettingsSection(skin, settingsLayout, apps) { edit ->
                            services.layout.updateLayout { current ->
                                current.editMainApp(edit, menuPrefs.layoutLocked())
                            }
                        }
                    }
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
                    SettingsContent(skin, page.title) { entries.forEach { ReleaseEntry(skin, it) } }
                }
                SettingsPage.WhatsNew -> SettingsPageFrame(skin, onBack = back) {
                    val entries = remember(context) { loadChangelog(context) }
                    SettingsContent(skin, page.title) { ReleaseEntry(skin, entries.firstOrNull { it.versionCode == BuildConfig.VERSION_CODE } ?: entries.first()) }
                }
                SettingsPage.StatsAlerts -> SettingsPageFrame(skin, onBack = back) {
                    ParcPage(safeTop = false, scroll = false, padBottom = false) { StatsAlertSettings(services.statsAlerts, skin) }
                }
                SettingsPage.CommsApps, SettingsPage.QuickReplies -> SettingsPageFrame(skin, onBack = back) {
                    ParcPage(safeTop = false, scroll = false, padBottom = false) {
                        CommsSettings(services.comms, skin, commsApps,
                            section = if (page == SettingsPage.CommsApps) CommsSettingsSection.Apps else CommsSettingsSection.Replies,
                            onOpenApps = { screen = SettingsScreen.Page(SettingsPage.CommsApps) },
                            onOpenReplies = { screen = SettingsScreen.Page(SettingsPage.QuickReplies) })
                    }
                }
                SettingsPage.Agenda -> SettingsPageFrame(skin, onBack = back) {
                    ParcPage(safeTop = false, scroll = false, padBottom = false) {
                        AgendaSettingsContent(skin, agendaSettings) { next -> agendaSettings = next; menuPrefs.setAgenda(next) }
                    }
                }
                SettingsPage.SwipeUp, SettingsPage.HubLongPress -> {
                    val slot = if (page == SettingsPage.SwipeUp) GestureSlot.SwipeUp else GestureSlot.HubLongPress
                    GestureSettingsPage(skin, slot, gestures.actionFor(slot), hubConfig.pages, apps,
                        onPick = { onSetGesture(slot, it) }, onBack = back)
                }
                SettingsPage.Diagnostics -> SettingsPageFrame(skin, onBack = back) {
                    val stored by remember(context) { SceneDiagnosticsStore(context) }.last.collectAsState(initial = SceneDiagnostic())
                    ParcPage(safeTop = false, blockGapDp = Metrics.itemGapDp) {
                        BasicText(page.title, style = style(skin, skin.colors.textPrimary, skin.typography.title))
                        sceneDiagnosticLines(stored).forEach { line ->
                            BasicText(line, style = style(skin, skin.colors.textSecondary, skin.typography.caption))
                        }
                        DiagnosticsExport(skin, rememberDiagnosticsExporter())
                    }
                }
                SettingsPage.HiddenLocked -> SettingsPageFrame(skin, onBack = back) {
                    HiddenLockedPage(skin = skin, snapshot = snapshot, target = target, onValue = onValue, onOpen = onOpen, custom = customRow)
                }
                SettingsPage.HiddenFolders -> SettingsPageFrame(skin, onBack = back) {
                    HiddenFolders(hiddenFoldersController, skin, Modifier.fillMaxSize(), topPaddingDp = 0f)
                }
                SettingsPage.LockedFolders -> SettingsPageFrame(skin, onBack = back) {
                    SettingsContent(skin, page.title) { LockedFoldersContent(services, skin, lockGate) }
                }
                SettingsPage.Folders -> FolderEditorHost(services, skin, settingsHost = true, onClose = back)
                SettingsPage.Focus -> FocusSettingsPage(skin = skin, focus = focus, modes = focusModes, repository = services.focus,
                    folders = settingsLayout.folders.map { folder ->
                        com.brightblu.parc.core.data.lock.LockedApps.folderForDisplay(folder, folder.id in hiddenFolderIds, lockGate.hideName())
                    }, skins = services.skins, apps = apps, layout = settingsLayout, onBack = back)
                SettingsPage.SkinStudio -> SettingsPageFrame(skin, onBack = back) {
                    val custom by services.customSkins.skins.collectAsState()
                    ParcPage(safeTop = false, scroll = false, padBottom = false) {
                        SkinStudioContent(SkinStudioHost(
                            builtIn = services.skinProvider.available.filter { !SkinNaming.isCustomId(it.id) }, custom = custom,
                            activeId = settingsState.selectedSkinId, saveSkin = { services.customSkins.save(it) },
                            deleteSkin = { services.customSkins.delete(it) }, useSkin = settingsController::selectSkin), skin)
                    }
                }
                SettingsPage.Permissions -> SettingsPageFrame(skin, onBack = back) {
                    SettingsContent(skin, page.title) { PermissionsContent(skin) }
                }
                SettingsPage.Hidden -> SettingsPageFrame(skin, onBack = back) {
                    HiddenApps(hiddenController, skin, Modifier.fillMaxSize(), topPaddingDp = 0f)
                }
                SettingsPage.Backup -> SettingsPageFrame(skin, onBack = back) {
                    LayoutBackupScreen(
                        backupController, skin, Modifier.fillMaxSize(), topPaddingDp = Metrics.rowGapDp,
                        weekly = weeklyBackupUi,
                        hasBackupKey = { WeatherVariant.hasBackupKey(context) },
                        showUpdateTokenNote = UpdateVariant.Available,
                        onWeeklyEnabled = { enabled ->
                            weeklyBackupStore.setEnabled(enabled)
                            if (enabled) WeeklyBackupScheduler.restart(context) else WeeklyBackupScheduler.cancel(context)
                        },
                        onWeeklyFolder = { uri ->
                            val parsed = android.net.Uri.parse(uri)
                            val previous = weeklyBackupStore.read().folderUri
                            val granted = runCatching {
                                context.contentResolver.takePersistableUriPermission(
                                    parsed,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                )
                            }.isSuccess
                            if (!granted) {
                                // T270: say so now instead of saving a folder that would fail at the first run.
                                weeklyBackupStore.recordFailure(WeeklyBackupFailure.GrantFailed, System.currentTimeMillis())
                                false
                            } else {
                                if (previous != null && previous != uri) runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        android.net.Uri.parse(previous),
                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                    )
                                }
                                weeklyBackupStore.setFolder(
                                    uri,
                                    AndroidBackupFolder.displayName(context, parsed) ?: "Chosen folder",
                                )
                                if (weeklyBackupStore.read().enabled) WeeklyBackupScheduler.restart(context)
                                true
                            }
                        },
                    )
                }
                SettingsPage.Onboarding -> SettingsPageFrame(skin, onBack = back) {
                    Onboarding(onboardingController, skin, onOpenTarget = { AndroidOnboardingIntents.open(context, it) },
                        modifier = Modifier.fillMaxSize(), safeTop = false)
                }
                else -> SettingsRegistryPage(page, snapshot, skin, target, back, onValue, onOpen, customRow)
            }
        }
        }
        previewSkin?.let { candidate -> SkinFullPreview(candidate, services, look,
            onUse = { settingsController.selectSkin(candidate.id); previewSkin = null },
            onBack = { previewSkin = null }) }
    }
    }
}

/** A group page: its three or four sub-page cards. */
@Composable
private fun SettingsGroupPage(
    group: SettingsGroup,
    skin: Skin,
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    val pages = SettingsPage.entries.filter { it.group == group && it.parent == null }
    SettingsPageFrame(skin, onBack = onBack) {
        SettingsContent(skin, group.title) {
            SettingsIntro(skin, group.intro)
            pages.forEach { page ->
                SettingsCard(skin) {
                    SettingRow(page.title, skin, SettingKind.Page, page.summary, divider = false) { onOpenPage(page) }
                }
                Spacer(Modifier.height(Metrics.rowGapDp.dp))
            }
        }
    }
}

/** One registry-generated sub-page. */
@Composable
private fun SettingsRegistryPage(
    page: SettingsPage,
    snapshot: SettingsSnapshot,
    skin: Skin,
    target: String?,
    onBack: () -> Unit,
    onValue: (SettingDef, Int) -> Unit,
    onOpen: (SettingDef) -> Unit,
    custom: @Composable (SettingDef) -> Unit,
    footerFor: (String) -> String = { card ->
        when (card) {
            "Both hubs" -> "One glass for both cards, in every skin. Off gives flat cards at their opacity."
            "Wallpaper" -> WallpaperSheetRules.note(snapshot.look.wallpaper)
            else -> ""
        }
    },
) {
    SettingsPageFrame(skin, onBack = onBack) {
        SettingsContent(skin, page.title) {
            SettingsIntro(skin, page.intro)
            RegistryRows(page, snapshot, skin, target, onValue, onOpen, custom, footer = footerFor)
            // T324: the last card's footer sentence ends clear of the gesture strip (it used to sit
            // right at the bottom edge, so it read as clipped until the page was scrolled).
            Spacer(Modifier.height(Metrics.rowGapDp.dp))
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
        // T325: the page title ("This build") is the only heading; the version is a row, not a second title.
        "About" -> Column(Modifier.padding(vertical = Metrics.rowGapDp.dp)) {
            BasicText("A home screen with thumb-reach arcs. Settings and layout stay on this device.",
                style = settingsText(skin, skin.colors.textSecondary, true),
                modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp).padding(top = Metrics.lineGapDp.dp))
            BasicText(SupportLinks.donateNote,
                style = settingsText(skin, skin.colors.textSecondary, true),
                modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp).padding(top = Metrics.lineGapDp.dp))
            // T293: the personal and weather builds are online for the private-repo update and the data
            // packs; only play has no internet at all. Each build says exactly what it is online for.
            val onlineLine = when (BuildConfig.FLAVOR) {
                "personal" -> "This build is online only to check for updates and download language packs: no accounts, ads, analytics or tracking."
                "personalWeather" -> "This build is online only for Weather, Translate (your own DeepL key), updates and language packs: no accounts, ads, analytics or tracking."
                "family" -> "This build is online only for Weather and Translate (your own DeepL key): no accounts, ads, analytics or tracking."
                else -> "This build has no internet access: no accounts, ads, analytics or tracking."
            }
            BasicText(onlineLine,
                style = settingsText(skin, skin.colors.textSecondary, true),
                modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp).padding(top = Metrics.lineGapDp.dp))
            Spacer(Modifier.height(Metrics.rowGapDp.dp))
            SettingsCard(skin) {
                val stamp = BuildStamp.lines()
                stamp.forEachIndexed { index, (label, value) ->
                    // T325: every build row keeps its value on the right, so Version, Version code, Built on
                    // and Code commit line up instead of Built alone dropping under its label.
                    SettingRow(label, skin, SettingKind.Value, value = value,
                        allowLongValueBeside = true, divider = index < stamp.lastIndex, onClick = {})
                }
            }
            // T293: check for updates, the token and the data-pack rows (personal and weather builds only).
            UpdateVariant.AboutContent(skin)
            if (SupportLinks.isVisible(SupportLinks.donate)) {
                Spacer(Modifier.height(Metrics.rowGapDp.dp))
                val context = LocalContext.current
                SettingsCard(skin) {
                    SettingRow("Support Parc", skin, SettingKind.Action,
                        "Opens the donation page in your browser", icon = "↗", divider = false) {
                        SupportLinks.open(context, SupportLinks.donate)
                    }
                }
            }
        }
    }
}

/**
 * The Settings root: exactly five groups and one search box over every setting. A result shows its
 * `Group · Sub-page` breadcrumb (and the full chain for a nested page) before jumping to the row.
 */
@Composable
internal fun RootPage(skin: Skin, snapshot: SettingsSnapshot, hubConfig: HubConfig?, onOpen: (SettingsGroup) -> Unit,
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
                val pagesFound = hubConfig?.orderedPages.orEmpty().filter {
                    hubPageName(it).contains(query.trim(), true) || it.contains(query.trim(), true)
                }
                if (found.isEmpty() && pagesFound.isEmpty()) BasicText("No settings match", style = settingsText(skin),
                    modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
                else SettingsCard(skin) {
                    found.forEach { def ->
                        val hidden = !def.shown(snapshot)
                        SettingRow(def.fullTitle, skin, SettingKind.Page,
                            settingsSearchSubtitle(def, hidden), value = def.value(snapshot)) {
                            query = ""; keyboard?.hide(); focusManager.clearFocus(); onJump(def)
                        }
                    }
                    pagesFound.forEach { id ->
                        SettingRow(hubPageName(id), skin, SettingKind.Page, "Hub page",
                            value = if (id in hubConfig!!.hiddenPages) "Hidden" else "Shown") {
                            keyboard?.hide(); focusManager.clearFocus(); query = ""; onHubPage(id)
                        }
                    }
                }
            } else SettingsGroup.entries.forEach { group ->
                SettingsCard(skin) {
                    SettingRow(group.title, skin, SettingKind.Page, group.summary,
                        value = if (group == SettingsGroup.DataAbout) BuildConfig.VERSION_NAME else "",
                        leading = { SettingsGroupGlyph(group.icon, skin) }, divider = false) { onOpen(group) }
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
