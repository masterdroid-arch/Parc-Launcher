package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import com.brightblu.parc.feature.settings.ui.*

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcSpacing
import com.brightblu.parc.model.HeaderStyle
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
    /** T73: active focus mode name, or null. The switch is hidden until a mode exists. */
    focusLabel: String? = null,
    clock: @Composable () -> Unit = {},
    /** T73: true when at least one focus mode is configured. */
    focusAvailable: Boolean = false,
    onToggleFocus: () -> Unit = {},
    pageContent: @Composable (String, List<String>) -> Unit = { page, _ -> HubText("${page.replaceFirstChar { it.uppercase() }} · coming soon", skin) },
    comms: @Composable () -> Unit = {},
    /** T121/T130: the demo page body for the current page id, used only while demo is on. */
    demoContent: @Composable (String) -> Unit = {},
    system: @Composable (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val config by store.config.collectAsState(initial = HubConfig())
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val feel = rememberFeelPlayer()
    fun resize(size: HubSize) {
        if (size != config.size) {
            feel.play(view, com.brightblu.parc.ring.FeelEvent.Tick)
            scope.launch { store.update { it.copy(size = size) } }
        }
    }
    val savedPage by store.lastPage.collectAsState(initial = "clock")
    val demo = DemoMode.enabled && !LocalHubPreview.current
    // T130: demo pages are not part of HubConfig (Notes is hidden by default), so the demo list
    // is the source of truth for restoring the last page. Real mode keeps the config gate.
    val selected = if (demo) savedPage else restoredHubPage(savedPage, config)
    fun select(id: String) { scope.launch { store.rememberPage(id) } }
    var optionsPage by remember { mutableStateOf<String?>(null) }
    // OPUS8 (T133): the Home key closes the hub's sheets and the open page's own dialogs (the page
    // body is keyed on [homeReset], like a page switch). The first page itself is chosen by the
    // host through the stored page. A count seen when the hub (re)appears is not a new press.
    val homePresses = LocalHomePresses.current
    val homeSeen = remember { homePresses }
    var homeReset by remember { mutableIntStateOf(0) }
    val openSettings = LocalHubSettings.current
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
        if (homePresses != homeSeen) { optionsPage = null; focusControls = false; homeReset++ }
    }
    if (focusControls) androidx.compose.material3.AlertDialog(
        onDismissRequest = { focusControls = false },
        containerColor = Color(skin.colors.surface.toInt()),
        title = { HubText("Focus", skin) },
        text = { Column(Modifier.verticalScroll(rememberHubScrollState())) { FocusHubStatus(skin) } },
        confirmButton = { HubButton("Done", skin) { focusControls = false } },
    )
    val pages = hubPagesFor(demo, config.pages) { focusState?.active?.hubPages?.contains(it) != false }
    val page = selected.takeIf { it in pages } ?: pages.firstOrNull()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeUp = WindowInsets.ime.getBottom(density) > 0
    val scrollGate = LocalHubScrollGate.current
    Column(modifier.fillMaxWidth().fillMaxHeight().clipToBounds()
        // T167: direction lock. The first drag past the touch slop is either a page swipe (mostly
        // horizontal, taken here before any page sees it) or a scroll (anything else, left alone).
        // While the reply field owns the IME, horizontal drags belong to text selection.
        .then(if (imeUp) Modifier else Modifier.pointerInput(pages, page) {
            val slop = viewConfiguration.touchSlop
            val threshold = Metrics.hubPageSwipeDp.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var lock = HubDragLock.Undecided
                var dx = 0f
                var dy = 0f
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.positionChange()
                    dx += delta.x; dy += delta.y
                    if (lock == HubDragLock.Undecided) lock = HubPageScroll.lock(dx, dy, slop)
                    if (lock == HubDragLock.Horizontal) {
                        change.consume()
                        if (!change.pressed && page != null && abs(dx) >= threshold) {
                            // OPUS1: pages loop (last -> first, first -> last).
                            hubPageAfterSwipe(pages, page, forward = dx < 0)?.let { if (it != page) select(it) }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        })) {
        // T158: the tabs lead the card; the focus pass row shows only while a Focus mode is running.
        if (pages.isNotEmpty()) HubTabStrip(pages, page, skin) { select(it) }
        if (!demo && focusState?.active != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText("Focus · " + focusState.activeName.orEmpty(), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.accent.toInt()), fontSize = skin.typography.caption.sizeSp.sp))
                    HubText(focusRemaining(focusState), skin)
                }
                Box(Modifier.heightIn(min = Metrics.focusControlDp.dp).widthIn(min = Metrics.focusControlDp.dp).semantics { contentDescription = "Emergency Focus pass" }
                    .clickable { focusControls = true }, contentAlignment = Alignment.Center) { HubText("Pass", skin) }
            }
        }
        if (page == null) {
            HubText("All pages hidden · open Hub pages in Settings", skin)
        } else {
            BoxWithConstraints(Modifier.weight(1f).clipToBounds().pointerInput(page) {
                detectTapGestures(onLongPress = { optionsPage = page })
            }.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithCache {
                val fadeHeight = Metrics.hubFadeHeightDp.dp.toPx().coerceAtMost(size.height)
                // T158: the card can be frosted or see-through, so the fade is a mask on the content, not a colour.
                val fade = Brush.verticalGradient(listOf(Color.Black, Color.Transparent),
                    startY = size.height - fadeHeight, endY = size.height)
                // T167: the fade only shows while a page has more content below it.
                onDrawWithContent {
                    drawContent()
                    if (scrollGate?.canScrollForward == true) drawRect(fade, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                }
            }) {
                val configuredTiles = config.tiles(page).filterNot { it in config.hiddenTiles[page].orEmpty() }
                val tiles = if (page == "timers" || page == QuickToggleCatalog.PageId) configuredTiles else HubPanelLayout.visibleTiles(configuredTiles, config.size, maxHeight.value, density.fontScale)
                val rows = if (page == "system") (tiles.size + 1) / 2 else tiles.size
                val cellHeight = HubPanelLayout.tileHeightDp(maxHeight.value, rows)
                CompositionLocalProvider(LocalHubSize provides config.size, LocalHubTileHeight provides cellHeight,
                    LocalHubOpenOptions provides { optionsPage = page },
                    LocalHubContentHeight provides maxHeight.value) { key(page, homeReset) {
                    if (tiles.isEmpty() && page !in setOf("clock", "comms", "focus", "notes", WeatherVariant.PageId)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CellLabel("No tiles shown · use …", skin) }
                    } else when (page) {
                        "clock" -> clock()
                        "comms" -> if (demo) demoContent("comms") else comms()
                        "notes" -> if (demo) demoContent("notes") else NotesHubPage(skin)
                        "focus" -> if (demo) demoContent("focus") else pageContent(page, tiles)
                        "power" -> if (demo) demoContent("power") else pageContent(page, tiles)
                        "system" -> system(tiles)
                        else -> if (demo) demoContent(page) else pageContent(page, tiles)
                    }
                } }
            }
        }
        // T158: the grip keeps the middle; the page's action and its options sit at the card's inset on either side.
        val focusButton = if (demo || focusState?.active != null || page == "clock") null
            else if (focusSummary != null) "Summary" else if (focusAvailable && "focus" !in pages) "Focus" else null
        Row(Modifier.fillMaxWidth().height(Metrics.hubGripHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (page == "clock") HubButton("Alarms", skin, "Open alarms") {
                    runCatching { context.startActivity(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
                } else if (focusButton != null) HubButton(focusButton, skin) {
                    if ("focus" in pages) select("focus")
                    else if (focusSummary != null) focusControls = true else onToggleFocus()
                }
            }
            Box(Modifier.width(Metrics.hubGripTouchWidthDp.dp).fillMaxHeight()
                .semantics { contentDescription = "Hub size: ${config.size}. Tap to cycle; drag to resize" }
                .clickable(enabled = !imeUp) { resize(config.size.next()) }
                .pointerInput(config.size, imeUp) {
                    var travel = 0f
                    detectVerticalDragGestures(onDragStart = { travel = 0f },
                        onVerticalDrag = { change, amount -> if (!imeUp) { change.consume(); travel += amount } },
                        onDragEnd = {
                            if (!imeUp && abs(travel) > Metrics.hubPageSwipeDp.dp.toPx() / 2f) {
                                val steps = (travel / Metrics.hubPageSwipeDp.dp.toPx()).roundToInt()
                                resize(HubSize.entries[(config.size.ordinal + steps).coerceIn(0, HubSize.entries.lastIndex)])
                            }
                        })
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(Metrics.hubGripWidthDp.dp, Metrics.hubGripLineDp.dp)
                    .background(Color(skin.colors.textSecondary.toInt()), CircleShape))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (page != null) HubButton("…", skin, "${hubPageName(page)} page options") { optionsPage = page }
            }
        }
    }
}

