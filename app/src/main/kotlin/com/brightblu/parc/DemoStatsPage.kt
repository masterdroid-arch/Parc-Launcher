package com.brightblu.parc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.demo.DemoSample
import com.brightblu.parc.core.data.demo.DemoStatsFormat
import com.brightblu.parc.core.data.demo.DemoStatsStore
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** T121: the one demo sample page id; T130 expands the demo hub to four safe pages. */
internal const val DemoHubPageId = "demo"

/** T130: the pages the demo hub shows, in order. Every body is demo-only or the clock; T136 adds Quick toggles with fixed sample states. */
internal val DemoHubPages: List<String> = listOf("clock", "power", "focus", "notes", QuickToggleCatalog.PageId) +
    (if (WeatherVariant.Available) listOf(WeatherVariant.PageId) else emptyList())

/**
 * T121/T130: the page list the hub shows. With demo off this is exactly the previous expression
 * (`configured.filter(allowed)`), so the real hub is untouched; with demo on the fixed four-page
 * list is returned and no configured or real page body can enter.
 */
internal fun hubPagesFor(
    demo: Boolean,
    configured: List<String>,
    allowed: (String) -> Boolean = { true },
): List<String> = if (demo) DemoHubPages else configured.filter(allowed)

/** T121/T130: the page name. Demo and real titles are the same word; the parameter stays so callers do not branch. */
internal fun hubPageTitle(demo: Boolean, page: String?): String =
    page?.let(::hubPageName) ?: "Stats"

/**
 * T121: two weeks of sample stats, rendered in the hub's own tile frame. The sample comes from
 * [DemoStatsStore] (seeded under the demo namespace); no app label, contact or location is read
 * or drawn. The page fits two rows of tiles, like the System page.
 */
@Composable
internal fun DemoStatsPage(store: DemoStatsStore, skin: Skin) {
    var sample by remember(store) { mutableStateOf<DemoSample?>(null) }
    var failed by remember(store) { mutableStateOf(false) }
    LaunchedEffect(store) {
        val result = withContext(Dispatchers.IO) { runCatching { store.read() } }
        sample = result.getOrNull()
        failed = result.isFailure
    }
    val data = sample
    val today = data?.today
    if (data == null || today == null) {
        CellLabel(if (failed) "Sample data unavailable" else "Loading sample…", skin)
        return
    }
    val cellHeight = HubPanelLayout.tileHeightDp(LocalHubContentHeight.current, 2)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
            DemoStatCell(
                label = "Screen time",
                value = DemoStatsFormat.duration(today.screenTimeMinutes),
                detail = "avg ${DemoStatsFormat.duration(data.screenTimeAverageMinutes)}",
                bars = data.days.map { it.screenTimeMinutes },
                cellHeight = cellHeight,
                skin = skin,
                modifier = Modifier.weight(1f),
            )
            DemoStatCell(
                label = "Unlocks",
                value = DemoStatsFormat.count(today.unlocks),
                detail = "avg ${DemoStatsFormat.count(data.unlocksAverage)}",
                bars = data.days.map { it.unlocks },
                cellHeight = cellHeight,
                skin = skin,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
            DemoStatCell(
                label = "Steps",
                value = DemoStatsFormat.count(today.steps),
                detail = "avg ${DemoStatsFormat.count(data.stepsAverage)}",
                bars = data.days.map { it.steps },
                cellHeight = cellHeight,
                skin = skin,
                modifier = Modifier.weight(1f),
            )
            DemoStatCell(
                label = "Battery",
                value = DemoStatsFormat.percent(today.batteryPercent),
                detail = "low ${DemoStatsFormat.percent(data.batteryLowPercent)}",
                bars = data.days.map { it.batteryPercent },
                cellHeight = cellHeight,
                skin = skin,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun DemoStatCell(
    label: String,
    value: String,
    detail: String,
    bars: List<Int>,
    cellHeight: Float,
    skin: Skin,
    modifier: Modifier = Modifier,
) {
    HudCell(skin, modifier.height(cellHeight.dp)) {
        Column(Modifier.fillMaxSize()) {
            CellLabel(label, skin)
            BasicText(value, style = hudValueStyle(skin))
            BasicText(detail, style = hudLabelStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height((Metrics.hudCellGapDp / 4).dp))
            Spacer(Modifier.weight(1f))
            DemoSparkline(bars, skin)
        }
    }
}

/** Fourteen bars, today in the accent colour. Static, so it allocates nothing while idle. */
@Composable
private fun DemoSparkline(values: List<Int>, skin: Skin) {
    val accent = Color(skin.colors.accent.toInt())
    val base = Color(skin.colors.lineBase.toInt())
    val gapDp = skin.hud.segmentGapDp
    Canvas(Modifier.fillMaxWidth().height(Metrics.hudSparklineHeightDp.dp)) {
        if (values.isEmpty()) return@Canvas
        val peak = values.max().coerceAtLeast(1)
        val slot = size.width / values.size
        val gap = (gapDp.dp.toPx()).coerceAtMost(slot / 2f)
        val barWidth = (slot - gap).coerceAtLeast(1f)
        values.forEachIndexed { index, value ->
            val barHeight = size.height * (value.toFloat() / peak)
            drawRect(
                color = if (index == values.lastIndex) accent else base,
                topLeft = Offset(index * slot, size.height - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}
