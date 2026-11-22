package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import com.brightblu.parc.model.readableAccent
import com.brightblu.parc.feature.settings.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import com.brightblu.parc.model.Springs
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcSpacing
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val LocalHubPreview = staticCompositionLocalOf { false }
/** T137: lets a page body open its own options sheet (Weather's "Choose city"). */
internal val LocalHubOpenOptions = staticCompositionLocalOf<() -> Unit> { {} }

/** OPUS8 (T133): Home key presses (LauncherSession.homePresses); 0 outside the launcher. */
internal val LocalHomePresses = compositionLocalOf { 0 }

@Composable
internal fun HubPages(
    skin: Skin,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggleExpanded: () -> Unit = {},
    /** T330: the full-screen view is open. Same pages and tiles, drawn over the whole screen. */
    fullScreen: Boolean = false,
    /** T330: the "Full-screen hub" setting; when off, the pull and the Expand control do nothing. */
    fullScreenEnabled: Boolean = true,
    onExpandFullScreen: () -> Unit = {},
    onCloseFullScreen: () -> Unit = {},
    /** T73: active focus mode name, or null. The switch is hidden until a mode exists. */
    focusLabel: String? = null,
    /** T73: true when at least one focus mode is configured. */
    focusAvailable: Boolean = false,
    onToggleFocus: () -> Unit = {},
    pageContent: @Composable (String, List<String>) -> Unit = { page, _ -> HubText("${page.replaceFirstChar { it.uppercase() }} · coming soon", skin) },
    comms: @Composable () -> Unit = {},
    /** T121/T130: the demo page body for the current page id, used only while demo is on. */
    demoContent: @Composable (String) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val config by store.config.collectAsState(initial = HubConfig())
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val feel = rememberFeelPlayer()
    // T258: the grip resizes what is on screen. A page with its own size keeps it own; every other page
    // follows the default, which is what the grip then changes.
    fun resize(page: String?, size: HubSize) {
        if (size != config.sizeFor(page)) {
            feel.play(view, com.brightblu.parc.ring.FeelEvent.Tick)
            scope.launch {
                store.update { if (page != null && page in it.pageSizes) it.withPageSize(page, size) else it.copy(size = size) }
            }
        }
    }
    val savedPage by store.lastPage.collectAsState(initial = TodayPage.Id)
    val demo = DemoMode.enabled && !LocalHubPreview.current
    // T130: demo pages are not part of HubConfig (Notes is hidden by default), so the demo list
    // is the source of truth for restoring the last page. Real mode keeps the config gate.
    // M13 review: a tapped note reminder shows the Notes page for this visit even when it is hidden or a Focus filter hides it.
    val noteRequest by NoteOpenRequests.pending.collectAsState()
    var forcedNotes by remember { mutableStateOf(false) }
    LaunchedEffect(noteRequest) { if (noteRequest != null) forcedNotes = true }
    val forcing = !demo && forcedNotes && savedPage == "notes"
    val selected = if (demo || forcing) savedPage else restoredHubPage(savedPage, config)
    fun select(id: String) { if (id != "notes") forcedNotes = false; scope.launch { store.rememberPage(id) } }
    var optionsPage by remember { mutableStateOf<String?>(null) }
    // T242: the small sheet that a hold on a page name opens.
    var quickPage by remember { mutableStateOf<String?>(null) }
    val reorderGate = remember { HubReorderGate() }
    // OPUS8 (T133): the Home key closes the hub's sheets and the open page's own dialogs (the page
    // body is keyed on [homeReset], like a page switch). The first page itself is chosen by the
    // host through the stored page. A count seen when the hub (re)appears is not a new press.
    val homePresses = LocalHomePresses.current
    val homeSeen = remember { homePresses }
    var homeReset by remember { mutableIntStateOf(0) }
    val openSettings = LocalHubSettings.current
    quickPage?.takeIf { it in config.orderedPages }?.let { id ->
        HubPageQuickSheet(id, skin, config, { change -> scope.launch { store.update(change) } },
            onDismiss = { quickPage = null }, onPageSettings = { quickPage = null; optionsPage = id })
    }
    optionsPage?.let { id ->
        HubPageOptionsSheet(id, skin, config, { change -> scope.launch { store.update(change) } },
            onDismiss = { optionsPage = null }, onFullSettings = { destination ->
                optionsPage = null; openSettings(destination)
            })
    }
    // A hidden remembered page falls back through the visible catalog; an all-hidden
    // configuration yields no content page.
    val focusRepo = LocalFocusRepository.current
    val focusState = focusRepo?.state?.collectAsState()?.value
    val focusSummary = focusRepo?.summary?.collectAsState()?.value
    var focusControls by remember { mutableStateOf(false) }
    LaunchedEffect(homePresses) {
        if (homePresses != homeSeen) { optionsPage = null; quickPage = null; focusControls = false; homeReset++ }
    }
    if (focusControls) androidx.compose.material3.AlertDialog(
        onDismissRequest = { focusControls = false },
        containerColor = Color(skin.colors.surface.toInt()),
        title = { HubText("Focus", skin) },
        text = { Column(Modifier.verticalScroll(rememberHubScrollState())) {
            if (focusState?.active == null) HubText("Focus is off", skin)
            FocusHubStatus(skin)
            if (focusState?.active == null && focusSummary == null) HubText("Choose a mode in Focus settings.", skin)
        } },
        confirmButton = { HubButton("Done", skin) { focusControls = false } },
        dismissButton = { HubButton("Focus settings", skin) { focusControls = false; openSettings(SettingsPage.Focus) } },
    )
    val pages = hubPagesFor(demo, config.pages, arcadeOpen = config.arcadeOpen) { focusState?.active?.hubPages?.let(HubCatalog::canonicalPages)?.contains(it) != false }
        .let { if (!forcing) it else withForcedHubPage(it, "notes", config.orderedPages) }
    val page = selected.takeIf { it in pages } ?: pages.firstOrNull()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeUp = WindowInsets.ime.getBottom(density) > 0
    val scrollGate = LocalHubScrollGate.current
    val arcadeHost = com.brightblu.parc.arcade.LocalArcadeHost.current
    val swipeExclusions = remember { HubSwipeExclusions() }
    val latestClose by rememberUpdatedState(onCloseFullScreen)
    Column(modifier.fillMaxWidth().fillMaxHeight().clipToBounds()
        .onGloballyPositioned { swipeExclusions.origin = it.positionInWindow() }
        .then(if (fullScreen) Modifier.semantics { contentDescription = HubFullScreen.ContainerDescription } else Modifier)
        // T167: direction lock. The first drag past the touch slop is either a page swipe (mostly
        // horizontal, taken here before any page sees it) or a scroll (anything else, left alone).
        // While the reply field owns the IME, horizontal drags belong to text selection.
        .then(if (imeUp) Modifier else Modifier.pointerInput(pages, page, fullScreen) {
            val slop = viewConfiguration.touchSlop
            val threshold = Metrics.hubPageSwipeDp.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // T240: a touch that starts on the page-name strip belongs to the strip's own horizontal scroll.
                // The strip's outer row is exactly one menu row high and is the first child of this column.
                val onStrip = HubTabGesture.startsOnStrip(down.position.y, Metrics.menuRowHeightDp.dp.toPx())
                if (onStrip && !fullScreen) return@awaitEachGesture
                // 1.5.1: a sideways-scrolling row on the page (the Library shelves) keeps its own horizontal drags.
                val sideways = swipeExclusions.contains(down.position)
                if (sideways && !fullScreen) return@awaitEachGesture
                // T330: a downward swipe closes the full-screen view only from the top of a page.
                val atTop = scrollGate?.atTop != false
                var closing = false
                var lock = HubDragLock.Undecided
                var dx = 0f
                var dy = 0f
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // T186: a tab held for reordering owns this finger; it is never a page swipe.
                    if (reorderGate.active) break
                    // T200: while a game is running, a drag on the page steers it; it is never a page swipe.
                    if (arcadeHost?.playing == true) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.positionChange()
                    dx += delta.x; dy += delta.y
                    if (lock == HubDragLock.Undecided) lock = HubPageScroll.lock(dx, dy, slop)
                    if (lock == HubDragLock.Horizontal && !onStrip && !sideways) {
                        change.consume()
                        if (!change.pressed && page != null && abs(dx) >= threshold) {
                            // OPUS1: pages loop (last -> first, first -> last).
                            hubPageAfterSwipe(pages, page, forward = dx < 0)?.let { if (it != page) select(it) }
                        }
                    } else if (fullScreen && lock == HubDragLock.Vertical &&
                        HubFullScreen.closesOnPageSwipe(atTop, dx, dy)) {
                        // T330: the view follows no finger; it simply closes once the gesture has gone far enough.
                        change.consume()
                        closing = true
                    }
                } while (event.changes.any { it.pressed })
                if (closing) latestClose()
            }
        })) {
        // T158: the tabs lead the card; the focus pass row shows only while a Focus mode is running.
        if (pages.isNotEmpty()) HubTabStrip(pages, page, skin, reorderGate = reorderGate,
            onLift = { feel.play(view, com.brightblu.parc.ring.FeelEvent.Menu) },
            onHold = if (demo) null else { id -> quickPage = id },
            onSlot = { feel.play(view, com.brightblu.parc.ring.FeelEvent.Tick) },
            // Demo tabs are sample pages: they never rewrite the saved order.
            onReorder = if (demo) null else { moved -> scope.launch { store.update { it.reorderVisible(pages, moved) } } }) { select(it) }
        // T260: the page-name hold has no sign on screen; say so once, the first time there are pages to reorder.
        val hintPrefs = remember(context) { ParcUiPrefs(context) }
        var pageNameHint by remember { mutableStateOf(false) }
        LaunchedEffect(pages.size, demo) {
            if (!demo && GestureHintRules.pageNameDue(hintPrefs.seenGestureHints(), pages.size)) {
                kotlinx.coroutines.delay(1_500)
                pageNameHint = true
            }
        }
        if (pageNameHint) GestureHintChip(GestureHint.PageName, skin,
            onGotIt = { hintPrefs.markGestureHintSeen(GestureHint.PageName.id); pageNameHint = false },
            onSkipAll = { hintPrefs.skipAllGestureHints(); pageNameHint = false })
        if (!demo && focusState?.active != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText("Focus · " + focusState.activeName.orEmpty(), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.readableAccent.toInt()), fontSize = skin.typography.caption.sizeSp.sp))
                    HubText(focusRemaining(focusState), skin)
                }
                Box(Modifier.heightIn(min = Metrics.focusControlDp.dp).widthIn(min = Metrics.focusControlDp.dp).semantics { contentDescription = "Emergency Focus pass" }
                    .clickable { focusControls = true }, contentAlignment = Alignment.Center) { HubText("Pass", skin) }
            }
        }
        if (page == null) {
            HubText("All pages hidden · open Hub pages in Settings", skin)
        } else {
            val showMoreBelow by remember(scrollGate) {
                derivedStateOf { scrollGate?.canScrollForward == true }
            }
            Box(Modifier.weight(1f)) {
            BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().pointerInput(page) {
                detectTapGestures(onLongPress = { optionsPage = page })
            }.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithCache {
                val fadeHeight = Metrics.hubFadeDrawDp.dp.toPx().coerceAtMost(size.height)
                // T158: the card can be frosted or see-through, so the fade is a mask on the content, not a colour.
                val fade = Brush.verticalGradient(0f to Color.Black, Metrics.hubFadeClearFraction to Color.Transparent, 1f to Color.Transparent,
                    startY = size.height - fadeHeight, endY = size.height)
                // T167: the fade only shows while a page has more content below it.
                onDrawWithContent {
                    drawContent()
                    if (scrollGate?.canScrollForward == true) drawRect(fade, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                }
            }) {
                val configuredTiles = config.tiles(page).filterNot { it in config.hiddenTiles[page].orEmpty() }
                // T330: the full-screen view shows every tile of the page, like Tall does in the card.
                val shownSize = HubFullScreen.sizeFor(fullScreen, config.sizeFor(page))
                val tiles = if (page == "timers" || page == DevicePage.Id || page == ControlsPage.Id) configuredTiles
                    else HubPanelLayout.visibleTiles(configuredTiles, shownSize, maxHeight.value, density.fontScale)
                val rows = tiles.size
                val cellHeight = HubPanelLayout.tileHeightDp(maxHeight.value, rows,
                    if (fullScreen) Metrics.hubFullTileMaxHeightDp else Metrics.hubTileMaxHeightDp)
                CompositionLocalProvider(LocalHubSize provides shownSize, LocalHubTileHeight provides cellHeight,
                    LocalHubOpenOptions provides { optionsPage = page },
                    LocalHubSwipeExclusions provides swipeExclusions,
                    LocalTodayVisiblePages provides pages.toSet(),
                    LocalTodayOpenPage provides { id -> if (id in pages) select(id) },
                    LocalHubContentHeight provides maxHeight.value) { key(page, homeReset) {
                    if (tiles.isEmpty() && page !in HubCatalog.tilelessPages) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CellLabel("No tiles shown · use …", skin) }
                    } else when (page) {
                        "comms" -> if (demo) demoContent("comms") else comms()
                        "notes" -> if (demo) demoContent("notes") else NotesHubPage(skin)
                        else -> if (demo) demoContent(page) else pageContent(page, tiles)
                    }
                } }
                // T262: a page taller than the panel says so in words, not only with a faint fade.
            }
                if (showMoreBelow) {
                    Box(Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = Metrics.hubScrollHintBottomDp.dp)
                        .background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha),
                            RoundedCornerShape(Metrics.hubScrollHintCornerDp.dp))
                        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()),
                            RoundedCornerShape(Metrics.hubScrollHintCornerDp.dp))
                        .padding(horizontal = Metrics.itemGapDp.dp, vertical = Metrics.lineGapDp.dp)
                        .semantics { contentDescription = "More below. Scroll the page to see it." },
                        contentAlignment = Alignment.Center) {
                        BasicText("More below", style = hudLabelStyle(skin).copy(color = Color(skin.colors.textSecondary.toInt())))
                    }
                }
            }
        }
        // T219: the Focus chip is the grip's stateful front door. It is shown whenever a mode exists or is
        // running; when Focus is not set up it stays away, so the hub never has a dead control.
        val focusChip = FocusChip.state(
            activeName = focusState?.activeName,
            untilMillis = focusState?.untilMillis,
            accent = focusState?.active?.accent,
            available = focusAvailable,
            pinned = "focus" in pages,
        )
        // T158: the grip keeps the middle; the page's action and its options sit at the card's inset on either side.
        Row(Modifier.fillMaxWidth().height(Metrics.hubGripHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                when (HubGripPriority.start(page, !demo && !LocalHubPreview.current && focusChip.visible)) {
                    HubGripStart.Alarms -> HubButton("Alarms", skin, "Open alarms") {
                        runCatching { context.startActivity(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
                    }
                    HubGripStart.Focus -> FocusChip(focusChip, skin) {
                        if (focusChip.opensFocusPage) select("focus") else focusControls = true
                    }
                    HubGripStart.None -> Unit
                }
            }
            Box(Modifier.width(Metrics.hubGripTouchWidthDp.dp).fillMaxHeight()
                .semantics {
                    contentDescription = if (fullScreen) HubFullScreen.GripDescription
                    else "Hub size: ${config.sizeFor(page)}. Tap to cycle; drag to resize" +
                        (if (fullScreenEnabled) "; pull up for full screen" else "")
                }
                .clickable(enabled = !imeUp) { if (fullScreen) onCloseFullScreen() else resize(page, config.sizeFor(page).next()) }
                .pointerInput(fullScreen, fullScreenEnabled, config.sizeFor(page), imeUp) {
                    var travel = 0f
                    detectVerticalDragGestures(onDragStart = { travel = 0f },
                        onVerticalDrag = { change, amount -> if (!imeUp) { change.consume(); travel += amount } },
                        onDragEnd = {
                            if (imeUp) return@detectVerticalDragGestures
                            val travelDp = travel / this.density
                            when {
                                // T330: pulling the grip up opens the full-screen view; inside it, a downward drag closes.
                                fullScreen -> if (HubFullScreen.closesOnGripPull(travelDp)) onCloseFullScreen()
                                HubFullScreen.opensOnGripPull(travelDp, fullScreenEnabled) -> onExpandFullScreen()
                                // A shorter drag keeps the card resize it always had.
                                abs(travel) > Metrics.hubPageSwipeDp.dp.toPx() / 2f -> {
                                    val steps = (travel / Metrics.hubPageSwipeDp.dp.toPx()).roundToInt()
                                    resize(page, HubSize.entries[(config.sizeFor(page).ordinal + steps).coerceIn(0, HubSize.entries.lastIndex)])
                                }
                            }
                        })
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(Metrics.hubGripWidthDp.dp, Metrics.hubGripLineDp.dp)
                    .background(Color(skin.colors.textSecondary.toInt()), CircleShape))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (page != null) {
                    // T330: the explicit way in, and the way out once inside.
                    if (fullScreen) HubButton(HubFullScreen.CloseLabel, skin, HubFullScreen.CloseDescription) { onCloseFullScreen() }
                    else if (fullScreenEnabled) HubButton(HubFullScreen.ExpandLabel, skin, HubFullScreen.ExpandDescription) { onExpandFullScreen() }
                    HubButton("…", skin, "${hubPageName(page)} page options") { optionsPage = page }
                }
            }
        }
    }
}

