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
    // T159: the real overview with fixture numbers (not charging, 14-day low as the history). Fixed hours, no real reading.
    val bars = demoHourBars(today.batteryPercent)
    Column(Modifier.fillMaxSize().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        PowerOverview(today.batteryPercent, false, null, today.screenTimeMinutes * 60_000L, bars, skin)
    }
}

/** Eight fixture hours ending at [percent]: a gentle fall of 3 points an hour. */
internal fun demoHourBars(percent: Int): List<HourBar> =
    (0 until PowerPage.BarCount).map { i ->
        HourBar(0L, (12 + i) % 24, (percent + (PowerPage.BarCount - 1 - i) * 3).coerceIn(0, 100), i == PowerPage.BarCount - 1)
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
        Modifier.fillMaxSize().hubPageScroll()
            .padding(horizontal = Metrics.lineGapDp.dp),
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
