package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.stats.StatsArchive
import com.brightblu.parc.core.data.stats.StatsAlertMetric
import com.brightblu.parc.core.data.stats.DeviceStatsSource
import com.brightblu.parc.core.data.stats.StatsFormat
import com.brightblu.parc.core.data.stats.StatsHistory
import com.brightblu.parc.core.data.demo.DemoStatsStore
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcSpacing
import com.brightblu.parc.model.StatsFrame
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypeToken
import java.io.File

/**
 * App-owned stats hub. Sampling stays gated by resume, screen-on and Home eligibility.
 * Configuration and paging live outside the ring renderer; snapshots recompose only this HUD.
 */
@Composable
fun BoxScope.StatsModule(
    source: DeviceStatsSource,
    hubBottomDp: Float,
    alertState: StatsAlertUiState = StatsAlertUiState(),
    onDismissAlert: (StatsAlertMetric) -> Unit = {},
    archive: StatsArchive = StatsArchive.Empty,
    powerSource: com.brightblu.parc.core.data.power.PowerStatsSource? = null,
    networkRepository: com.brightblu.parc.core.data.network.NetworkStatsRepository? = null,
    dayLedger: com.brightblu.parc.core.data.ledger.DayLedgerRepository? = null,
    newApps: com.brightblu.parc.core.data.apps.NewAppsProvider? = null,
    appStorage: com.brightblu.parc.core.data.storage.AppStorageRepository? = null,
    volume: com.brightblu.parc.core.data.audio.VolumeRepository? = null,
    comms: com.brightblu.parc.core.data.comms.CommsExtrasRepository? = null,
    entries: List<com.brightblu.parc.model.AppEntry> = emptyList(),
    onLaunch: (com.brightblu.parc.model.AppId) -> Unit = {},
    skin: Skin,
    eligible: Boolean,
    resumed: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    pullProgress: () -> Float = { 0f },
    hubTopDp: Float = Metrics.keepOutTopDp + Metrics.hubTopGapDp,
    /** T330: the full-screen view is open; the card fills the screen and every page keeps its place. */
    fullScreen: Boolean = false,
    /** T330: the "Full-screen hub" setting. */
    fullScreenEnabled: Boolean = true,
    onExpandFullScreen: () -> Unit = {},
    onCloseFullScreen: () -> Unit = {},
    /** T73: the active focus mode, shown as the hub header switch; null when nothing is active. */
    focusActiveName: String? = null,
    /** T73: true when at least one mode is configured, so the hub offers the switch. */
    focusAvailable: Boolean = false,
    /** T73: manual on/off from the hub. */
    onToggleFocus: () -> Unit = {},
) {
    val stats by source.stats.collectAsState()
    val history by source.history.collectAsState()
    val screenOn = rememberScreenOn()
    val visible = statsModuleVisible(eligible, stats)
    // Clock and page controls remain usable while the first sensor snapshot is loading.
    val active = statsActive(resumed, screenOn, eligible)
    LaunchedEffect(active) { source.setActive(active) }
    DisposableEffect(Unit) { onDispose { source.setActive(false) } }

    if (!visible) return
    val presentation = remember(stats) { StatsPresentation.of(stats) }
    val context = LocalContext.current
    val hubStore = remember(context) { HubConfigStore(context) }
    val config by hubStore.config.collectAsState(initial = HubConfig())
    val top = hubTopDp
    // T258: the card is as tall as the page on show wants it to be.
    val savedPage by hubStore.lastPage.collectAsState(initial = TodayPage.Id)
    val shownSize = config.sizeFor(restoredHubPage(savedPage, config))
    val panelDp by androidx.compose.animation.core.animateFloatAsState(
        HubPanelLayout.heightDp(shownSize, com.brightblu.parc.ring.RingHudBounds.heightDp(top, hubBottomDp)),
        androidx.compose.animation.core.spring(com.brightblu.parc.model.Springs.sheet.damping,
            com.brightblu.parc.model.Springs.sheet.stiffness), label = "hubPanelHeight")
    StatsHud(
        powerSource = powerSource,
        networkRepository = networkRepository,
        dayLedger = dayLedger, newApps = newApps, appStorage = appStorage,
        volume = volume, comms = comms, entries = entries, onLaunch = onLaunch,
        active = active,
        alertState = alertState, onDismissAlert = onDismissAlert, archive = archive,
        presentation = presentation,
        history = history,
        skin = skin,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        focusActiveName = focusActiveName,
        focusAvailable = focusAvailable,
        onToggleFocus = onToggleFocus,
        fullScreen = fullScreen,
        fullScreenEnabled = fullScreenEnabled,
        onExpandFullScreen = onExpandFullScreen,
        onCloseFullScreen = onCloseFullScreen,
        modifier = (if (fullScreen) Modifier
            .align(Alignment.TopStart)
            .fillMaxSize()
        else Modifier
            .align(Alignment.TopStart)
            .padding(start = Metrics.panelGutterDp.dp, end = Metrics.panelGutterDp.dp, top = top.dp)
            .height(panelDp.dp))
            .clipToBounds()
            .graphicsLayer { alpha = 1f - (1f - Metrics.searchPullMinHubAlpha) * pullProgress() },
    )
}

