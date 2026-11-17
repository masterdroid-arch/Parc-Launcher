package com.brightblu.parc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.ledger.DayLedgerRepository
import com.brightblu.parc.core.data.network.NetworkStatsRepository
import com.brightblu.parc.core.data.network.NetworkUsageStats
import com.brightblu.parc.core.data.power.PowerStats
import com.brightblu.parc.core.data.power.PowerStatsSource
import com.brightblu.parc.core.data.stats.StatsArchive
import com.brightblu.parc.core.data.stats.StatsHistory
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * T263: System + Power + Network as one page. The four sections are fixed; every old tile is
 * mapped into exactly one of them, so a reading is never drawn twice.
 */
@Composable
internal fun DeviceHubPage(
    powerSource: PowerStatsSource?,
    networkRepository: NetworkStatsRepository?,
    dayLedger: DayLedgerRepository?,
    entries: List<AppEntry>,
    active: Boolean,
    tiles: List<String>,
    skin: Skin,
    alerts: Set<com.brightblu.parc.core.data.stats.StatsAlertMetric> = emptySet(),
    archive: StatsArchive = StatsArchive.Empty,
    presentation: StatsPresentation,
    history: StatsHistory,
    banner: com.brightblu.parc.core.data.stats.StatsAlertMetric? = null,
    onDismissAlert: (com.brightblu.parc.core.data.stats.StatsAlertMetric) -> Unit = {},
) {
    DisposableEffect(powerSource, active) {
        powerSource?.setActive(active)
        onDispose { powerSource?.setActive(false) }
    }
    val power = powerSource?.stats?.collectAsState()?.value ?: PowerStats.Empty
    val visible = remember(tiles) { tiles.toSet() }
    val charging = power.chargingPower?.direction == com.brightblu.parc.core.data.power.PowerDirection.Charging
    val bars = remember(archive) { PowerPage.hourBars(archive.series(com.brightblu.parc.core.data.stats.StatsArchiveMetric.BatteryPercent), System.currentTimeMillis(), ZoneId.systemDefault()) }

    var snapshot by remember(networkRepository) { mutableStateOf<NetworkUsageStats?>(null) }
    LaunchedEffect(networkRepository, dayLedger, active) {
        if (active) while (isActive) {
            if (networkRepository != null) snapshot = withContext(Dispatchers.IO) { networkRepository.read() }
            dayLedger?.refresh()
            delay(Metrics.hubNetworkRefreshMillis)
        }
    }
    val stored = dayLedger?.ledger?.collectAsState()?.value
    val access = dayLedger?.accessGranted?.collectAsState()?.value
    val (period, cycle) = rememberHubPeriod("network")
    val ownPackage = LocalContext.current.packageName
    val today = dayLedger?.today()
    val network = NetworkTileContext(snapshot, stored, access, period, cycle, entries, ownPackage, today)

    Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        banner?.let { metric ->
            Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("! ${metric.alertLabel()}", Modifier.weight(1f), style = hudValueStyle(skin, skin.colors.warn),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                BasicText("Dismiss", Modifier.clickable { onDismissAlert(metric) }.padding(horizontal = Metrics.lineGapDp.dp),
                    style = hudLabelStyle(skin))
            }
        }
        val powerTiles = HubCatalog.deviceSections.getValue("Power").filter { it in visible }
        if (powerTiles.isNotEmpty()) {
            DeviceSection("Power", skin) {
                if ("battery" in powerTiles) {
                    PowerOverview(presentation.batteryPercent, charging, power.chargeTimeRemainingMillis,
                        power.screenOnTodayMillis, bars, skin, power.chargingPower)
                }
                val rest = powerTiles.filterNot { it == "battery" }
                if (rest.isNotEmpty()) {
                    PageTiles(rest, expanded = false, skin = skin, alerts = alerts, primaryFirst = false,
                        emptyHint = false, scroll = false) { tile -> PowerTileContent(tile, power, presentation.batteryPercent, archive, skin) }
                }
            }
        }
        val networkTiles = HubCatalog.deviceSections.getValue("Network").filter { it in visible }
        if (networkTiles.isNotEmpty()) {
            DeviceSection("Network", skin) {
                PageTiles(networkTiles, expanded = false, skin = skin, alerts = alerts, primaryFirst = false, emptyHint = false, scroll = false) { tile ->
                    if (tile in HubCatalog.systemHistoryTiles) {
                        StatsArchiveGraph(tile, HubCatalog.systemHistoryTiles.getValue(tile), archive, skin)
                    } else {
                        NetworkTileContent(tile, network, presentation, history, skin)
                    }
                }
            }
        }
        val storageTiles = HubCatalog.deviceSections.getValue("Storage").filter { it in visible }
        if (storageTiles.isNotEmpty()) {
            DeviceSection("Storage", skin) {
                PageTiles(storageTiles, expanded = false, skin = skin, alerts = alerts, primaryFirst = false, emptyHint = false, scroll = false) { tile ->
                    StorageTileContent(tile, presentation, archive, skin)
                }
            }
        }
        val thermalTiles = HubCatalog.deviceSections.getValue("Thermal").filter { it in visible }
        if (thermalTiles.isNotEmpty()) {
            DeviceSection("Thermal", skin) {
                PageTiles(thermalTiles, expanded = false, skin = skin, alerts = alerts, primaryFirst = false, emptyHint = false, scroll = false) { tile ->
                    when (tile) {
                        "thermal" -> Column {
                            ThermalCell(presentation.thermalChip ?: com.brightblu.parc.core.data.stats.StatsFormat.Unavailable, skin)
                            CellLabel("CPU ${presentation.cpuLoad}", skin)
                            BasicText(presentation.temperatures.joinToString(" · ") {
                                "${it.label} ${com.brightblu.parc.core.data.stats.StatsFormat.temperature(it.celsius)}"
                            }, style = hudLabelStyle(skin), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        "battery temperature" -> PowerTileContent(tile, power, presentation.batteryPercent, archive, skin)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceSection(title: String, skin: Skin, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        BasicText(title, style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        content()
    }
}

@Composable
private fun StorageTileContent(tile: String, presentation: StatsPresentation, archive: StatsArchive, skin: Skin) {
    when (tile) {
        in HubCatalog.systemHistoryTiles -> StatsArchiveGraph(tile, HubCatalog.systemHistoryTiles.getValue(tile), archive, skin)
        "storage" -> if (presentation.storageText != null) StorageCell(presentation, skin) else CellLabel("Storage —", skin)
        "ram" -> if (presentation.ramPercent != null) MemoryCell(presentation, skin) else CellLabel("RAM —", skin)
        "uptime" -> UptimeCell(presentation.uptime, skin)
    }
}
