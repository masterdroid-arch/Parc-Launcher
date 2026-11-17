package com.brightblu.parc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.demo.DemoStatsStore
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/** T263/T268: invented readings for the merged Device page; no repository or device value is touched. */
@Composable
internal fun DemoDevicePage(store: DemoStatsStore?, skin: Skin) {
    Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        BasicText("Power", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        if (store != null) DemoPowerPage(store, skin, scroll = false) else CellLabel("Loading sample…", skin)
        BasicText("Network", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        DemoNetworkPage(skin, scroll = false)
        BasicText("Storage", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            Readout("Storage used", "42 GB of 128 GB", skin)
            Readout("Memory used", "3.1 GB of 8 GB", skin)
            Readout("Uptime", "2 h 14 min", skin)
        }
        BasicText("Thermal", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            Readout("Thermal state", "Normal", skin)
            Readout("CPU", "18%", skin)
            Readout("Battery temperature", "31 °C", skin)
        }
    }
}

/** T263: invented volume rows plus the existing demo toggles, so Controls can be photographed safely. */
@Composable
internal fun DemoControlsPage(skin: Skin) {
    Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        BasicText("Volume", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            Readout("Media", "10 / 15", skin)
            Readout("Ring", "6 / 7", skin)
            Readout("Alarm", "7 / 7", skin)
            Readout("Call", "5 / 7", skin)
            Readout("Output device", "Phone speaker", skin)
        }
        BasicText("Quick toggles", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        QuickTogglesHubPage(QuickToggleCatalog.tiles, active = false, skin = skin, demo = true, scroll = false)
    }
}