@Composable
internal fun StatsHud(
    powerSource: com.brightblu.parc.core.data.power.PowerStatsSource?,
    networkRepository: com.brightblu.parc.core.data.network.NetworkStatsRepository?,
    dayLedger: com.brightblu.parc.core.data.ledger.DayLedgerRepository?,
    newApps: com.brightblu.parc.core.data.apps.NewAppsProvider?,
    appStorage: com.brightblu.parc.core.data.storage.AppStorageRepository?,
    volume: com.brightblu.parc.core.data.audio.VolumeRepository?,
    comms: com.brightblu.parc.core.data.comms.CommsExtrasRepository?,
    entries: List<com.brightblu.parc.model.AppEntry>,
    onLaunch: (com.brightblu.parc.model.AppId) -> Unit,
    active: Boolean,
    alertState: StatsAlertUiState,
    onDismissAlert: (StatsAlertMetric) -> Unit,
    archive: StatsArchive,
    presentation: StatsPresentation,
    history: StatsHistory,
    skin: Skin,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    focusActiveName: String? = null,
    focusAvailable: Boolean = false,
    onToggleFocus: () -> Unit = {},
    fullScreen: Boolean = false,
    fullScreenEnabled: Boolean = true,
    onExpandFullScreen: () -> Unit = {},
    onCloseFullScreen: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // T158: the hub is always the card (DESIGN 3.4): 32 dp corners, 16 dp inside on every side. T176: drawn like the arc card.
    // T330: the full-screen view takes the whole screen with square corners; a page gutter holds the text.
    val shape: androidx.compose.ui.graphics.Shape =
        if (fullScreen) androidx.compose.ui.graphics.RectangleShape
        else androidx.compose.foundation.shape.RoundedCornerShape(skin.shape.moduleCornerDp.dp)
    val surfaceModifier = remember(skin) { Modifier.hubSurface(skin) }
    val inset = if (fullScreen) ParcSpacing.hubFullScreen() else ParcSpacing.hubCard()
    val panel = modifier
        // T330: the arcs stay behind; the full-screen page is opaque even when the card is set see-through.
        .then(if (fullScreen) Modifier.background(Color(skin.colors.bg.toInt())) else Modifier)
        .then(surfaceModifier).then(Modifier.clip(shape))
        .then(if (fullScreen) Modifier.windowInsetsPadding(WindowInsets.systemBars) else Modifier)
        .padding(start = inset.startDp.dp, top = inset.topDp.dp, end = inset.endDp.dp, bottom = inset.bottomDp.dp)
    // T121: the demo store resolves under T120a/T120b's namespace because this context is the
    // application context (ParcApplication delegates storage to the wrapped base). Never built
    // when demo is off.
    val appContext = LocalContext.current.applicationContext
    val demoStore = remember(appContext) {
        if (DemoMode.enabled) DemoStatsStore(File(appContext.filesDir, DemoStatsStore.FileName)) else null
    }
    Column(panel) {
        HubPages(skin, expanded = expanded, onToggleExpanded = onToggleExpanded,
            fullScreen = fullScreen,
            fullScreenEnabled = fullScreenEnabled,
            onExpandFullScreen = onExpandFullScreen,
            onCloseFullScreen = onCloseFullScreen,
            focusLabel = focusActiveName,
            focusAvailable = focusAvailable,
            onToggleFocus = onToggleFocus,
            demoContent = { page ->
                when (page) {
                    DevicePage.Id -> DemoDevicePage(demoStore, skin)
                    ControlsPage.Id -> DemoControlsPage(skin)
                    CommonplacePage.Id -> DemoCommonplacePage(skin)
                    "apps" -> DemoAppsPage(skin)
                    "notes" -> DemoNotesPage(skin)
                    "comms" -> DemoCommsPage(skin)
                    "focus" -> FocusHubPage(skin)
                    WeatherVariant.PageId -> WeatherVariant.DemoPage(skin)
                    StepsPage.Id -> DemoStepsPage(skin)
                    RhythmPage.Id -> DemoRhythmPage(skin)
                    SkyPage.Id -> DemoSkyPage(skin)
                    YearPage.Id -> DemoYearPage(skin)
                    RecordsPage.Id -> DemoRecordsPage(skin)
                    TodayPage.Id -> DemoTodayPage(skin)
                    LibraryPage.Id -> DemoLibraryPage(skin)
                    com.brightblu.parc.arcade.ArcadePage.Id -> com.brightblu.parc.arcade.ArcadeHubPage(skin, active)
                    else -> extraPage(page)?.demo?.invoke(skin) ?: Unit
                }
            },
            comms = { if (comms != null) CommsPage(comms, skin) },
            pageContent = { page, tiles ->
                when (page) {
                    DevicePage.Id -> DeviceHubPage(powerSource, networkRepository, dayLedger, entries, active, tiles, skin,
                        alertState.active, archive, presentation, history, alertState.banner, onDismissAlert)
                    ControlsPage.Id -> ControlsHubPage(volume, active, tiles, skin)
                    CommonplacePage.Id -> CommonplaceHubPage(skin)
                    "apps" -> AppsHubPage(dayLedger, newApps, appStorage, entries, active, tiles, expanded, skin, onLaunch)
                    "timers" -> TimersHubPage(active, tiles, expanded, skin)
                    "agenda" -> AgendaHubPage(active, tiles, expanded, skin)
                    "media" -> MediaHubPage(active, tiles, expanded, skin)
                    "focus" -> FocusHubPage(skin)
                    WeatherVariant.PageId -> WeatherVariant.Page(skin, active, LocalHubOpenOptions.current)
                    StepsPage.Id -> StepsHubPage(dayLedger, skin, active)
                    RhythmPage.Id -> RhythmHubPage(dayLedger, skin, active)
                    SkyPage.Id -> SkyHubPage(skin, active)
                    YearPage.Id -> YearHubPage(dayLedger, skin, active)
                    RecordsPage.Id -> RecordsHubPage(dayLedger, skin, active)
                    TodayPage.Id -> TodayHubPage(dayLedger, skin, active, focusActiveName)
                    LibraryPage.Id -> LibraryHubPage(skin, active)
                    com.brightblu.parc.arcade.ArcadePage.Id -> com.brightblu.parc.arcade.ArcadeHubPage(skin, active)
                    else -> extraPage(page)?.body?.invoke(skin, active) ?: CellLabel("${page.replaceFirstChar { it.uppercase() }} · coming soon", skin)
                }
            })
        }
    }

