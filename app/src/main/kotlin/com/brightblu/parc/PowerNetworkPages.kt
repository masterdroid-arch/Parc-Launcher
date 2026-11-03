package com.brightblu.parc

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.style.TextAlign
import com.brightblu.parc.core.data.stats.StatsArchiveMetric
import java.time.ZoneId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.power.*
import com.brightblu.parc.core.data.network.*
import com.brightblu.parc.core.data.stats.StatsFormat
import com.brightblu.parc.core.data.stats.StatsHistory
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun PowerHubPage(source: PowerStatsSource?, active: Boolean, tiles: List<String>, expanded: Boolean, skin: Skin,
    alerts: Set<com.brightblu.parc.core.data.stats.StatsAlertMetric> = emptySet(),
    archive: com.brightblu.parc.core.data.stats.StatsArchive = com.brightblu.parc.core.data.stats.StatsArchive.Empty,
    batteryPercent: Int? = null) {
    DisposableEffect(source, active) {
        source?.setActive(active)
        onDispose { source?.setActive(false) }
    }
    val stats = source?.stats?.collectAsState()?.value ?: PowerStats.Empty
    // T159: the overview replaces the "charging", "screen on today" and "time to full" tiles; the other tiles follow it.
    val charging = stats.chargingPower?.direction == PowerDirection.Charging
    val bars = remember(archive) { PowerPage.hourBars(archive.series(StatsArchiveMetric.BatteryPercent), System.currentTimeMillis(), ZoneId.systemDefault()) }
    val rest = remember(tiles) { tiles.filterNot { it in PowerOverviewTiles } }
    PageTiles(rest, expanded, skin, alerts, primaryFirst = false, emptyHint = false, leading = {
        PowerOverview(batteryPercent, charging, stats.chargeTimeRemainingMillis, stats.screenOnTodayMillis, bars, skin)
    }) { tile ->
        val warning = alertForTile(tile, alerts) != null
        val tileSkin = remember(skin, warning) { if (warning) skin.copy(colors = skin.colors.copy(accent = skin.colors.warn)) else skin }
        when (tile) {
            in HubCatalog.powerHistoryTiles -> StatsArchiveGraph(tile, HubCatalog.powerHistoryTiles.getValue(tile), archive, tileSkin)
            "charging" -> Column {
                HubHero(StatsFormat.percent(batteryPercent), "Battery · ${PowerNetworkFormat.watts(stats.chargingPower)}", tileSkin)
                if (stats.chargeTimeRemainingMillis != null) CellLabel("Full in ${PowerNetworkFormat.duration(stats.chargeTimeRemainingMillis)}", tileSkin)
            }
            "time to full" -> Readout("Time to full", PowerNetworkFormat.duration(stats.chargeTimeRemainingMillis), tileSkin)
            "health and cycles" -> Readout("Battery health / cycles", "${PowerNetworkFormat.health(stats.health)} · ${stats.cycleCount ?: "—"} cycles", tileSkin)
            "battery temperature" -> Column {
                Readout("Battery temperature · last 5 min", StatsFormat.temperature(stats.temperatureHistoryC.lastOrNull()), tileSkin)
                TemperatureHistory(stats.temperatureHistoryC, tileSkin)
            }
            "screen on today" -> Column {
                Readout("Screen on today", PowerNetworkFormat.duration(stats.screenOnTodayMillis), tileSkin)
                if (stats.screenOnTodayMillis == null) UsageAccessRow(skin)
            }
        }
    }
}

@Composable
internal fun NetworkHubPage(repository: NetworkStatsRepository?, active: Boolean, tiles: List<String>, expanded: Boolean,
    presentation: StatsPresentation, history: StatsHistory, skin: Skin) {
    var snapshot by remember(repository) { mutableStateOf<NetworkUsageStats?>(null) }
    LaunchedEffect(repository, active) {
        if (active && repository != null) while (isActive) {
            snapshot = withContext(Dispatchers.IO) { repository.read() }
            delay(Metrics.hubNetworkRefreshMillis)
        }
    }
    val today = snapshot?.today as? TodayDataUsage.Available
    PageTiles(tiles, expanded, skin) { tile ->
        when (tile) {
            "traffic" -> Column {
                CellLabel("Live traffic · ↓ down / ↑ up", skin)
                NetworkCell(presentation, history, skin)
            }
            "today totals" -> Column {
                Readout("Today · Wi-Fi / mobile", "${PowerNetworkFormat.bytes(today?.wifi?.totalBytes)} / ${PowerNetworkFormat.bytes(today?.mobile?.totalBytes)}", skin)
                if (snapshot?.today == TodayDataUsage.Unavailable) UsageAccessRow(skin)
            }
            "top apps" -> Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                CellLabel("Top apps · data today", skin)
                val apps = today?.topApps
                if (apps == null) {
                    CellLabel(if (snapshot == null) "Loading…" else "—", skin)
                    if (snapshot?.today == TodayDataUsage.Unavailable) UsageAccessRow(skin)
                } else if (apps.isEmpty()) CellLabel("No app data today", skin)
                else {
                    val max = apps.maxOf { it.totalBytes }.coerceAtLeast(1L)
                    apps.take(NetworkStatsRepository.TopCount).forEach { app ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                            BasicText(app.label, Modifier.weight(1f), style = hudValueStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText(PowerNetworkFormat.bytes(app.totalBytes), style = hudLabelStyle(skin))
                        }
                        DataBar((app.totalBytes.toDouble() / max).toFloat(), skin)
                    }
                }
            }
            "wifi link" -> Readout("Wi-Fi · speed / signal", "${snapshot?.link?.wifiLinkSpeedMbps?.let { "$it Mbps" } ?: "—"} / ${snapshot?.link?.wifiRssiDbm?.let { "$it dBm" } ?: "—"}", skin)
            "network type" -> Readout("Mobile network type", PowerNetworkFormat.network(snapshot?.link?.mobileNetworkType), skin)
        }
    }
}