@Composable
internal fun HubPageSettings(skin: Skin, config: HubConfig, update: ((HubConfig) -> HubConfig) -> Unit) {
    val undo = rememberEditorUndo()
    var optionsPage by remember { mutableStateOf<String?>(null) }
    val openSettings = LocalHubSettings.current
    optionsPage?.let { id ->
        HubPageOptionsSheet(id, skin, config, update, { optionsPage = null }) { destination ->
            optionsPage = null; openSettings(destination)
        }
    }
    EditorScaffold(EditorSpec.HubPages, skin, empty = config.orderedPages.all { it in config.hiddenPages },
        undo = if (undo.action != null) ({ undo.run() }) else null,
        preview = { BasicText("On Home: " + config.orderedPages.filterNot { it in config.hiddenPages }.joinToString(" · "), style = settingsText(skin, skin.colors.accent, true)) }) {
        HubSizeChoices(skin, config, update)
        config.orderedPages.forEachIndexed { index, page ->
            val shown = page !in config.hiddenPages
            SettingsCard(skin) {
                SettingRow(hubPageName(page), skin, SettingKind.Toggle, checked = shown) {
                    update { it.showPage(page, !shown) }
                    if (shown) undo.offer { update { it.showPage(page, true) } }
                }
                SettingRow("${hubPageName(page)} options", skin, SettingKind.Page,
                    description = "Change this page's content, order and visibility") { optionsPage = page }
                Row {
                    HubButton("↑", skin, "Move $page page up", index > 0) { update { it.movePage(page, -1) } }
                    HubButton("↓", skin, "Move $page page down", index < config.orderedPages.lastIndex) { update { it.movePage(page, 1) } }
                }
            }
        }
    }
}

@Composable
private fun HubText(text: String, skin: Skin, modifier: Modifier = Modifier) {
    BasicText(text, modifier, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = TextStyle(color = Color(skin.colors.textPrimary.toInt()), fontSize = Metrics.hubNameSp.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontFamily = if (skin.style.clockFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else if (skin.style.header == HeaderStyle.ruled) androidx.compose.ui.text.font.FontFamily.Serif else skin.textFamily))
}

@Composable
private fun HubButton(text: String, skin: Skin, description: String = text, enabled: Boolean = true, compact: Boolean = false, click: () -> Unit) {
    Box(Modifier.height((if (compact) Metrics.hubHeaderHeightDp else Metrics.menuRowHeightDp).dp).widthIn(min = Metrics.menuRowHeightDp.dp)
        .semantics { contentDescription = description }.clickable(enabled = enabled, onClick = click)
        .padding(horizontal = Metrics.lineGapDp.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = TextStyle(fontFamily = skin.textFamily, color = Color((if (enabled) skin.colors.accent else skin.colors.lineActive).toInt()), fontSize = skin.typography.caption.sizeSp.sp))
    }
}