// --- Frames --------------------------------------------------------------------------------

@Composable
internal fun HudCell(skin: Skin, modifier: Modifier = Modifier, compact: Boolean = false, warning: Boolean = false,
    narrow: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    HudFrame(skin, modifier.heightIn(min = Metrics.hudCellHeightDp.dp), narrow) {
        content()
        if (warning) BasicText("!", Modifier.align(Alignment.TopEnd), style = hudValueStyle(skin, skin.colors.warn))
    }
}

/** A corner-bracketed frame with a faint surface tint. Brackets use the HUD skin tokens. */
@Composable
private fun HudFrame(skin: Skin, modifier: Modifier = Modifier, narrow: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.hudFrame(skin).padding(horizontal = ParcSpacing.cellHorizontalInsetDp(narrow).dp, vertical = ParcSpacing.cellVerticalInsetDp(narrow).dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

private fun Modifier.hudFrame(skin: Skin): Modifier = drawWithCache {
    val stroke = skin.hud.bracketWidthDp.dp.toPx()
    val bracket = skin.hud.bracketLengthDp.dp.toPx()
    val w = size.width; val h = size.height
    val path = androidx.compose.ui.graphics.Path()
    if (skin.style.terminalStats && (skin.style.statsFrame == StatsFrame.legend || skin.style.statsFrame == StatsFrame.terminal)) {
        path.moveTo(stroke, stroke); path.lineTo(w - stroke, stroke)
        path.lineTo(w - stroke, h - stroke); path.lineTo(stroke, h - stroke); path.close()
    } else if (skin.style.terminalStats) {
        path.moveTo(0f, bracket); path.lineTo(0f, 0f); path.lineTo(bracket, 0f)
        path.moveTo(w - bracket, 0f); path.lineTo(w, 0f); path.lineTo(w, bracket)
        path.moveTo(w, bracket); path.lineTo(w, h); path.lineTo(w - bracket, h)
        path.moveTo(bracket, h); path.lineTo(0f, h); path.lineTo(0f, h - bracket)
    }
    val frame = Color(skin.colors.accent.toInt()).copy(alpha = 0.85f)
    val surface = Color(skin.colors.surface.toInt()).copy(alpha =
        if (skin.style.statsFrame == StatsFrame.legend || skin.style.statsFrame == StatsFrame.terminal) skin.shape.surfaceAlpha else skin.shape.surfaceAlpha * 0.55f)
    val radius = if (skin.style.terminalStats) CornerRadius.Zero else CornerRadius(skin.shape.surfaceCornerDp.dp.toPx(), skin.shape.surfaceCornerDp.dp.toPx())
    val softBorder = Color(skin.colors.lineBase.toInt())
    val outline = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Square)
    onDrawBehind {
        drawRoundRect(color = surface, cornerRadius = radius)
        if (skin.style.terminalStats) drawPath(path, frame, style = outline)
        else drawRoundRect(softBorder, cornerRadius = radius, style = outline)
    }
}

// --- Cells ---------------------------------------------------------------------------------

@Composable
private fun BatteryCell(p: StatsPresentation, skin: Skin) {
    Column(Modifier.fillMaxWidth()) {
        CellLabel("Battery", skin)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(StatsFormat.percent(p.batteryPercent), style = hudValueStyle(skin))
            Spacer(Modifier.width(Metrics.gridDp.dp))
            Box(Modifier.weight(1f)) { SegmentedBatteryBar(batterySegments(p.batteryPercent), skin) }
            if (p.charging) {
                Spacer(Modifier.width(Metrics.gridDp.dp))
                BoltGlyph(Color(skin.colors.accent.toInt()), skin.typography.caption.sizeSp.dp)
            }
            if (p.plug != null) {
                Spacer(Modifier.width(Metrics.gridDp.dp))
                BasicText(p.plug, style = hudAccentStyle(skin))
            }
        }
    }
}