@Composable
internal fun PageTiles(tiles: List<String>, expanded: Boolean, skin: Skin,
    alerts: Set<com.brightblu.parc.core.data.stats.StatsAlertMetric> = emptySet(),
    centreSingleTile: Boolean = false, primaryFirst: Boolean = true, emptyHint: Boolean = true, leading: @Composable () -> Unit = {}, content: @Composable (String) -> Unit) {
    val height = if (centreSingleTile) Metrics.hudCellHeightDp else LocalHubTileHeight.current
    androidx.compose.runtime.CompositionLocalProvider(LocalHubTileHeight provides height) {
        Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
            leading()
            // T83: the hub scrolls inside its arc bound; a collapsed two-tile list is not a mode.
            tiles.forEachIndexed { index, tile ->
                val warning = alertForTile(tile, alerts) != null
                val tileSkin = remember(skin, warning) { if (warning) skin.copy(colors = skin.colors.copy(accent = skin.colors.warn)) else skin }
                CompositionLocalProvider(LocalHubPrimary provides (primaryFirst && index == 0)) {
                    HudCell(tileSkin, Modifier.fillMaxWidth(), warning = warning) { content(tile) }
                }
            }
            if (emptyHint && tiles.isEmpty()) Box(Modifier.fillMaxWidth().height(LocalHubContentHeight.current.dp), contentAlignment = Alignment.Center) { CellLabel("No tiles shown · use Edit", skin) }
        }
    }
}

@Composable
internal fun Readout(label: String, value: String, skin: Skin) {
    if (LocalHubPrimary.current) HubHero(value, label, skin)
    else Column { BasicText(value, style = hudValueStyle(skin)); CellLabel(label, skin) }
}

@Composable
internal fun UsageAccessRow(skin: Skin) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp).clickable {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
    }) { BasicText("Grant usage access", style = hudValueStyle(skin, skin.colors.accent)) }
}

@Composable
internal fun DataBar(fraction: Float, skin: Skin) {
    val base = Color(skin.colors.lineBase.toInt())
    val accent = Color(skin.colors.accent.toInt())
    Canvas(Modifier.fillMaxWidth().height(Metrics.hudBarHeightDp.dp)) {
        drawRect(base)
        drawRect(accent, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
    }
}

@Composable
private fun TemperatureHistory(values: List<Float>, skin: Skin) {
    // Compute and unbox outside the draw callback. Flat and single-sample histories stay visible.
    val points = remember(values) { values.filter { it.isFinite() }.toFloatArray() }
    val low = remember(points) { (points.minOrNull() ?: 0f) - 1f }
    val high = remember(points) { (points.maxOrNull() ?: 0f) + 1f }
    val color = Color(skin.colors.accent.toInt())
    Canvas(Modifier.fillMaxWidth().height(Metrics.hudSparklineHeightDp.dp)) {
        val stroke = Metrics.hudGaugeStrokeDp.dp.toPx()
        if (points.size == 1) drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
        for (i in 1 until points.size) {
            drawLine(color,
                Offset(size.width * (i - 1) / (points.size - 1), size.height * (1 - (points[i - 1] - low) / (high - low))),
                Offset(size.width * i / (points.size - 1), size.height * (1 - (points[i] - low) / (high - low))), stroke)
        }
    }
}

/** Tiles the T159 overview already shows; hiding or reordering them in the page options changes nothing. */
private val PowerOverviewTiles = setOf("charging", "screen on today", "time to full")

/**
 * T159, DESIGN S22: big battery number, 20-dot meter, two pebbles (screen on, charging) and the last 8 hours.
 * Stateless: every value comes in, so the demo page and the real page draw the same thing. Compact shows the
 * number and the meter only.
 */
@Composable
internal fun PowerOverview(percent: Int?, charging: Boolean, fullInMillis: Long?, screenOnMillis: Long?,
    bars: List<HourBar>, skin: Skin) {
    val size = LocalHubSize.current
    val typography = skin.typography
    val ink = Color(skin.colors.textPrimary.toInt())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        // The battery number scales with the system font up to 130 %, like the other readable numerals (DESIGN 2.2).
        val density = LocalDensity.current
        val capped = remember(density) { Density(density.density, density.fontScale.coerceAtMost(PowerPage.MaxFontScale)) }
        CompositionLocalProvider(LocalDensity provides capped) {
            DotNumerals(if (percent == null) "--%" else "$percent%", DotRole.bigDot, typography, ink)
        }
        DotMeter(percent, skin)
        if (size == HubSize.Compact) return@Column
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
            HudCell(skin, Modifier.weight(1f).fillMaxHeight()) { ScreenOnPebble(screenOnMillis, skin) }
            HudCell(skin, Modifier.weight(1f).fillMaxHeight()) {
                Column {
                    BasicText(PowerPage.chargingState(charging), style = hudValueStyle(skin))
                    PowerPage.fullIn(charging, fullInMillis)?.let { CellLabel(it, skin) }
                }
            }
        }
        HudCell(skin, Modifier.fillMaxWidth()) { HourBars(bars, skin) }
    }
}

