package com.brightblu.parc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
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
internal fun DemoPowerPage(store: DemoStatsStore, skin: Skin, scroll: Boolean = true) {
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
    Column(Modifier.fillMaxWidth().then(if (scroll) Modifier.fillMaxSize().hubPageScroll() else Modifier),
        verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
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

/** T192/T249: invented rows, a made-up book and credit; never enters a real provider, library or quote store. */
@Composable
internal fun DemoTodayPage(skin: Skin) {
    // T268: the demo uses today so a screenshot can never show a date that has already passed.
    TodayContent(java.time.LocalDate.now(), listOf(
        TodayLine("agenda", "Walk by the river · in 40 min", "Next event",
            figure = "40 min", caption = "Next event · Walk by the river"),
        TodayLine("timers", "Tea · 4 min left", "Timer", figure = "4 min", caption = "Timer · Tea"),
        TodayLine("notes", "Reminder · Tue 18:00 · Water the plants", "Reminder",
            figure = "18:00", caption = "Reminder · Tue · Water the plants"),
        TodayLine("steps", "5,210 steps · goal 8,000", "Steps",
            figure = "5,210", caption = "Steps · goal 8,000"),
        TodayLine("sky", "Golden hour · 16:32", "Sky", figure = "16:32", caption = "Golden hour · Sky"),
    ), Quote("demo-today", "There is time to sit by the window and watch the afternoon arrive.",
        "Mira Vale", "Imaginary afternoon"), null, skin, LocalTodayOpenPage.current,
        continueBook = TodayLines.continueReading(DemoLibraryBooks),
        clock = {
            // 1.5.1: the real header with fixed demo values: 09:41, an alarm tomorrow at 07:00 and a second clock.
            val zone = java.time.ZoneId.systemDefault()
            val now = java.time.LocalDate.now().atTime(9, 41).atZone(zone).toInstant().toEpochMilli()
            val alarm = java.time.LocalDate.now().plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
            TodayClockHeader(skin, ClockOptions(zone = "America/New_York"), now, alarm, false, zone, null, {}, {})
        })
}
