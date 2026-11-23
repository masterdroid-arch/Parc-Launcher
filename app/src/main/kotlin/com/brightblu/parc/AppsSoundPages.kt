package com.brightblu.parc

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.brightblu.parc.ring.FeelEvent
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.apps.*
import com.brightblu.parc.core.data.ledger.DayLedgerRepository
import com.brightblu.parc.core.data.ledger.LedgerAppKind
import com.brightblu.parc.core.data.ledger.LedgerMath
import com.brightblu.parc.core.data.ledger.LedgerTopApps
import com.brightblu.parc.core.data.audio.*
import com.brightblu.parc.core.data.storage.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal object AppsSoundFormat {
    fun time(millis: Long?): String {
        if (millis == null || millis < 0) return "—"
        val minutes = millis / 60_000
        return when { millis == 0L -> "0 min"; minutes == 0L -> "<1 min"; minutes < 60 -> "$minutes min"; else -> "${minutes / 60} h ${minutes % 60} min" }
    }
    fun sliderIndex(fraction: Float, max: Int) = VolumeScale.indexForFraction(fraction, max)
    fun bar(value: Long, max: Long): Float = if (max <= 0) 0f else (value.toDouble() / max).toFloat().coerceIn(0f, 1f)
}

@Composable
internal fun AppsHubPage(ledger: DayLedgerRepository?, newApps: NewAppsProvider?, storage: AppStorageRepository?,
    entries: List<AppEntry>, active: Boolean, tiles: List<String>, expanded: Boolean, skin: Skin, onLaunch: (AppId) -> Unit) {
    var recent by remember(newApps) { mutableStateOf<List<NewApp>?>(null) }
    var sizes by remember(storage) { mutableStateOf<StorageResult?>(null) }
    var failure by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(newApps, storage, active, entries) {
        if (active) withContext(Dispatchers.IO) {
            recent = newApps?.recent()
            sizes = storage?.read()
        }
    }
    LaunchedEffect(ledger, active) {
        if (active) while (isActive) {
            ledger?.refresh()
            delay(Metrics.hubNetworkRefreshMillis)
        }
    }
    // T185: one screen-time tile; its period (Today, 7 days, 30 days, This year, All time) is remembered for this page.
    val stored = ledger?.ledger?.collectAsState()?.value
    val access = ledger?.accessGranted?.collectAsState()?.value
    val (period, cycle) = rememberHubPeriod("apps")
    val today = ledger?.today()
    val summary = remember(stored, period, today) { if (stored != null && today != null) LedgerMath.summary(stored, period, today) else null }
    PageTiles(tiles, expanded, skin) { tile ->
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            when (tile) {
                "screen time" -> {
                    PeriodLabel(summary?.screenLabel() ?: period.label, skin, cycle)
                    val shown = summary != null && access != false && summary.screenDays > 0
                    Readout("Screen time", AppsSoundFormat.time(summary?.screenOnMillis.takeIf { shown }), skin)
                    if (stored == null) CellLabel("Loading…", skin)
                    if (access == false) UsageAccessRow(skin)
                    else if (stored != null && today != null) {
                        val top = LedgerTopApps.of(LedgerMath.appTotals(stored, period, today, LedgerAppKind.Screen), entries, context.packageName, ScreenTimeRepository.TopCount)
                        val max = top.maxOfOrNull { it.value } ?: 0L
                        if (top.isEmpty()) CellLabel("No screen time recorded", skin)
                        top.forEach { app -> AppBarRow(app.label, AppsSoundFormat.time(app.value), AppsSoundFormat.bar(app.value, max), skin) }
                    }
                }
                "new apps" -> {
                    CellLabel("New apps · last 7 days", skin)
                    if (recent == null) CellLabel("Loading…", skin)
                    if (recent?.isEmpty() == true) CellLabel("No new apps", skin)
                    recent.orEmpty().forEach { item ->
                        Box(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
                            .clickable { onLaunch(item.app.id) }) {
                            BasicText(item.app.label, style = hudValueStyle(skin, skin.colors.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                "biggest apps" -> {
                    CellLabel("Biggest apps · app + data + cache", skin)
                    if (sizes == null) CellLabel("Loading…", skin)
                    if (sizes == StorageResult.Unavailable) UsageAccessRow(skin)
                    val biggest = (sizes as? StorageResult.Available)?.biggest()
                    if (biggest?.isEmpty() == true) CellLabel("No app storage recorded", skin)
                    val max = biggest?.maxOfOrNull { it.totalBytes } ?: 0L
                    biggest.orEmpty().forEach { app ->
                        AppBarRow(app.label, PowerNetworkFormat.bytes(app.totalBytes), AppsSoundFormat.bar(app.totalBytes, max), skin)
                        val entry = entries.firstOrNull { it.id == app.appId }
                        if (entry != null && !entry.isSystem && entry.packageName != context.packageName) {
                            Box(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
                                .semantics { contentDescription = "Uninstall ${app.label}" }.clickable {
                                    failure = runCatching { context.startActivity(Intent(if (BuildConfig.FLAVOR == "play") android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Intent.ACTION_DELETE,
                                        Uri.fromParts("package", entry.packageName, null))) }.isFailure
                                }) { BasicText("Uninstall", style = hudValueStyle(skin, skin.colors.accent)) }
                        }
                    }
                    if (failure) CellLabel("Couldn't open uninstall", skin)
                }
            }
        }
    }
}

@Composable
private fun AppBarRow(label: String, value: String, fraction: Float, skin: Skin) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
        BasicText(label, Modifier.weight(1f), style = hudValueStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(value, style = hudLabelStyle(skin))
    }
    DataBar(fraction, skin)
}

@Composable
internal fun SoundHubPage(repository: VolumeRepository?, active: Boolean, tiles: List<String>, expanded: Boolean, skin: Skin,
    scroll: Boolean = true) {
    val state = repository?.state?.collectAsState()?.value ?: VolumeState.Empty
    LaunchedEffect(repository, active) { if (active) repository?.refresh() }
    val view = LocalView.current
    val feel = rememberFeelPlayer()
    var refused by remember { mutableStateOf<VolumeStream?>(null) }
    PageTiles(tiles, expanded, skin, scroll = scroll) { tile ->
        if (tile == "output") Readout("Output device", state.outputDeviceName ?: "—", skin)
        else {
            val stream = VolumeStream.All.first { it.name.lowercase() == tile }
            val level = state.level(stream)
            Column {
                // T377: a labelled bar, not a value over a slider. The stream name sits left, the bar
                // fills the middle and the exact "n / m" reading sits right, so the four streams line up.
                Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(stream.name, Modifier.width(Metrics.hudBarLabelDp.dp), style = hudValueStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(Metrics.itemGapDp.dp))
                    if (level != null) {
                        VolumeSlider(level, skin, active, Modifier.weight(1f)) { fraction ->
                            val index = AppsSoundFormat.sliderIndex(fraction, level.maxIndex)
                            val current = repository?.state?.value?.level(stream)?.index
                            if (index != current) {
                                if (repository?.setVolume(stream, index) == true) {
                                    refused = null
                                    feel.play(view, FeelEvent.Tick)
                                } else refused = stream
                            }
                        }
                        Spacer(Modifier.width(Metrics.itemGapDp.dp))
                        BasicText("${level.index} / ${level.maxIndex}", style = hudLabelStyle(skin), maxLines = 1)
                    } else {
                        Box(Modifier.weight(1f)) { DataBar(0f, skin) }
                        Spacer(Modifier.width(Metrics.itemGapDp.dp))
                        BasicText("Unavailable", style = hudLabelStyle(skin), maxLines = 1)
                    }
                }
                if (refused == stream) CellLabel("Volume change refused by Android", skin)
            }
        }
    }
}

/** Consumes horizontal drags before the hub pager; TalkBack uses the same discrete mapping. */
@Composable
private fun VolumeSlider(level: StreamVolume, skin: Skin, active: Boolean, modifier: Modifier = Modifier, change: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    var drag by remember(level.stream) { mutableFloatStateOf(level.fraction) }
    val currentChange by rememberUpdatedState(change)
    val currentLevel by rememberUpdatedState(level)
    val base = Color(skin.colors.lineBase.toInt())
    val accent = Color(skin.colors.accent.toInt())
    Canvas(modifier.height(Metrics.menuRowHeightDp.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .semantics {
            contentDescription = "${level.stream.name} volume"
            progressBarRangeInfo = ProgressBarRangeInfo(level.index.toFloat(), 0f..level.maxIndex.toFloat(), (level.maxIndex - 1).coerceAtLeast(0))
            setProgress { target -> if (active && level.maxIndex > 0) { change(target / level.maxIndex); true } else false }
        }
        .draggable(rememberDraggableState { delta ->
            drag = (drag + delta / width).coerceIn(0f, 1f)
            currentChange(drag)
        }, Orientation.Horizontal, enabled = active && level.maxIndex > 0, startDragImmediately = true,
            onDragStarted = { drag = currentLevel.fraction })) {
        val h = Metrics.hudBarHeightDp.dp.toPx()
        drawRect(base, Offset(0f, (size.height - h) / 2), Size(size.width, h))
        drawRect(accent, Offset(0f, (size.height - h) / 2), Size(size.width * level.fraction, h))
        val radius = Metrics.gridDp.dp.toPx()
        drawCircle(accent, radius, Offset((size.width * level.fraction).coerceIn(radius, size.width - radius), size.height / 2))
    }
}