@Composable
private fun ScreenOnPebble(millis: Long?, skin: Skin) {
    val context = LocalContext.current
    val parts = PowerPage.hoursMinutes(millis)
    Column {
        CellLabel("Screen on", skin)
        if (parts == null) {
            DotNumerals("--", DotRole.countDot, skin.typography, Color(skin.colors.textPrimary.toInt()))
            BasicText("Grant usage access", Modifier.clickable {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
            }, style = hudValueStyle(skin, skin.colors.accent))
        } else {
            val ink = Color(skin.colors.textPrimary.toInt())
            Row {
                DotNumerals("${parts.first}", DotRole.countDot, skin.typography, ink, Modifier.alignByBaseline())
                BasicText("h ", Modifier.alignByBaseline(), style = hudLabelStyle(skin))
                DotNumerals("${parts.second}", DotRole.countDot, skin.typography, ink, Modifier.alignByBaseline())
                BasicText("m", Modifier.alignByBaseline(), style = hudLabelStyle(skin))
            }
        }
    }
}

/** 20 dots: filled in `textPrimary` (accent at 15 % or below), empty in `lineBase`. */
@Composable
private fun DotMeter(percent: Int?, skin: Skin) {
    val filled = PowerPage.filledDots(percent)
    val on = Color((if (PowerPage.isLow(percent)) skin.colors.accent else skin.colors.textPrimary).toInt())
    val off = Color(skin.colors.lineBase.toInt())
    Canvas(Modifier.fillMaxWidth().height(PowerPage.MeterHeightDp.dp)) {
        val pitch = size.width / PowerPage.MeterDots
        val radius = minOf(pitch * PowerPage.DotFill / 2f, size.height / 2f)
        for (i in 0 until PowerPage.MeterDots) drawCircle(if (i < filled) on else off, radius, Offset(pitch * (i + 0.5f), size.height / 2f))
    }
}

/** Eight bars, one per hour; the current hour is in the accent and labelled "now". Gaps show only the track. */
@Composable
private fun HourBars(bars: List<HourBar>, skin: Skin) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
        CellLabel("Last 8 hours", skin)
        if (!PowerPage.hasData(bars)) {
            BasicText(PowerPage.EmptyHistory, style = hudValueStyle(skin))
            return@Column
        }
        val track = Color(skin.colors.lineBase.toInt())
        val level = Color(skin.colors.textSecondary.toInt())
        val now = Color(skin.colors.accent.toInt())
        Canvas(Modifier.fillMaxWidth().height(PowerPage.BarsHeightDp.dp)) {
            val slot = size.width / bars.size
            val width = slot * PowerPage.BarFill
            val corner = CornerRadius(width / 2f, width / 2f)
            bars.forEachIndexed { i, bar ->
                val left = slot * i + (slot - width) / 2f
                drawRoundRect(track, Offset(left, 0f), Size(width, size.height), corner)
                val h = size.height * PowerPage.barFraction(bar)
                if (bar.percent != null) drawRoundRect(if (bar.current) now else level, Offset(left, size.height - h), Size(width, h), corner)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { bar ->
                BasicText(PowerPage.hourLabel(bar), Modifier.weight(1f), style = hudLabelStyle(if (bar.current) skin.copy(colors = skin.colors.copy(textSecondary = skin.colors.accent)) else skin).copy(textAlign = TextAlign.Center), maxLines = 1)
            }
        }
    }
}