@Composable
private fun HubText(text: String, skin: Skin, modifier: Modifier = Modifier) {
    BasicText(text, modifier, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = hudValueStyle(skin, role = HubType.section(skin.typography)))
}

@Composable
private fun HubButton(text: String, skin: Skin, description: String = text, enabled: Boolean = true, compact: Boolean = false, click: () -> Unit) {
    Box(Modifier.height((if (compact) Metrics.hubHeaderHeightDp else Metrics.menuRowHeightDp).dp).widthIn(min = Metrics.menuRowHeightDp.dp)
        .semantics { contentDescription = description }.clickable(enabled = enabled, onClick = click)
        .padding(horizontal = Metrics.lineGapDp.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = TextStyle(fontFamily = skin.textFamily, color = Color((if (enabled) skin.colors.readableAccent else skin.colors.lineActive).toInt()), fontSize = skin.typography.caption.sizeSp.sp))
    }
}

/**
 * 1.5.1: places on a hub page that scroll sideways themselves. A drag that starts inside one is not a page swipe.
 * Positions are window coordinates; [origin] is the page column's own window position.
 */
internal class HubSwipeExclusions {
    var origin: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero
    val rects = HashMap<Any, androidx.compose.ui.geometry.Rect>()
    fun contains(local: androidx.compose.ui.geometry.Offset): Boolean {
        val p = origin + local
        return rects.values.any { it.contains(p) }
    }
}

internal val LocalHubSwipeExclusions = staticCompositionLocalOf<HubSwipeExclusions?> { null }

/** Marks this element as one that scrolls sideways on its own, so the hub's page swipe leaves it alone. */
@Composable
internal fun Modifier.hubOwnsHorizontalDrag(): Modifier {
    val exclusions = LocalHubSwipeExclusions.current ?: return this
    val key = remember { Any() }
    DisposableEffect(exclusions) { onDispose { exclusions.rects.remove(key) } }
    return this.onGloballyPositioned { exclusions.rects[key] = it.boundsInWindow() }
}