@Composable
private fun CollapsedBattery(p: StatsPresentation, skin: Skin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(StatsFormat.percent(p.batteryPercent), style = hudValueStyle(skin))
        Spacer(Modifier.width(Metrics.gridDp.dp))
        Box(Modifier.width(32.dp)) { SegmentedBatteryBar(batterySegments(p.batteryPercent), skin) }
        if (p.charging) {
            Spacer(Modifier.width(Metrics.gridDp.dp))
            BoltGlyph(Color(skin.colors.accent.toInt()), skin.typography.caption.sizeSp.dp)
        }
        if (p.plug != null) {
            Spacer(Modifier.width(Metrics.gridDp.dp))
            BasicText(p.plug, style = hudAccentStyle(skin))
        }
    }
}

@Composable
private fun SegmentedBatteryBar(segments: List<Boolean>?, skin: Skin, accent: Long = skin.colors.accent) {
    Canvas(Modifier.fillMaxWidth().height(Metrics.hudBarHeightDp.dp)) {
        val list = segments ?: return@Canvas
        val gap = skin.hud.segmentGapDp.dp.toPx()
        val count = list.size
        val segW = (size.width - gap * (count - 1)) / count
        val radius = CornerRadius((Metrics.gridDp / 4).dp.toPx(), (Metrics.gridDp / 4).dp.toPx())
        for (i in list.indices) {
            val filled = list[i]
            val x = i * (segW + gap)
            drawRoundRect(
                color = if (filled) Color(accent.toInt()) else Color(skin.colors.lineBase.toInt()),
                topLeft = Offset(x, 0f),
                size = Size(segW, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** T377: the battery as a ring with the percentage printed inside, so the ring and the number agree. */
@Composable
internal fun BatteryRing(percent: Int?, skin: Skin) {
    val track = Color(skin.colors.lineBase.toInt())
    val on = Color((if (PowerPage.isLow(percent)) skin.colors.accent else skin.colors.textPrimary).toInt())
    val fraction = (percent?.coerceIn(0, 100) ?: 0) / 100f
    Box(Modifier.size(Metrics.hudBatteryRingDp.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Metrics.hudBatteryRingStrokeDp.dp.toPx()
            val inset = stroke / 2f
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
            if (fraction > 0f) drawArc(on, -90f, 360f * fraction, false, Offset(inset, inset), box,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        BasicText(StatsFormat.percent(percent), style = hudValueStyle(skin).copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

@Composable
private fun TemperatureText(t: TempGauge, skin: Skin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("${t.label} ", style = hudLabelStyle(skin))
        BasicText(StatsFormat.temperature(t.celsius), style = hudValueStyle(skin, temperatureColor(t.celsius, skin)))
    }
}

@Composable
internal fun ThermalCell(label: String, skin: Skin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("Thermal", style = hudLabelStyle(skin))
        Spacer(Modifier.width(Metrics.gridDp.dp))
        BasicText(label, style = hudAccentStyle(skin))
    }
}

@Composable
private fun TemperatureCell(gauges: List<TempGauge>, skin: Skin) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp),
        verticalAlignment = Alignment.Top,
    ) {
        gauges.forEach { gauge -> ArcGauge(gauge, skin, Modifier.weight(1f)) }
    }
}

@Composable
private fun ArcGauge(gauge: TempGauge, skin: Skin, modifier: Modifier = Modifier) {
    val color = Color(temperatureColor(gauge.celsius, skin).toInt())
    val fraction = temperatureFraction(gauge.celsius)
    val density = LocalDensity.current
    val strokeStyle = remember(density) { Stroke(with(density) { Metrics.hudGaugeStrokeDp.dp.toPx() }, cap = StrokeCap.Round) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(Metrics.hudGaugeDp.dp)) {
            val stroke = Metrics.hudGaugeStrokeDp.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = Color(skin.colors.lineBase.toInt()),
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = strokeStyle,
            )
            if (fraction > 0f) {
                drawArc(
                    color = color,
                    startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = strokeStyle,
                )
            }
        }
        BasicText(gauge.label, style = hudLabelStyle(skin))
        BasicText(StatsFormat.temperature(gauge.celsius), style = hudValueStyle(skin, temperatureColor(gauge.celsius, skin)))
    }
}

@Composable
private fun NetworkReadout(p: StatsPresentation, skin: Skin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("\u2193 ", style = hudAccentStyle(skin))
        BasicText(p.networkDown, style = hudValueStyle(skin))
        Spacer(Modifier.width(Metrics.gridDp.dp))
        BasicText("\u2191 ", style = hudAccentStyle(skin))
        BasicText(p.networkUp, style = hudValueStyle(skin))
    }
}

@Composable
internal fun NetworkCell(p: StatsPresentation, history: StatsHistory, skin: Skin) {
    Column(Modifier.fillMaxWidth()) {
        if (LocalHubPrimary.current) {
            HubHero(p.networkDown, "Download now", skin)
            CellLabel("Upload ${p.networkUp}", skin)
        } else NetworkReadout(p, skin)
        Spacer(Modifier.height(Metrics.gridDp.dp))
        Sparkline(history.networkRxBytesPerSecond, history.networkTxBytesPerSecond, skin)
    }
}

@Composable
private fun Sparkline(rx: List<Long>, tx: List<Long>, skin: Skin) {
    val max = remember(rx, tx) { maxOf(rx.maxOrNull() ?: 0L, tx.maxOrNull() ?: 0L, 1L).toFloat() }
    val down = remember(rx) { FloatArray(rx.size) { rx[it].toFloat() } }
    val up = remember(tx) { FloatArray(tx.size) { tx[it].toFloat() } }
    Canvas(Modifier.fillMaxWidth().height(Metrics.hudSparklineHeightDp.dp)) {
        if (down.isEmpty() && up.isEmpty()) return@Canvas
        val stroke = (Metrics.hudGaugeStrokeDp * 0.6f).dp.toPx()
        drawSeries(down, max, Color(skin.colors.accent.toInt()), stroke)
        drawSeries(up, max, Color(skin.colors.accent2.toInt()), stroke)
    }
}

/** Round caps reach half a stroke past the ends of a line, so the series starts that far inside the canvas (T166). */
private fun DrawScope.drawSeries(values: FloatArray, max: Float, color: Color, stroke: Float) {
    if (values.size < 2) return
    val inset = stroke / 2f
    val step = (size.width - 2 * inset) / (values.size - 1)
    val h = size.height - 2 * inset
    for (i in 0 until values.size - 1) {
        val x0 = inset + i * step
        val y0 = inset + h - (values[i].toFloat() / max * h).coerceIn(0f, h)
        val x1 = inset + (i + 1) * step
        val y1 = inset + h - (values[i + 1].toFloat() / max * h).coerceIn(0f, h)
        drawLine(color, Offset(x0, y0), Offset(x1, y1), stroke, StrokeCap.Round)
    }
}

@Composable
internal fun StorageCell(p: StatsPresentation, skin: Skin) {
    val percent = p.storagePercent ?: return
    val text = p.storageText ?: return
    val accent = storageColor(percent, skin)
    val segments = remember(percent) { batterySegments(percent) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
        Column(Modifier.fillMaxWidth()) {
            CellLabel("Storage", skin)
            BasicText("$percent%  $text", style = hudValueStyle(skin, accent))
        }
        SegmentedBatteryBar(segments, skin, accent)
    }
}

@Composable
internal fun MemoryCell(p: StatsPresentation, skin: Skin) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
        if (p.ramPercent != null) LabeledBar("RAM", p.ramPercent, skin)
    }
}

