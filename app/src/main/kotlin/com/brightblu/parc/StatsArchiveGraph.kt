package com.brightblu.parc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.stats.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.delay

internal const val StatsHistoryWindowMillis = Metrics.statsHistoryWindowMillis
internal fun historyWindow(points: List<StatsArchivePoint>, now: Long): List<StatsArchivePoint> =
    points.filter { it.atMillis in (now - StatsHistoryWindowMillis)..now }.sortedBy { it.atMillis }

@Composable
internal fun StatsArchiveGraph(label: String, metric: StatsArchiveMetric, archive: StatsArchive, skin: Skin) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(archive) {
        while (true) { now = System.currentTimeMillis(); delay(Metrics.statsHistoryRefreshMillis) }
    }
    val points = remember(archive, metric, now) { historyWindow(archive.series(metric), now) }
    val last = points.lastOrNull()
    val value = last?.let { when (metric.unit) {
        StatsArchiveUnit.Percent -> "${it.value.toInt()}%"
        StatsArchiveUnit.Celsius -> StatsFormat.temperature(it.value.toFloat())
        StatsArchiveUnit.Bytes -> PowerNetworkFormat.bytes(it.value.toLong())
    } } ?: "No history yet"
    Column(Modifier.fillMaxWidth()) {
        BasicText("${label.replaceFirstChar { it.uppercase() }} · 24 h", style = hudLabelStyle(skin))
        BasicText(value, style = hudValueStyle(skin))
        Box(Modifier.fillMaxWidth().height(Metrics.hudSparklineHeightDp.dp).drawWithCache {
            // Paths, stroke and scaling are prepared only when data, time, skin or bounds change.
            val path = Path()
            val low = if (metric.unit == StatsArchiveUnit.Percent) 0.0 else (points.minOfOrNull { it.value } ?: 0.0) - 1.0
            val high = if (metric.unit == StatsArchiveUnit.Percent) 100.0 else (points.maxOfOrNull { it.value } ?: 0.0) + 1.0
            var previous: StatsArchivePoint? = null
            for (point in points) {
                val x = ((point.atMillis - (now - StatsHistoryWindowMillis)).toDouble() / StatsHistoryWindowMillis * size.width).toFloat()
                val y = (size.height * (1 - (point.value - low) / (high - low))).toFloat()
                val prior = previous
                if (prior == null || point.atMillis - prior.atMillis > Metrics.statsHistoryGapMillis ||
                    (metric.unit == StatsArchiveUnit.Bytes && point.value < prior.value)) {
                    path.moveTo(x, y)
                    // A small mark represents a single sample without inventing a time interval.
                    path.lineTo((x + skin.hud.bracketWidthDp.dp.toPx()).coerceAtMost(size.width), y)
                } else path.lineTo(x, y)
                previous = point
            }
            val stroke = Stroke(skin.hud.bracketWidthDp.dp.toPx())
            val color = Color(skin.colors.accent.toInt())
            onDrawBehind { drawPath(path, color, style = stroke) }
        })
    }
}
