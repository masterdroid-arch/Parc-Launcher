package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalView
import com.brightblu.parc.ring.FeelEvent
import android.widget.Toast
import com.brightblu.parc.core.data.HapticsLevel
import com.brightblu.parc.core.data.notifications.NotificationUnreadStore
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.ParcSettings
import com.brightblu.parc.feature.search.*
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import com.brightblu.parc.ring.module.leanAway
import com.brightblu.parc.ring.module.rememberSheetProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LauncherHome(services: LauncherServices, geometry: RingLayout, navigation: ShellNavigation,
    resumedFlow: StateFlow<Boolean>, hideKeyboard: () -> Unit, openAndroidSettings: () -> Unit,
    onSession: (LauncherSession) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val uiPrefs = remember(context) { ParcUiPrefs(context) }
    // T69: gesture actions live in an app-local store, never core/data ParcPrefs.
    val gestureStore = remember(context) { GestureOptionsStore(context) }
    // OPUS8 (T133): until the stored choice is read, swipe up does nothing, so a user who chose
    // None never sees the new All-apps default flash in.
    val gestureConfig by gestureStore.config.collectAsState(initial = GestureConfig(swipeUp = GestureAction.None))
    // T71: the look lives in its own app-local store; defaults keep today's appearance.
    val lookStore = remember(context) { ParcLookStore(context) }
    val look by lookStore.look.collectAsState(initial = LookSettings())
    // T73: focus state drives a view-level filter only; the layout is never written.
    val focus by services.focus.state.collectAsState()
    val focusModes by services.focus.modes.collectAsState()
    val hubStore = remember(context) { HubConfigStore(context) }
    val hubAppearanceStore = remember(context) { HubAppearanceStore(context) }
    val hubAppearance by hubAppearanceStore.appearance.collectAsState(initial = HubAppearance())
    // T177: "Panel opacity" is gone; a value the user had moved becomes each hub's own opacity (idempotent, also after a restore).
    LaunchedEffect(lookStore) {
        lookStore.look.collect { stored ->
            val current = hubAppearanceStore.appearance.first()
            if (HubOpacity.migratePanel(current, stored.panelOpacityPercent) != current)
                hubAppearanceStore.update { HubOpacity.migratePanel(it, stored.panelOpacityPercent) }
        }
    }
    val hubConfig by hubStore.config.collectAsState(initial = HubConfig())
    val appActions = remember(context) { LauncherAppActions(context) }
    val searchFirstSeen = remember(uiPrefs) { uiPrefs.searchFirstSeen() }
    var searchSucceeded by remember { mutableStateOf(uiPrefs.searchSucceeded()) }
    // T35 discoverability: caption + start pulse until the first week is up or a search succeeds.
    val searchHint = remember(searchSucceeded, searchFirstSeen) {
        searchHintVisible(System.currentTimeMillis(), searchFirstSeen, searchSucceeded)
    }
    // T30: the gear opens this overlay; the first-run hint shows once until dismissed.
    var showSettings by remember { mutableStateOf(false) }
    var settingsInitialPage by remember { mutableStateOf(SettingsPage.Root) }
    // OPUS8 (T133): a group of the Settings root to open at once (Home menu "Wallpaper and look").
    var settingsInitialRow by remember { mutableStateOf<String?>(null) }
    var showFolderCreate by remember { mutableStateOf(false) }
    var showAddApp by remember { mutableStateOf(false) }
    var hintSeen by remember { mutableStateOf(uiPrefs.hintSeen()) }
    var swapPulse by remember { mutableStateOf(emptySet<SlotPosition>()) }
    LaunchedEffect(services.usageLayer) {
        services.usageLayer.lastChanges.collect { changes ->
            if (changes.isNotEmpty()) {
                swapPulse = changes.mapTo(mutableSetOf()) { it.position }
                delay(Metrics.swapFadeMs + 160L)
                swapPulse = emptySet()
                services.usageLayer.consumeChanges()
            }
        }
    }
    val settings by services.prefs.settings.collectAsState(initial = ParcSettings())
    val haptics by rememberUpdatedState(settings.haptics != HapticsLevel.Off)
    val feel = rememberFeelPlayer()
    val arcFeelStore = remember { FeelStore(context) }
    val arcFeel by rememberUpdatedState(arcFeelStore.arcFeel.collectAsState(initial = com.brightblu.parc.model.ArcFeel()).value)
    val edit = remember(services.layout, scope) { RingEditSession(services.layout, scope) }
    val rotations = remember(uiPrefs) { uiPrefs.rotations() }
    // T83: the HUD bound follows the settled live offsets, not only the cold-start snapshot.
    var liveRotations by remember(uiPrefs) { mutableStateOf(rotations) }
    val linkLauncher = remember(context) { LinkLauncher(context) }
    val pinnedSource = remember(context) { PinnedShortcutSource(context) }
    val search = remember(services) {
        SearchSheetState(services.searchEngine, services.focusIndex, services.usage, scope)
    }
    // T98: locked folders. The gate reads the same snapshot the scene is built from.
    val lockedPackages by services.lockedPackages.collectAsState()
    val folderUnlocked by services.folderLock.windows.collectAsState()
    var hideLockedName by remember { mutableStateOf(uiPrefs.lockHideName()) }
    val lockGate = rememberFolderLockGate(services)
    var pendingFocusExit by remember { mutableStateOf<com.brightblu.parc.core.data.focus.FocusExit?>(null) }
    var pendingFocusLaunch by remember { mutableStateOf<AppId?>(null) }
    var approvedFocusLaunch by remember { mutableStateOf<AppId?>(null) }
    val session = remember(search, navigation) {
        LauncherSession(navigation, services.appIndex, services.usage, services.layout, search,
            hideKeyboard, { showSettings = true }, services.shortcutHost,
            // OPUS6 (audit F5): a failed menu change (layout full, storage error) says so instead
            // of crashing Parc.
            kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
                ParcLog.e("Parc", "menu action failed", error)
                edit.notify("Could not save that change")
            }),
            launchLink = { if (!linkLauncher.open(it)) edit.notify("Link unavailable") },
            launchPinnedShortcut = { shortcut ->
                val blocked = shortcut.packageName in services.lockedPackages.value ||
                    shortcut.packageName in focusBlockedPackages(services.focus.state.value.active, services.appIndex.apps.value, services.layout.layout.value)
                if (!blocked) scope.launch {
                    val launched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pinnedSource.launch(shortcut) }
                    if (!launched) edit.notify("Shortcut unavailable. Hold it to remove.")
                }
            },
            closeEditing = { val wasEditing = edit.enabled; edit.close(); wasEditing },
            launchApp = { id ->
                val mode = services.focus.state.value.active
                val entry = services.appIndex.apps.value.firstOrNull { it.id == id }
                val blocked = mode != null && entry?.packageName in focusBlockedPackages(mode, services.appIndex.apps.value, services.layout.layout.value)
                if (blocked && approvedFocusLaunch != id) {
                    services.focus.recordBlockedAttempt()
                    pendingFocusLaunch = id
                    com.brightblu.parc.core.data.profiles.LaunchOutcome.Unavailable
                } else { approvedFocusLaunch = null; services.appIndex.launchWithOutcome(id) }
            },
            onLaunchRefused = { outcome -> workProfileNotice(outcome)?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            } },
            onAppLaunched = services.lastApp::onLaunched,
            lastApp = { services.lastApp.lastApp.value?.appId },
            hubDoubleTapEnabled = uiPrefs.hubDoubleTap(),
            onPinnedLaunch = { if (haptics) feel.play(view, FeelEvent.Confirm) },
            onMenuOpened = { if (haptics) feel.play(view, FeelEvent.Menu) },
            onRotation = { arcId, offset -> uiPrefs.setRotation(arcId, offset)
                liveRotations = liveRotations + (arcId to offset) },
            openAndroidSettings = openAndroidSettings,
            appInfo = { id -> appEntryFor(id, services.appIndex.apps.value)?.let(appActions::openAppInfo) },
            uninstall = { id -> appEntryFor(id, services.appIndex.apps.value)?.let(appActions::uninstall) },
            uninstallable = appActions::canUninstall,
            gestures = { gestureConfig },
            enterEditMode = { edit.enter() },
            shortcutAllowed = { id ->
                services.appIndex.apps.value.firstOrNull { it.id == id }?.packageName !in focusBlockedPackages(services.focus.state.value.active, services.appIndex.apps.value, services.layout.layout.value)
            },
            menuNotifications = ListenerMenuNotifications(uiPrefs, blocked = { services.lockedPackages.value }),
            lockGate = lockGate,
            openSettingsAt = { target ->
                settingsInitialPage = target.page; settingsInitialRow = target.row; showSettings = true
            },
            hubPages = { hubConfig.pages },
            hubPage = { page ->
                scope.launch {
                    hubStore.rememberPage(page)
                    // Opening the hub means the panel is on screen, on the chosen page.
                    if (!settings.statsVisible) services.prefs.setStatsVisible(true)
                    // Hub size is independent of the retired expanded preference.
                }
            })
    }
    SideEffect { onSession(session) }
    DisposableEffect(search) { onDispose { session.cancelHubTap(); search.cancel() } }
    val shell by navigation.state.collectAsState()
    val ui by search.state.collectAsState()
    val presentation by services.presentation.collectAsState()
    val apps = presentation.apps
    val layout = presentation.layout
    // The active mode's packages leave every view projection, never the stored layout.
    val focusHidden = remember(focus.active, apps, layout) { focusBlockedPackages(focus.active, apps, layout) }
    val visibleApps = remember(apps, focusHidden, lockedPackages) {
        FocusFilter.visibleApps(apps, if (lockedPackages.isEmpty()) focusHidden else focusHidden + lockedPackages)
    }
    // T98: folders whose apps are hidden right now (lock disc), and the labels a remembered search must not reveal.
    val lockedFolders = remember(layout, folderUnlocked) {
        com.brightblu.parc.core.data.lock.LockedApps.lockedFoldersNow(layout.folders.filter { !services.folderLock.isUnlocked(it.id) }, false)
    }
    val hiddenLabels = remember(apps, lockedPackages, lockedFolders, layout, hideLockedName) {
        apps.filter { it.packageName in lockedPackages }.map { it.label } +
            if (hideLockedName) layout.folders.filter { it.id in lockedFolders }.map { it.name } else emptyList()
    }
    // Relocking removes the apps at once: leave a locked folder and close menus that may show its apps.
    LaunchedEffect(folderUnlocked) {
        if (folderUnlocked.isEmpty() || lockedFolders.isNotEmpty()) {
            session.dismissMenu()
            session.dismissFolderMenu()
        }
    }
    LaunchedEffect(folderUnlocked, shell.layer, shell.folderId, layout) {
        val open = shell.folderId
        if (shell.layer == Layer.Folder && open != null && open in lockedFolders) session.reset()
    }
    // T75: profile membership drives the briefcase badge and the paused dim. The setting is
    // app-local and defaults to on, so an install without a work profile looks unchanged.
    val profiles by services.appIndex.profiles.collectAsState()
    var markWorkApps by remember { mutableStateOf(uiPrefs.markWorkApps()) }
    val liveReady by services.liveReady.collectAsState()
    // T83: the icon cache emits one revision per loaded icon. Coalesce the storm so a 150-app
    // All-apps scene is published once per quiet period instead of being rebuilt and re-prepared
    // on every icon (which used to keep the stale Home scene on screen indefinitely).
    val rawRevision by services.iconRevision.collectAsState()
    var revision by remember { mutableStateOf(rawRevision) }
    LaunchedEffect(rawRevision) {
        delay(120)
        revision = rawRevision
    }
    val menu by session.menu.collectAsState()
    val folderMenu by session.folderMenu.collectAsState()
    val shortcutMenu by session.shortcutMenu.collectAsState()
    val homeMenu by session.homeMenu.collectAsState()
    // OPUS8 (T133): the Home key. The session already closed its own layers (LauncherSession.home);
    // this closes the ones the host owns. The hub reads the same count to go to its first page.
    val homePresses by session.homePresses.collectAsState()
    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        showSettings = false; settingsInitialPage = SettingsPage.Root; settingsInitialRow = null
        showFolderCreate = false; showAddApp = false; edit.emptyPosition = null
        pendingFocusLaunch = null; approvedFocusLaunch = null; pendingFocusExit = null
        // The hub goes back to its first page, also when Home was already clean.
        HomeKey.firstHubPage(DemoMode.enabled, hubConfig.pages) { focus.active?.hubPages?.contains(it) != false }
            ?.let { hubStore.rememberPage(it) }
    }
    val resumed by resumedFlow.collectAsState()
    LaunchedEffect(showSettings, resumed) { hideLockedName = uiPrefs.lockHideName() }
    // T97: the in-memory notification collection follows the setting; re-read on resume so a
    // restored backup takes effect without a restart.
    LaunchedEffect(resumed) { MenuNotifications.applyEnabled(uiPrefs.menuNotifications()) }
    LaunchedEffect(resumed) { if (!resumed) { edit.onHostPaused(); pendingFocusLaunch = null; approvedFocusLaunch = null } }
    LaunchedEffect(resumed, liveReady) {
        if (resumed && liveReady) {
            services.focus.refresh()
            // T75: profile state (a pause or a removal) is re-read when the launcher comes back,
            // because the platform does not always deliver an availability callback for quiet mode.
            services.appIndex.refresh()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                services.lastApp.refresh()
            }
        }
    }
    val density = LocalDensity.current
    // T166: the folder title and the clock start below the status bar and any cutout, like every page.
    val statusTopDp = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density) / density.density
    val homeFocus = androidx.compose.ui.platform.LocalFocusManager.current
    val homeKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val replyKeyboardVisible by rememberUpdatedState(imeBottom > 0 && !ui.isOpen)
    LaunchedEffect(imeBottom) { session.keyboard(imeBottom > 0) }
    LaunchedEffect(ui.isOpen, ui.abcListOpen) { session.syncSearch() }
    // Refresh an open query after the indexer processes an app/layout update.
    LaunchedEffect(apps, layout) {
        kotlinx.coroutines.yield()
        search.onQueryChange(search.state.value.query)
    }
    val clock = rememberHubTime()
    // T71: optional date and next-alarm line. Refreshed twice a minute so a new alarm or a crossed
    // minute shows up without waking beyond that; AlarmManager is read directly, no permission.
    var clockDetail by remember { mutableStateOf(ClockDetail()) }
    LaunchedEffect(look.showDate, look.showAlarm) {
        if (!look.showDate && !look.showAlarm) {
            clockDetail = ClockDetail()
            return@LaunchedEffect
        }
        val zone = java.time.ZoneId.systemDefault()
        while (true) {
            val now = System.currentTimeMillis()
            clockDetail = LookClock.detail(look.showDate, look.showAlarm,
                java.time.LocalDate.now(zone), nextAlarmMillis(context), now, zone)
            delay(30_000L)
        }
    }
    // T73: the clock block carries the active mode name; an inactive mode adds no line.
    val clockDetailWithFocus = remember(clockDetail, focus, clock) {
        clockDetail.copy(focusLine = FocusDisplay.clockLine(focus))
    }
    val latestClockDetail by rememberUpdatedState(clockDetailWithFocus)
    val baseSkin by services.skinProvider.active.collectAsState()
    val skin = remember(baseSkin, focus.active) {
        val selected = services.skins.firstOrNull { it.id == focus.active?.skinId } ?: baseSkin
        focus.active?.accent?.let { selected.copy(colors = selected.colors.copy(accent = it, lineActive = it)) } ?: selected
    }
    HomeUpdateNotes(skin)
    pendingFocusExit?.let { FocusExitDialog(services.focus, it, skin, onDismiss = { pendingFocusExit = null }) }
    val iconTreatment = focus.active?.iconTreatment ?: com.brightblu.parc.core.data.focus.FocusIconTreatment.Normal
    // T71: the window side of the wallpaper choice (black/solid paint it; system shows through).
    LaunchedEffect(context, look.wallpaper, skin) {
        (context as? android.app.Activity)?.let { applyLookWindow(it, look, skin) }
    }
    // T30: the Arc layout and Joystick rows persist here and take effect live.
    var aligned by remember { mutableStateOf(uiPrefs.alignAcrossArcs()) }
    var hubDoubleTap by remember { mutableStateOf(uiPrefs.hubDoubleTap()) }
    var joystick by remember { mutableStateOf(uiPrefs.joystickAim()) }
    val counts by remember(uiPrefs) { uiPrefs.countsFlow }.collectAsState(initial = uiPrefs.layoutCounts())
    LaunchedEffect(counts.arcs, liveReady, layout) {
        if (liveReady && counts.arcs == 3 && layout.arcs.any { it.arc.id == "A4" && it.slots.isNotEmpty() }) {
            services.layout.updateLayout { LayoutCounts.migrate(it, counts.arcs) }
        }
    }
    val sceneGeometry = remember(geometry, aligned, counts) {
        RingLayout(geometry.widthDp, geometry.heightDp, geometry.insets,
            geometry.options.copy(alignAcrossArcs = aligned, arcsShown = counts.arcs, iconsPerArc = counts.icons))
    }
    val shortcutVisuals by produceState<Map<Slot.Shortcut, PinnedShortcutVisual>>(emptyMap(), layout, resumed, revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            layout.arcs.flatMap { it.slots.values }.filterIsInstance<Slot.Shortcut>().associateWith(pinnedSource::visual)
        }
    }
    // T71: the stored size, clamped so no icon grows past the tightest slot pitch.
    val tightestPitchDp = remember(sceneGeometry) { sceneGeometry.minimumPitchDp() }
    val iconSizeDp = LookRules.effectiveIconSizeDp(look.iconSizeDp, tightestPitchDp)
    val renderLook = remember(look, iconSizeDp) { look.copy(iconSizeDp = iconSizeDp.toInt()) }
    val latestRenderLook by rememberUpdatedState(renderLook)
    val latestRefraction by rememberUpdatedState(hubAppearance.refraction)
    // T122/T130: demo placeholders have no installed icon, so demo mode draws a generic code icon
    // from the fixed table. Real ids and demo-off keep the existing app-index path unchanged.
    val sceneIcon: (AppId) -> android.graphics.Bitmap? = remember(services) {
        { id ->
            if (DemoMode.enabled && DemoHomeLayout.isSampleApp(id)) {
                com.brightblu.parc.core.data.demo.DemoIcons.iconFor(
                    demo = true, id = id, sizePx = Metrics.iconBitmapPx, label = DemoHomeLayout.labelFor(id))
            } else {
                services.focusIndex.icon(id, Metrics.iconBitmapPx)
            }
        }
    }
    val previews by produceState<Map<FolderId, android.graphics.Bitmap>>(emptyMap(), layout, visibleApps, revision, skin, iconTreatment, lockedFolders) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            FolderPreviews.prepare(layout, visibleApps, services.usage, skin, lockedFolders) {
                sceneIcon(it)
            }
        }
    }
    val scene = remember(focusHidden, lockedPackages, shortcutVisuals, previews, sceneGeometry, layout, visibleApps, shell.layer, shell.folderId, revision, rotations, profiles, markWorkApps, iconTreatment, lockedFolders, hideLockedName) {
        LauncherScenes.build(sceneGeometry, layout, visibleApps, shell,
            "", previews, rotations, services.usage::rank,
            profiles = profiles, markWorkApps = markWorkApps,
            lockedFolders = lockedFolders, hideLockedName = hideLockedName, shortcutVisuals = shortcutVisuals,
            shortcutHiddenPackages = focusHidden + lockedPackages + apps.filter { it.id in layout.hidden }.map { it.packageName }) {
            sceneIcon(it)
        }
    }
    // T83 field diagnostics: what the phone actually built, stored app-locally for Parc Settings.
    val sceneDiagnostics = remember(context) { SceneDiagnosticsStore(context) }
    LaunchedEffect(scene, SceneDiagnostics.lastError) {
        sceneDiagnostics.record(SceneDiagnostic(
            layer = scene.layer.name,
            slots = scene.slots.size,
            capacities = scene.arcs.joinToString(",") { it.slotCapacity.toString() },
            error = SceneDiagnostics.lastError,
            updatedAtMillis = System.currentTimeMillis()))
    }
    var badgesEnabled by remember { mutableStateOf(uiPrefs.notificationBadges()) }
    var badgesInAllApps by remember { mutableStateOf(uiPrefs.badgesInAllApps()) }
    val unread by NotificationUnreadStore.shared.unread.collectAsState()
    // Settings is opaque; input is gated through searchOpen (the renderer ignores touches meanwhile).
    val inputBlocked = shortcutMenu != null || showSettings || showFolderCreate || edit.emptyPosition != null ||
        folderMenu?.page == FolderPage.Rename || menu?.page == MenuPage.NewFolder
    LaunchedEffect(inputBlocked, edit.enabled, showSettings) {
        if (inputBlocked || edit.enabled || showSettings) session.cancelHubTap()
    }
    val target = remember(scene, ui, unread, visibleApps, layout, menu, folderMenu, homeMenu, liveReady, edit.enabled, searchHint, swapPulse, inputBlocked, badgesEnabled, badgesInAllApps, focus.silenceBadges, lockedFolders, statusTopDp) {
        LauncherScenes.withUnread(scene, layout, visibleApps, unread,
            FocusFilter.badgesVisible(badgesEnabled, focus), badgesInAllApps, lockedFolders).copy(searchOpen = ui.isOpen || inputBlocked,
            searchHint = searchHint,
            swapPulse = swapPulse,
            accessibilityHidden = shortcutMenu != null || menu != null || folderMenu != null || homeMenu != null || edit.enabled ||
                showSettings || !liveReady,
            highlight = search.highlight(),
            hostDimsModule = ui.isOpen,
            pulse = emptySet(),
            chromeTopDp = ParcSpacing.pageTopDp(statusTopDp))
    }
    SideEffect { edit.scene = target }
    LaunchedEffect(shell.layer, shell.folderId, ui.isOpen) { edit.close() }
    LaunchedEffect(shell.layer) { uiPrefs.setMode(shell.layer) }
    // One-step undo for a drag edit; the snackbar expires with the snapshot.
    LaunchedEffect(edit.undoAvailable) {
        if (edit.undoAvailable) {
            delay(Metrics.snackbarMs)
            edit.clearUndo()
        }
    }
    val sessionRef = rememberUpdatedState(session)
    // OPUS3: warm the All-apps ring while Home is shown, so the first hub tap after a launch or a
    // resume shows the ring with real icons almost at once. Icons are loaded into the (OPUS2-sized)
    // cache first; after things are quiet the ring scene is built off the main thread and the
    // renderer prepares its rasters in the background (never shown by itself, bounded memory).
    var warmAllApps by remember { mutableStateOf<RingState?>(null) }
    val warmInputs = AllAppsWarmUp.Inputs(liveReady && resumed && shell.layer == Layer.Home && !ui.isOpen,
        visibleApps.size, layout, sceneGeometry, rotations, revision, profiles, markWorkApps)
    LaunchedEffect(warmInputs.wanted, resumed) {
        if (!warmInputs.wanted || DemoMode.enabled) return@LaunchedEffect
        // Low priority: let Home finish appearing first, then load icons a few at a time.
        delay(AllAppsWarmUp.StartDelayMs)
        val ids = visibleApps.filterNot { it.id in layout.hidden }.map { it.id }
        for (chunk in ids.chunked(AllAppsWarmUp.ChunkSize)) {
            services.appIndex.preload(chunk, Metrics.iconBitmapPx)
            delay(AllAppsWarmUp.ChunkPauseMs)
        }
    }
    LaunchedEffect(warmInputs) {
        if (!warmInputs.wanted) return@LaunchedEffect
        delay(AllAppsWarmUp.StartDelayMs + AllAppsWarmUp.QuietMs)
        warmAllApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            LauncherScenes.build(sceneGeometry, layout, visibleApps, ShellState(layer = Layer.AllApps), "",
                rotations = rotations, profiles = profiles, markWorkApps = markWorkApps,
                shortcutHiddenPackages = focusHidden + lockedPackages) { sceneIcon(it) }
        }
    }
    // OPUS4: the All-apps letters turn the ring through this.
    val steer = remember { com.brightblu.parc.ring.RingSteer() }
    // OPUS5: read by the renderer at touch-down; written once the letter arc is placed below.
    val latestLetterArcRef = remember { mutableStateOf<LetterArc.Arc?>(null) }
    val hubModule = remember { com.brightblu.parc.ring.module.HubModuleState() }
    val renderer = remember(edit) {
        CanvasRingRenderer(module = hubModule, detectSearchSwipe = false, editing = edit, steer = steer,
            ignoreDown = { x, y -> latestLetterArcRef.value?.let { LetterArc.inZone(it, x, y) } == true },
            hapticsEnabled = { haptics }, feel = feel, arcFeel = { arcFeel }, joystickEnabled = { joystick },
            hubHold = { sessionRef.value.onHubLongPress() },
            look = { latestRenderLook }, clockDetail = { latestClockDetail },
            onSceneError = { SceneDiagnostics.record(it) },
            warmScene = { warmAllApps },
            emptyHold = { x, y -> sessionRef.value.onEmptyLongPress(x, y) }, cardAlphaIsOpacity = true,
            refraction = { latestRefraction })
    }
    val swipe = remember { SearchSwipe() }
    val swipeUp = remember { SearchSwipeDetector(upward = true) }
    val pull = remember { PullState() }
    val hubScroll = remember { HubScrollGate() }
    val pullProgress = remember { mutableFloatStateOf(0f) }
    // T157: the search sheet and the module's dim share one spring (`Springs.sheet`); a touch mid-motion catches it.
    val sheet = rememberSheetProgress(ui.isOpen, 1f / skin.motionScale)
    val searchOpenNow by rememberUpdatedState(ui.isOpen)
    val sheetShown by remember { derivedStateOf { searchOpenNow || sheet.progress > 0f } }
    var pullReturn by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(ui.isOpen) {
        if (!ui.isOpen) { pullReturn?.cancel(); pullProgress.floatValue = 0f; pull.cancel() }
    }
    val cardTopDp = sceneGeometry.originYDp - Metrics.moduleCardAboveOriginDp
    val arcBottom = HubPanelLayout.bottomDp(RingHudBounds.bottomDp(scene,
        (skin.typography.label.sizeSp + Metrics.labelPaddingVerticalDp * 2) * density.fontScale,
        iconSizeDp, liveRotations), cardTopDp)
    val statusInset = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density) / density.density
    val hubTop = HubPanelLayout.topDp(statusInset)
    val hubBottom = hubTop + HubPanelLayout.heightDp(hubConfig.size, arcBottom - hubTop)
    val latestHubEligible by rememberUpdatedState(statsEligible(settings.statsVisible, shell.layer, ui.isOpen))
    // T171: each hub has its own opacity; a hub without one of its own follows the old single panel opacity.
    // T172: in glass the range and default differ, and "Keep text readable" picks each card's ink from the picture behind it.
    val glassNow = hubAppearance.glassEnabled(skin)
    val glassStage = com.brightblu.parc.ring.module.GlassWallpaperStage.current
    val measuring = glassNow && HubCards.keepReadable(look) && glassStage != null
    val screenWidthPx = (geometry.widthDp * density.density).toInt()
    val screenHeightPx = (geometry.heightDp * density.density).toInt()
    val arcBackdrop = remember(measuring, glassStage?.id, sceneGeometry.originXDp, sceneGeometry.originYDp, screenWidthPx, screenHeightPx) {
        if (!measuring || glassStage == null) null else {
            val r = com.brightblu.parc.ring.module.ModuleCard.rect(sceneGeometry.originXDp, sceneGeometry.originYDp, geometry.widthDp)
            val d = density.density
            com.brightblu.parc.ring.module.BackdropProbe.measure(glassStage.bitmap, screenWidthPx, screenHeightPx,
                (r.left * d).toInt(), (r.top * d).toInt(), (r.width * d).toInt(), (r.height * d).toInt())
        }
    }
    val statsBackdrop = remember(measuring, glassStage?.id, hubTop, hubBottom, screenWidthPx, screenHeightPx) {
        if (!measuring || glassStage == null) null else {
            val d = density.density
            com.brightblu.parc.ring.module.BackdropProbe.measure(glassStage.bitmap, screenWidthPx, screenHeightPx,
                (Metrics.panelGutterDp * d).toInt(), (hubTop * d).toInt(), ((geometry.widthDp - 2 * Metrics.panelGutterDp) * d).toInt(), ((hubBottom - hubTop) * d).toInt())
        }
    }
    // T176: both card skins come from HubCards, the one place that also feeds the Settings previews and the skin gallery.
    val hubCards = remember(skin, hubAppearance, look.wallpaper, look.wallpaperReadability, arcBackdrop, statsBackdrop) {
        HubCards.of(skin, look, hubAppearance, arcBackdrop, statsBackdrop)
    }
    val arcSkin = hubCards.arc
    val statsSkin = hubCards.stats
    val swipeBlocked by rememberUpdatedState(inputBlocked || edit.enabled ||
        menu != null || folderMenu != null || homeMenu != null || shell.layer == Layer.Folder || !liveReady || !resumed)
    val latestSearch by rememberUpdatedState(ui.isOpen)
    val latestAbc by rememberUpdatedState(ui.abcListOpen)
    val latestSettings by rememberUpdatedState(showSettings)
    val connection = remember(session, density, swipeUp) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!latestSearch && source == NestedScrollSource.UserInput) {
                    pull.panelScrolled(consumed.y)
                }
                // T69: a swipe that scrolled the hub panel is never a gesture action.
                if (consumed.y != 0f) swipeUp.cancel()
                if (latestSearch && !swipeBlocked && source == NestedScrollSource.UserInput && swipe.scroll(available.y / density.density,
                        consumed.y / density.density)) session.onSearch()
                return Offset.Zero
            }
        }
    }
    // OPUS4: the A to Z strip exists only on the All-apps ring, when it turns and nothing covers it.
    val allAppsRing = target.arcs.singleOrNull()?.takeIf { target.layer == Layer.AllApps }
    val stripLabels = remember(target.slots) { target.slots.map { it.label } }
    // OPUS5: the letters sit on an inner arc around the hub (the left-edge strip is gone).
    val letterArc = if (allAppsRing != null && LetterStrip.visible(shell.layer, ui.isOpen || inputBlocked,
            edit.enabled, target.slots.size, allAppsRing.slotCapacity))
        remember(allAppsRing, iconSizeDp) {
            val searchTop = com.brightblu.parc.ring.searchButtonTopDp(target.arcs)
            LetterArc.place(allAppsRing.originXDp, allAppsRing.originYDp, allAppsRing, iconSizeDp,
                Metrics.searchButtonLeftDp + Metrics.searchButtonTouchDp / 2f, searchTop + Metrics.searchButtonTouchDp / 2f)
        } else null
    val latestLetterArc by rememberUpdatedState(letterArc)
    SideEffect { latestLetterArcRef.value = letterArc }
    // The Canvas applies search alpha tokens. Avoid multiplying them by the sheet scrim again.
    val sheetSkin = remember(skin) { skin.copy(colors = skin.colors.copy(scrim = skin.colors.scrim and 0x00FFFFFFL)) }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null, LocalHubScrollGate provides hubScroll) {
        Box(Modifier.fillMaxSize().nestedScroll(connection).pointerInput(session, density, sceneGeometry, hubTop, hubBottom) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val downX = down.position.x / density.density
                val downY = down.position.y / density.density
                val searchAtStart = latestSearch
                if (sheet.moving) sheet.catchIt()
                pullReturn?.cancel()
                val fromHub = latestHubEligible && downY in hubTop..hubBottom &&
                    downX in Metrics.textGutterDp..(sceneGeometry.widthDp - Metrics.textGutterDp)
                if (replyKeyboardVisible && !fromHub) { homeFocus.clearFocus(); homeKeyboard?.hide() }
                // OPUS4: a touch on the letter strip never starts the search pull or swipe up.
                val onControlStart = !renderer.canStartSearchSwipe(downX, downY) ||
                    latestLetterArc?.let { LetterArc.inZone(it, downX, downY) } == true
                pull.start(downX, downY, down.uptimeMillis, fromHub = fromHub,
                    atScrollTop = hubScroll.atTop, blocked = swipeBlocked || searchAtStart,
                    onControl = onControlStart)
                // T69: swipe up. The same empty-space rule as the search pull, and never from the
                // hub panel (that surface scrolls); a chosen action of None disarms it entirely.
                val inHubPanel = fromHub && statsModuleVisible(latestHubEligible, services.stats.stats.value)
                swipeUp.start(downX, downY, down.uptimeMillis, blocked = !SwipeUpGate.armed(
                    gestureConfig.swipeUp, swipeBlocked || searchAtStart, onControlStart, inHubPanel))
                if (!searchAtStart) pullProgress.floatValue = 0f
                swipe.start(downX, downY, fromHub = kotlin.math.hypot(
                    downX - sceneGeometry.originXDp, downY - sceneGeometry.originYDp) <= Metrics.hubTouchDp / 2f,
                    anywhere = searchAtStart)
                val tracker = VelocityTracker()
                var moved = false
                var dismissed = false
                var opened = false
                var thresholdTicked = false
                try { do {
                    val event = awaitPointerEvent(if (searchAtStart || fromHub) PointerEventPass.Final else PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) { pull.cancel(); swipeUp.cancel() }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > Metrics.gridDp * 2 * density.density) moved = true
                    val dx = (change.position.x - down.position.x) / density.density
                    val dy = (change.position.y - down.position.y) / density.density
                    // T157: a swipe up never dismisses, and neither does a swipe down that scrolls the list (the list
                    // consumes it); the dismiss rule lives in SheetDismissRule and also sees the nested scroll above.
                    if (searchAtStart) tracker.addPointerInputChange(change)
                    if (searchAtStart && !dismissed && !latestSettings && latestSearch && !latestAbc &&
                        (swipe.move(change.position.x / density.density, change.position.y / density.density, change.isConsumed,
                            tracker.calculateVelocity().y / density.density) ||
                            !change.pressed && !moved && !change.isConsumed)) {
                        session.dismissSearch()
                        dismissed = true
                    }
                    if (swipeBlocked || fromHub && !hubScroll.atTop) pull.cancel()
                    if (!searchAtStart) {
                        if (pull.move(change.position.x / density.density, change.position.y / density.density)) {
                            thresholdTicked = true
                            if (haptics) feel.play(view, FeelEvent.Tick)
                        }
                        pullProgress.floatValue = pull.progress
                        if (!change.pressed && pull.release(change.position.x / density.density,
                                change.position.y / density.density, change.uptimeMillis)) {
                            opened = true
                            pullProgress.floatValue = 1f
                            if (!thresholdTicked && haptics) feel.play(view, FeelEvent.Tick)
                            session.onSearch()
                        }
                        // Prevent a preview gesture from releasing on a renderer control.
                        if (pullProgress.floatValue > 0f || opened) change.consume()
                    }
                    if (!searchAtStart && !opened &&
                        swipeUp.move(change.position.x / density.density, change.position.y / density.density,
                            change.uptimeMillis, released = !change.pressed)) {
                        change.consume()
                        session.onSwipeUp()
                    }
                } while (event.changes.any { it.pressed })
                } finally {
                    if (!searchAtStart && !opened) {
                        pull.cancel()
                        val from = pullProgress.floatValue
                        pullReturn = scope.launch {
                            animate(from, 0f, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                                pullProgress.floatValue = value.coerceAtLeast(0f)
                            }
                        }
                    }
                    swipeUp.cancel()
                    if (!dismissed && sheet.caught) sheet.resume()
                }
            }
        }) {
            Box(Modifier.fillMaxSize()) {
                com.brightblu.parc.ring.module.HubModule(renderer, target, arcSkin, session, hubModule)
                // T157: the module dims to 35 % on the sheet spring. A layer alpha cannot be used: the module is one
                // Canvas of overlapping draws and the platform ignores the layer alpha for it. A cover in the background
                // colour with 65 % opacity gives the same picture, costs one rectangle and only while the sheet is up.
                val cover = Color(skin.colors.bg.toInt())
                Box(Modifier.fillMaxSize().drawBehind {
                    val p = sheet.progress.coerceIn(0f, 1f)
                    if (p > 0f) drawRect(cover, alpha = (1f - Metrics.searchModuleAlpha) * p)
                })
            }
            letterArc?.let { AllAppsLetterArc(it, stripLabels, steer, skin, haptics, feel) }
            if (edit.enabled) {
                // T166: below the status bar and any cutout like every page, the touch area one action edge gap from
                // the screen edge and the word itself on the screen gutter.
                Row(Modifier.align(Alignment.TopStart).padding(start = Metrics.actionEdgeGapDp.dp,
                    top = ParcSpacing.pageTopDp(statusInset).dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.accent.toInt()), fontSize = skin.typography.menu.sizeSp.sp)
                    Box(Modifier.sizeIn(minWidth = Metrics.minTouchDp.dp, minHeight = Metrics.minTouchDp.dp).clickable { edit.close() }
                        .padding(horizontal = Metrics.itemGapDp.dp), contentAlignment = Alignment.Center) {
                        BasicText("Done", style = style)
                    }
                    BasicText(if (edit.busy) "Saving…" else "", style = style)
                }
            }
            // OPUS8 (T133): what to do in edit mode, at the bottom. It takes no touches, so drags
            // and the + spots under it still work; it gives way to a notice or Undo.
            if (edit.enabled && edit.notice.isEmpty() && !edit.undoAvailable && edit.emptyPosition == null) {
                EditModeBar(skin, Modifier.align(Alignment.BottomCenter))
            }
            if (edit.notice.isNotEmpty()) BasicText(edit.notice, Modifier.align(Alignment.BottomCenter)
                .padding(bottom = Metrics.snackbarBottomDp.dp)
                .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.snackbarCornerDp.dp))
                .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
                style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.textSecondary.toInt()), fontSize = skin.typography.caption.sizeSp.sp))
            val statsEligibleNow = statsEligible(settings.statsVisible, shell.layer, ui.isOpen)
            val lean = com.brightblu.parc.ring.module.rememberLeanProgress(hubModule)
            androidx.compose.runtime.CompositionLocalProvider(LocalFocusRepository provides services.focus,
                LocalLockedPackages provides lockedPackages, LocalHiddenPackages provides (lockedPackages + focusHidden),
                LocalHubLook provides look,
                com.brightblu.parc.ring.module.LocalGlassRefraction provides hubCards.refraction,
                LocalFocusSentence provides { mode -> focusModeSentence(mode, services.unlockedApps.value, layout.folders) },
                LocalHubSettings provides { destination -> settingsInitialPage = destination; showSettings = true },
                LocalHomePresses provides homePresses,
                LocalEditFocus provides { settingsInitialPage = SettingsPage.Focus; showSettings = true }) {
            Box(Modifier.fillMaxSize().leanAway(lean)) {
            // T155: the standalone clock exists only while the stats panel is turned off; with the panel on, its
            // own Clock page keeps the job. It follows the pull-down search fade like the panel does.
            if (ClockFaces.standaloneVisible(settings.statsVisible, settings.clockVisible, shell.layer == Layer.Home,
                    ui.isOpen, edit.enabled || showSettings)) {
                HomeClock(skin, look, hubConfig.clock, clockDetailWithFocus, hubTop,
                    Modifier.align(Alignment.TopStart).graphicsLayer {
                        alpha = 1f - (1f - Metrics.searchPullMinHubAlpha) * pullProgress.floatValue
                    })
            }
            StatsModule(
                source = services.stats,
                alertState = services.statsAlertUi.state.collectAsState().value,
                onDismissAlert = services.statsAlertUi::dismiss,
                archive = services.statsArchive.archive.collectAsState().value,
                hubBottomDp = hubBottom,
                powerSource = services.powerStats,
                networkRepository = services.networkStats,
                screenTime = services.screenTime, newApps = services.newApps,
                appStorage = services.appStorage, volume = services.volume, comms = services.comms,
                entries = services.unlockedApps.collectAsState().value,
                onLaunch = session::launch,
                skin = statsSkin,
                eligible = statsEligibleNow && !edit.enabled && !showSettings,
                resumed = resumed,
                expanded = settings.statsExpanded,
                onToggleExpanded = {
                    scope.launch { hubStore.update { it.copy(size = it.size.next()) } }
                },
                pullProgress = { pullProgress.floatValue },
                hubTopDp = hubTop,
                clock = { ClockHubPage(statsSkin, look, clock, clockDetailWithFocus, settings.clockVisible) },
                focusActiveName = focus.activeName,
                focusAvailable = focusModes.isNotEmpty(),
                onToggleFocus = {
                    when (val action = FocusManual.toggle(focus, focusModes)) {
                        is FocusManualAction.On -> scope.launch { services.focus.quickStart(action.name, 60) }
                        is FocusManualAction.Off -> { pendingFocusExit = com.brightblu.parc.core.data.focus.FocusExit.Pass }
                        FocusManualAction.None -> Unit
                    }
                },
            )
            }
            }
            if (edit.undoAvailable) {
                UndoSnackbar(skin, message = edit.notice.ifEmpty { "Moved" }, onUndo = { edit.undo() },
                    modifier = Modifier.align(Alignment.BottomCenter))
            }
            if (!hintSeen && shell.layer == Layer.Home && !ui.isOpen && menu == null &&
                folderMenu == null && !edit.enabled && !showSettings && liveReady) {
                FirstRunHint(skin) { uiPrefs.markHintSeen(); hintSeen = true }
            }
            edit.emptyPosition?.let { position ->
                val arcIndex = target.arcs.indexOfFirst { it.id == position.arcId }
                EmptySlotSheet(EditModeText.emptySpotTitle(arcIndex, target.arcs.size),
                    visibleApps.filterNot { it.id in layout.hidden || it.suspended }, skin, showAddApp,
                    onNewFolder = { edit.emptyPosition = null; showAddApp = false; showFolderCreate = true },
                    onAddApp = { showAddApp = true },
                    onPickApp = { id ->
                        scope.launch {
                            val current = services.layout.layout.value
                            if (current.arcs.firstOrNull { it.arc.id == position.arcId }?.slots?.containsKey(position.index) == false) {
                                try { services.layout.pin(id, position); edit.notify("App added") }
                                catch (_: IllegalArgumentException) { edit.notify("Layout changed. Try again") }
                            } else edit.notify("Layout changed. Try again")
                            edit.emptyPosition = null; showAddApp = false
                        }
                    }, onClose = { edit.emptyPosition = null; showAddApp = false })
            }
            if (showFolderCreate) FolderEditorHost(services, skin, create = true) {
                showFolderCreate = false; edit.close(); navigation.emit(ShellEvent.ResetToBase)
            }
            if (showSettings) {
                ParcSettings(services = services, skin = skin, initialPage = settingsInitialPage, initialRow = settingsInitialRow,
                    alignedArcs = aligned, joystick = joystick,
                    hubDoubleTap = hubDoubleTap,
                    onSetHubDoubleTap = { hubDoubleTap = it; uiPrefs.setHubDoubleTap(it); session.setHubDoubleTap(it) },
                    gestures = gestureConfig,
                    onSetGesture = { slot, action -> scope.launch { gestureStore.set(slot, action) } },
                    badgesEnabled = badgesEnabled, badgesInAllApps = badgesInAllApps,
                    onSetBadgesEnabled = { badgesEnabled = it; uiPrefs.setNotificationBadges(it) },
                    onSetBadgesInAllApps = { badgesInAllApps = it; uiPrefs.setBadgesInAllApps(it) },
                    markWorkApps = markWorkApps,
                    onSetMarkWorkApps = { markWorkApps = it; uiPrefs.setMarkWorkApps(it) },
                    onSetAlignedArcs = { aligned = it; uiPrefs.setAlignAcrossArcs(it) },
                    onSetJoystick = { joystick = it; uiPrefs.setJoystickAim(it) },
                    onAndroidSettings = openAndroidSettings,
                    look = look,
                    onChangeLook = { change -> scope.launch { lookStore.update(change) } },
                    onClose = { showSettings = false; settingsInitialPage = SettingsPage.Root; settingsInitialRow = null })
            }
            if (sheetShown) {
                if (ui.isOpen && ui.abcListOpen) {
                    val index = remember(visibleApps, layout.hidden) { AbcIndex.of(visibleApps, layout.hidden) }
                    AbcListScreen(index, skin, session::launch)
                    AllAppsJoystick(sceneGeometry, skin, session::dismissSearch)
                } else if (!ui.abcListOpen) SearchSheet(search, sheetSkin, progress = { sheet.progress }, closing = !ui.isOpen, onLaunch = { id ->
                    // A successful search clears the T35 discoverability hint.
                    uiPrefs.markSearchSucceeded(); searchSucceeded = true; session.launch(id)
                }, hiddenLabels = hiddenLabels, modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
                    // OPUS8 (T133): Parc settings and its pages are findable; fixed page names only.
                    parcSettings = SettingsSearchEntries.all,
                    onOpenParcSetting = { key ->
                        SettingsSearchEntries.target(key)?.let { goal ->
                            session.dismissSearch()
                            settingsInitialPage = goal.page; settingsInitialRow = goal.row; showSettings = true
                        }
                    })
            }
            Box(Modifier.fillMaxWidth().leanAway(lean)) {
            PullSearchBar(skin, progress = { if (ui.abcListOpen) 0f else pullProgress.floatValue },
                committed = ui.isOpen && !ui.abcListOpen,
                query = ui.query, onQueryChange = search::onQueryChange)
            }
            // Menus are the top-most layer: the name sheet is a full-screen sibling, so it must be
            // declared after the hub, which paints its own background.
            menu?.let {
                if (it.page == MenuPage.NewFolder) {
                    FolderNameSheet(title = "New folder", draftName = it.draftName, nameIssue = it.nameIssue,
                        skin = skin, onNameChange = session::menuNameChanged,
                        onSave = { session.menuAction(MenuAction.SaveNewFolder, "") },
                        onCancel = { session.menuAction(MenuAction.CancelNewFolder, "") })
                } else {
                    LongPressMenu(menu = it, skin = skin, onDismiss = session::dismissMenu,
                        onAction = session::menuAction)
                }
            }
            folderMenu?.let {
                if (it.page == FolderPage.Rename) {
                    FolderNameSheet(title = "Rename folder", draftName = it.draftName, nameIssue = it.nameIssue,
                        skin = skin,
                        onNameChange = session::folderNameChanged,
                        onSave = { session.folderAction(MenuAction.Save, "") },
                        onCancel = { session.folderAction(MenuAction.Back, "") })
                } else {
                    FolderMenu(menu = it, skin = skin, onDismiss = session::dismissFolderMenu,
                        onAction = session::folderAction, onNameChange = session::folderNameChanged)
                }
            }
            homeMenu?.let {
                HomeMenu(it, skin, onDismiss = session::dismissHomeMenu, onRow = session::homeMenuAction)
            }
            shortcutMenu?.let { shortcut ->
                androidx.compose.material3.AlertDialog(onDismissRequest = session::dismissShortcutMenu,
                    containerColor = Color(skin.colors.surface.toInt()),
                    title = { BasicText(shortcut.label, style = com.brightblu.parc.feature.settings.ui.settingsText(skin)) },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = {
                        scope.launch { services.layout.updateLayout { ShortcutPlacement.remove(it, shortcut) } }
                        session.dismissShortcutMenu()
                    }) { BasicText("Remove", style = com.brightblu.parc.feature.settings.ui.settingsText(skin, skin.colors.accent)) } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = session::dismissShortcutMenu) {
                        BasicText("Cancel", style = com.brightblu.parc.feature.settings.ui.settingsText(skin, skin.colors.accent))
                    } })
            }
            pendingFocusLaunch?.let { id ->
                val mode = focus.active
                if (mode != null) FocusLaunchGate(apps.firstOrNull { it.id == id }?.label ?: "app", skin, services.focus, mode,
                    onDismiss = { pendingFocusLaunch = null },
                    onOpen = { approvedFocusLaunch = id; pendingFocusLaunch = null; session.launch(id) })
                else pendingFocusLaunch = null
            }

        }
    }
}

/** One-step undo for a drag edit (T30). Drawn above the ring, dismissed by its own timer. */
@Composable
private fun UndoSnackbar(skin: Skin, message: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = Metrics.snackbarBottomDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha),
                    RoundedCornerShape(Metrics.snackbarCornerDp.dp))
                .padding(start = Metrics.cardInsetDp.dp, end = Metrics.actionEdgeGapDp.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.cardInsetDp.dp),
        ) {
            BasicText(message, style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.textPrimary.toInt()),
                fontSize = skin.typography.menu.sizeSp.sp))
            Box(Modifier.clickable(onClick = onUndo).parcMinTouch().padding(horizontal = Metrics.itemGapDp.dp), contentAlignment = Alignment.Center) {
                BasicText("Undo", style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.accent.toInt()),
                    fontSize = skin.typography.menu.sizeSp.sp))
            }
        }
    }
}