@Composable
private fun LabeledBar(label: String, percent: Int, skin: Skin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = hudLabelStyle(skin), modifier = Modifier.width((Metrics.gridDp * 7.5f).dp))
        Spacer(Modifier.width(Metrics.gridDp.dp))
        Canvas(Modifier.weight(1f).height(Metrics.hudBarHeightDp.dp)) {
            drawRoundRect(
                color = Color(skin.colors.lineBase.toInt()),
                cornerRadius = CornerRadius((Metrics.gridDp / 4).dp.toPx(), (Metrics.gridDp / 4).dp.toPx()),
            )
            val f = percent.coerceIn(0, 100) / 100f
            if (f > 0f) {
                drawRoundRect(
                    color = Color(skin.colors.accent.toInt()),
                    topLeft = Offset.Zero,
                    size = Size(size.width * f, size.height),
                    cornerRadius = CornerRadius((Metrics.gridDp / 4).dp.toPx(), (Metrics.gridDp / 4).dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(Metrics.gridDp.dp))
        BasicText(StatsFormat.percent(percent), style = hudValueStyle(skin))
    }
}

@Composable
internal fun WifiCell(p: StatsPresentation, skin: Skin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("Wi-Fi", style = hudLabelStyle(skin))
        Spacer(Modifier.width(Metrics.gridDp.dp))
        if (p.wifiBars != null) WifiBars(p.wifiBars, skin)
        if (p.wifiLinkSpeed != null) {
            Spacer(Modifier.width(Metrics.gridDp.dp))
            BasicText(p.wifiLinkSpeed, style = hudValueStyle(skin))
        }
    }
}

