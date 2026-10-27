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
    PageTiles(tiles, expanded, skin, alerts) { tile ->
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
    centreSingleTile: Boolean = false, leading: @Composable () -> Unit = {}, content: @Composable (String) -> Unit) {
    val height = if (centreSingleTile) Metrics.hudCellHeightDp else LocalHubTileHeight.current
    androidx.compose.runtime.CompositionLocalProvider(LocalHubTileHeight provides height) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberHubScrollState()), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
            leading()
            // T83: the hub scrolls inside its arc bound; a collapsed two-tile list is not a mode.
            tiles.forEachIndexed { index, tile ->
                val warning = alertForTile(tile, alerts) != null
                val tileSkin = remember(skin, warning) { if (warning) skin.copy(colors = skin.colors.copy(accent = skin.colors.warn)) else skin }
                CompositionLocalProvider(LocalHubPrimary provides (index == 0)) {
                    HudCell(tileSkin, Modifier.fillMaxWidth(), warning = warning) { content(tile) }
                }
            }
            if (tiles.isEmpty()) Box(Modifier.fillMaxWidth().height(LocalHubContentHeight.current.dp), contentAlignment = Alignment.Center) { CellLabel("No tiles shown · use Edit", skin) }
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
