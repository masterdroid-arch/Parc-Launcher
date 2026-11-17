package com.brightblu.parc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.DayLedgerRepository
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.steps.StepMath
import com.brightblu.parc.core.data.steps.StepOptions
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * T197: the Records page. Personal bests worked out from the day ledger alone (no new data is recorded). The page
 * refreshes the ledger while it is the visible page and otherwise only draws [RecordsContent].
 */
@Composable
internal fun RecordsHubPage(ledger: DayLedgerRepository?, skin: Skin, active: Boolean) {
    val context = LocalContext.current
    val steps = LocalStepsRepository.current
    val store = remember(context) { HubConfigStore(context) }
    LaunchedEffect(ledger, active) {
        if (active) while (true) {
            ledger?.refresh()
            delay(Metrics.hubNetworkRefreshMillis)
        }
    }
    val stored = ledger?.ledger?.collectAsState()?.value ?: DayLedger.Empty
    val options = store.stepOptions.collectAsState(initial = StepOptions()).value
    val live = steps?.live?.collectAsState()?.value
    val today = ledger?.today() ?: LocalDate.now()
    val todaySteps = StepMath.todayTotal(stored.day(today)?.steps, live?.pending, today)
    val state = remember(stored, options.goal, todaySteps, today) { RecordsMath.compute(stored, today, options.goal, todaySteps) }
    Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        RecordsContent(state, skin)
    }
}

/** The drawing, stateless so the demo page shares it. Every value carries a label that says what it is. */
@Composable
internal fun RecordsContent(state: RecordsState, skin: Skin) {
    val gap = Metrics.hudCellGapDp.dp
    when (state) {
        is RecordsState.NotEnough -> HudCell(skin, Modifier.fillMaxWidth()) {
            val (value, label) = RecordsMath.notEnoughText(state)
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label $value" }) {
                BasicText(value, style = hudValueStyle(skin))
                CellLabel(label, skin)
            }
        }
        is RecordsState.Ready -> {
            // 1.5.1: one card, two groups. Each record is a label over a big figure, the date quiet at the right of the
            // label and today's number at the right of the figure; thin dividers keep the rows apart.
            HudCell(skin, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    HubSectionLabel("Personal bests", skin)
                    state.records.forEachIndexed { index, row ->
                        RecordLine(RecordsMath.rowText(row), skin)
                        if (index < state.records.lastIndex) HubDivider(skin)
                    }
                    if (state.streaks.isNotEmpty()) {
                        Spacer(Modifier.height(Metrics.itemGapDp.dp))
                        HubSectionLabel("Streaks", skin)
                        state.streaks.forEachIndexed { index, row ->
                            RecordLine(RecordsMath.streakText(row), skin)
                            if (index < state.streaks.lastIndex) HubDivider(skin)
                        }
                    }
                }
            }
        }
    }
}

/** One merged TalkBack reading per row. The label and date on the first line; the value and today's number on the second. */
@Composable
private fun RecordLine(text: RecordsMath.RowText, skin: Skin) {
    val aside = listOfNotNull(text.mark, text.date).joinToString(" · ").ifEmpty { null }
    HubStatRow(text.label, text.value, skin, labelAside = aside, side = text.beside, sideAccent = text.mark != null,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text.description })
}

/** The demo page: a made-up month, no ledger and no permission. */
@Composable
internal fun DemoRecordsPage(skin: Skin) {
    val today = LocalDate.now()
    val state = remember(today) {
        val steps = intArrayOf(6200, 8400, 5100, 9300, 7600, 4200, 10450, 8800, 7100, 6900, 9100, 5600, 8200, 7700)
        var ledger = DayLedger.Empty
        for (i in 1..30) {
            val n = steps[i % steps.size]
            ledger = ledger.withDay(LedgerDay(today.minusDays(i.toLong()), wifiBytes = 400_000_000L + n * 40_000L, mobileBytes = 90_000_000L + i * 1_000_000L,
                screenOnMillis = (150L + (i * 17) % 90) * 60_000L, steps = n, pickups = 30 + (i * 7) % 40, phoneFreeMillis = (110L + (i * 13) % 90) * 60_000L))
        }
        ledger = ledger.withDay(LedgerDay(today, screenOnMillis = 62L * 60_000L, steps = 9_900, pickups = 14, phoneFreeMillis = 3L * 3_600_000L))
        RecordsMath.compute(ledger, today, 8_000, 9_900)
    }
    Column(Modifier.fillMaxWidth().hubPageScroll()) { RecordsContent(state, skin) }
}