@Composable
private fun WifiBars(bars: Int, skin: Skin) {
    Canvas(Modifier.width((Metrics.gridDp * 5).dp).height((Metrics.gridDp * 3.5f).dp)) {
        val total = Metrics.hudWifiBars
        val gap = (Metrics.gridDp / 4).dp.toPx()
        val barW = (size.width - gap * (total - 1)) / total
        for (i in 0 until total) {
            val filled = i < bars
            val barH = size.height * (i + 1) / total
            val x = i * (barW + gap)
            val y = size.height - barH
            drawRect(
                color = if (filled) Color(skin.colors.accent.toInt()) else Color(skin.colors.lineBase.toInt()),
                topLeft = Offset(x, y),
                size = Size(barW, barH),
            )
        }
    }
}

@Composable
internal fun UptimeCell(uptime: String, skin: Skin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("Up", style = hudLabelStyle(skin))
        Spacer(Modifier.width(Metrics.gridDp.dp))
        BasicText(uptime, style = hudValueStyle(skin))
    }
}

@Composable
internal fun CellLabel(label: String, skin: Skin) {
    BasicText(label, style = hudLabelStyle(skin))
}

/** A small solid lightning bolt in the accent colour, avoiding emoji presentation. */
@Composable
private fun BoltGlyph(color: Color, sizeDp: Dp) {
    Box(Modifier.size(sizeDp).drawWithCache {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.55f, 0f)
            lineTo(w * 0.10f, h * 0.62f)
            lineTo(w * 0.42f, h * 0.62f)
            lineTo(w * 0.25f, h)
            lineTo(w * 0.95f, h * 0.35f)
            lineTo(w * 0.60f, h * 0.35f)
            close()
        }
        onDrawBehind { drawPath(path, color) }
    })
}

