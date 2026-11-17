package com.brightblu.parc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import java.time.LocalDate

/**
 * T268: the Network and Screen time pages in demo mode. They use the real tile frame, period label
 * and type scale with [DemoUsage]'s made-up figures; no repository, permission or device reading is
 * touched. The period choice is remembered in the demo namespace by the existing per-page key.
 */
@Composable
internal fun DemoNetworkPage(skin: Skin, scroll: Boolean = true) {
    val today = remember { LocalDate.now() }
    val (period, cycle) = rememberHubPeriod("network")
    val page = remember(period, today) { DemoUsage.network(period, today) }
    PageTiles(HubCatalog.networkTiles, expanded = false, skin = skin, scroll = scroll) { tile ->
        when (tile) {
            "traffic" -> Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                CellLabel("Live traffic · ↓ down / ↑ up", skin)
                BasicText("1.2 MB/s / 86 kB/s", style = hudValueStyle(skin), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                DataBar(0.58f, skin)
            }
            "data totals" -> Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                PeriodLabel(page.summary.networkLabel(), skin, cycle)
                Readout("Wi-Fi / mobile",
                    "${PowerNetworkFormat.bytes(page.summary.wifiBytes)} / ${PowerNetworkFormat.bytes(page.summary.mobileBytes)}",
                    skin)
            }
            "top apps" -> Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                CellLabel("Top apps · data", skin)
                PeriodLabel(page.summary.networkLabel(), skin, cycle)
                DemoTopApps(page.apps, skin) { PowerNetworkFormat.bytes(it) }
            }
            "wifi link" -> Readout("Wi-Fi · speed / signal", "433 Mbps / −58 dBm", skin)
            "network type" -> Readout("Mobile network type", "5G", skin)
        }
    }
}

@Composable
internal fun DemoAppsPage(skin: Skin) {
    val today = remember { LocalDate.now() }
    val (period, cycle) = rememberHubPeriod("apps")
    val page = remember(period, today) { DemoUsage.screen(period, today) }
    PageTiles(HubCatalog.appsTiles, expanded = false, skin = skin) { tile ->
        when (tile) {
            "screen time" -> Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                PeriodLabel(page.summary.screenLabel(), skin, cycle)
                Readout("Screen time", AppsSoundFormat.time(page.summary.screenOnMillis), skin)
                DemoTopApps(page.apps, skin) { AppsSoundFormat.time(it) }
            }
            "new apps" -> Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                CellLabel("New apps · last 7 days", skin)
                DemoUsage.newApps.forEach { app ->
                    DemoLabelRow(
                        label = app.label,
                        value = if (app.daysAgo == 1) "yesterday" else "${app.daysAgo} days ago",
                        skin = skin,
                    )
                }
            }
            "biggest apps" -> Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                CellLabel("Biggest apps · app + data + cache", skin)
                val max = DemoUsage.biggestApps.maxOf { it.second }
                DemoUsage.biggestApps.forEach { (label, bytes) ->
                    DemoAppBarRow(label, PowerNetworkFormat.bytes(bytes), bytes.toFloat() / max, skin)
                }
            }
        }
    }
}

@Composable
private fun DemoTopApps(
    apps: List<DemoUsageApp>,
    skin: Skin,
    format: (Long) -> String,
) {
    val max = apps.maxOfOrNull { it.value } ?: 1L
    apps.forEach { app ->
        DemoAppBarRow(app.label, format(app.value), app.value.toFloat() / max.coerceAtLeast(1L), skin)
    }
}

@Composable
private fun DemoAppBarRow(label: String, value: String, fraction: Float, skin: Skin) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
        BasicText(label, Modifier.weight(1f), style = hudValueStyle(skin), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        BasicText(value, style = hudLabelStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DataBar(fraction.coerceIn(0f, 1f), skin)
}

@Composable
private fun DemoLabelRow(label: String, value: String, skin: Skin) {
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
        BasicText(label, Modifier.weight(1f), style = hudValueStyle(skin), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        BasicText(value, style = hudLabelStyle(skin).copy(color = Color(skin.colors.textSecondary.toInt())),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
