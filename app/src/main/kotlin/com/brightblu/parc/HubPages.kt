package com.brightblu.parc

import com.brightblu.parc.feature.settings.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
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
    val headerTime by store.headerTime.collectAsState(initial = false)
    val time = rememberHubTime()
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
    Column(modifier.fillMaxWidth().fillMaxHeight().clipToBounds()
        .background(if (LocalHubLook.current.wallpaper in setOf(com.brightblu.parc.model.WallpaperStyle.System, com.brightblu.parc.model.WallpaperStyle.Image)) Color.Transparent else Color(skin.colors.bg.toInt()))
        .pointerInput(pages, page, imeUp) {
            var travel = 0f
            detectHorizontalDragGestures(
                onDragStart = { travel = 0f },
                onHorizontalDrag = { change, amount ->
                    // While the reply field owns the IME, horizontal drags belong to text
                    // selection, not page switching.
                    if (!imeUp) { change.consume(); travel += amount }
                },
                onDragEnd = {
                    if (page != null && abs(travel) >= Metrics.hubPageSwipeDp.dp.toPx()) {
                        // OPUS1: pages loop (last -> first, first -> last).
                        hubPageAfterSwipe(pages, page, forward = travel < 0)?.let { if (it != page) select(it) }
                    }
                },
            )
        }) {
        Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp).drawWithCache {
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(0f, size.height); lineTo(size.width, size.height) }
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(skin.lineWidths.baseDp.dp.toPx())
            val tint = Color(skin.colors.lineBase.toInt())
            onDrawBehind { if (skin.style.header == HeaderStyle.ruled) drawPath(p, tint, style = stroke) }
        }, verticalAlignment = Alignment.CenterVertically) {
            if (!demo && focusState?.active != null) {
                Column(Modifier.weight(1f)) {
                    BasicText(hubPageTitle(demo, page) + " · " + focusState.activeName.orEmpty(), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = TextStyle(color = Color(skin.colors.accent.toInt()), fontSize = skin.typography.caption.sizeSp.sp))
                    HubText(focusRemaining(focusState), skin)
                }
                Box(Modifier.size(Metrics.focusControlDp.dp).semantics { contentDescription = "Emergency Focus pass" }
                    .clickable {
                        focusControls = true
                    }, contentAlignment = Alignment.Center) { HubText("Pass", skin) }
            } else {
                HubText(hubPageTitle(demo, page) + if (headerTime) " · $time" else "", skin, Modifier.weight(1f).height(Metrics.hubHeaderHeightDp.dp).wrapContentHeight(Alignment.CenterVertically))
                if (!demo && (focusAvailable || focusSummary != null)) {
                    HubButton(if (focusSummary != null) "Summary" else "Focus", skin, compact = true) {
                        if ("focus" in pages) select("focus")
                        else if (focusSummary != null) focusControls = true else onToggleFocus()
                    }
                }
            }
            if (page != null) HubButton(hubTemplates.getValue(page).action, skin) {
                if (page == "clock") {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                    runCatching { context.startActivity(intent) }
                } else optionsPage = page
            }
            if (page != null) HubButton("…", skin, "${hubPageName(page)} page options") {
                optionsPage = page
            }


        }
        if (page == null) {
            HubText("All pages hidden · open Hub pages in Settings", skin)
        } else {
            BoxWithConstraints(Modifier.weight(1f).clipToBounds().pointerInput(page) {
                detectTapGestures(onLongPress = { optionsPage = page })
            }.drawWithCache {
                val fadeHeight = Metrics.hubFadeHeightDp.dp.toPx().coerceAtMost(size.height)
                val background = Color(skin.colors.bg.toInt())
                val fade = Brush.verticalGradient(listOf(background.copy(alpha = 0f), background),
                    startY = size.height - fadeHeight, endY = size.height)
                onDrawWithContent { drawContent(); drawRect(fade) }
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
            if (config.size != HubSize.Compact) Row(Modifier.fillMaxWidth().height(Metrics.hubDotsHeightDp.dp), horizontalArrangement = Arrangement.Center) {
                val start = (pages.indexOf(page) - 3).coerceIn(0, (pages.size - 7).coerceAtLeast(0))
                pages.drop(start).take(7).forEach { id ->
                    Box(Modifier.size(Metrics.menuRowHeightDp.dp).semantics { contentDescription = "${hubPageName(id)} page${if (id == page) ", selected" else ""}" }
                        .clickable { select(id) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (id == page) Metrics.hubDotActiveWidthDp.dp else Metrics.hudDotDp.dp, Metrics.hudDotDp.dp)
                            .background(Color((if (id == page) skin.colors.accent else skin.colors.textSecondary).toInt()), CircleShape))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(Metrics.hubGripHeightDp.dp)
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
        fontFamily = if (skin.style.clockFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else if (skin.style.header == HeaderStyle.ruled) androidx.compose.ui.text.font.FontFamily.Serif else null))
}

@Composable
private fun HubButton(text: String, skin: Skin, description: String = text, enabled: Boolean = true, compact: Boolean = false, click: () -> Unit) {
    Box(Modifier.height((if (compact) Metrics.hubHeaderHeightDp else Metrics.menuRowHeightDp).dp).widthIn(min = Metrics.menuRowHeightDp.dp)
        .semantics { contentDescription = description }.clickable(enabled = enabled, onClick = click)
        .padding(horizontal = Metrics.gridDp.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = TextStyle(fontFamily = if (skin.typography.fallbackFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else null, color = Color((if (enabled) skin.colors.accent else skin.colors.lineActive).toInt()), fontSize = skin.typography.caption.sizeSp.sp))
    }
}