internal fun hudLabelStyle(skin: Skin): TextStyle = TextStyle(
    color = Color(skin.colors.textSecondary.toInt()),
    fontSize = skin.typography.caption.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.caption.weight),
    letterSpacing = skin.typography.caption.trackingSp.sp,
    fontFamily = skin.textFamily,
)

internal fun hudValueStyle(
    skin: Skin,
    color: Long? = null,
    role: TypeToken = skin.typography.body,
): TextStyle = TextStyle(
    color = Color((color ?: skin.colors.textPrimary).toInt()),
    fontSize = role.sizeSp.sp,
    fontWeight = FontWeight(role.weight),
    letterSpacing = role.trackingSp.sp,
    fontFamily = skin.textFamily,
    fontFeatureSettings = "tnum",
)

private fun hudAccentStyle(skin: Skin): TextStyle = hudValueStyle(skin, skin.colors.accent)

/** True while the screen is on, tracked from the system screen on/off broadcast. */
@Composable
private fun rememberScreenOn(): Boolean {
    val context = LocalContext.current
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    var screenOn by remember { mutableStateOf(powerManager.isInteractive) }
    DisposableEffect(context, powerManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                screenOn = when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> true
                    Intent.ACTION_SCREEN_OFF -> false
                    else -> powerManager.isInteractive
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return screenOn
}
