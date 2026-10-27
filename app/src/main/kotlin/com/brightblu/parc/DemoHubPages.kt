package com.brightblu.parc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.demo.DemoSample
import com.brightblu.parc.core.data.demo.DemoStatsFormat
import com.brightblu.parc.core.data.demo.DemoStatsStore
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsSection
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T130: the other demo hub bodies. Each one draws only fixture text or fixture numbers, so the
 * screenshots in `reports/public-screenshots/` cannot contain a real note, app name or device
 * reading. The real page composables are never entered while demo is on.
 */

@Composable
internal fun DemoPowerPage(store: DemoStatsStore, skin: Skin) {
    var sample by remember(store) { mutableStateOf<DemoSample?>(null) }
    LaunchedEffect(store) {
        sample = withContext(Dispatchers.IO) { runCatching { store.read() }.getOrNull() }
    }
    val data = sample ?: run {
        CellLabel("Loading sample…", skin)
        return
    }
    val today = data.today ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberHubScrollState()), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        HudCell(skin, Modifier.fillMaxWidth()) { HubHero(DemoStatsFormat.percent(today.batteryPercent), "Battery · not charging", skin) }
        if (LocalHubSize.current != HubSize.Compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
                HudCell(skin, Modifier.weight(1f)) { Readout("Battery temperature", "31°C", skin) }
                HudCell(skin, Modifier.weight(1f)) { Readout("Screen on today", DemoStatsFormat.duration(today.screenTimeMinutes), skin) }
            }
            if (LocalHubSize.current == HubSize.Tall) DemoStatCell("Battery history", DemoStatsFormat.percent(today.batteryPercent),
                "14 days · low ${DemoStatsFormat.percent(data.batteryLowPercent)}", data.days.map { it.batteryPercent },
                Metrics.hubTileMaxHeightDp, skin, Modifier.fillMaxWidth())
        }
    }
}

/** Three generic jottings. They are constants in code, never read from the notes store. */
@Composable
internal fun DemoNotesPage(skin: Skin) {
    val notes = listOf(
        "Shopping" to "Milk, bread, coffee",
        "Reading" to "Finish the chapter, then two lines of notes",
        "Weekend" to "Walk by the river if it stays dry",
        "Packing" to "Charger, headphones, umbrella",
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberHubScrollState())
            .padding(horizontal = Metrics.gridDp.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp),
    ) {
        CellLabel("Quick jottings", skin)
        notes.take(if (LocalHubSize.current == HubSize.Compact) 1 else if (LocalHubSize.current == HubSize.Standard) 2 else notes.size).forEach { (title, body) ->
            HudCell(skin, Modifier.fillMaxWidth()) {
                HubHero(title, body, skin)
            }
        }
    }
}
