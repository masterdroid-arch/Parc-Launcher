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
import com.brightblu.parc.core.data.audio.*
import com.brightblu.parc.core.data.storage.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.Dispatchers
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
internal fun AppsHubPage(screenTime: ScreenTimeRepository?, newApps: NewAppsProvider?, storage: AppStorageRepository?,
    entries: List<AppEntry>, active: Boolean, tiles: List<String>, expanded: Boolean, skin: Skin, onLaunch: (AppId) -> Unit) {
    var time by remember(screenTime) { mutableStateOf<ScreenTimeResult?>(null) }
    var recent by remember(newApps) { mutableStateOf<List<NewApp>?>(null) }
    var sizes by remember(storage) { mutableStateOf<StorageResult?>(null) }
    var failure by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(screenTime, newApps, storage, active, entries) {
        if (active) withContext(Dispatchers.IO) {
            time = screenTime?.read()
            recent = newApps?.recent()
            sizes = storage?.read()
        }
    }
    PageTiles(tiles, expanded, skin) { tile ->
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            when (tile) {
                "screen time today", "screen time 7 days" -> {
                    val today = tile == "screen time today"
                    val available = time as? ScreenTimeResult.Available
                    Readout(if (today) "Screen time · today" else "Screen time · 7 days incl. today",
                        AppsSoundFormat.time(if (today) available?.todayTotalMillis else available?.last7DaysTotalMillis), skin)
                    if (time == null) CellLabel("Loading…", skin)
                    if (time == ScreenTimeResult.Unavailable) UsageAccessRow(skin)
                    if (available != null) {
                        val top = if (today) available.topToday() else available.topLast7Days()
                        val max = top.maxOfOrNull { if (today) it.todayMillis else it.last7DaysMillis } ?: 0L
                        if (top.isEmpty()) CellLabel("No screen time recorded", skin)
                        top.forEach { app ->
                            val value = if (today) app.todayMillis else app.last7DaysMillis
                            AppBarRow(app.label, AppsSoundFormat.time(value), AppsSoundFormat.bar(value, max), skin)
                        }
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
internal fun SoundHubPage(repository: VolumeRepository?, active: Boolean, tiles: List<String>, expanded: Boolean, skin: Skin) {
    val state = repository?.state?.collectAsState()?.value ?: VolumeState.Empty
    LaunchedEffect(repository, active) { if (active) repository?.refresh() }
    val view = LocalView.current
    val feel = rememberFeelPlayer()
    var refused by remember { mutableStateOf<VolumeStream?>(null) }
    PageTiles(tiles, expanded, skin) { tile ->
        if (tile == "output") Readout("Output device", state.outputDeviceName ?: "—", skin)
        else {
            val stream = VolumeStream.All.first { it.name.lowercase() == tile }
            val level = state.level(stream)
            Column {
                Readout(stream.name, level?.let { "${it.index} / ${it.maxIndex}" } ?: "Unavailable", skin)
                if (level != null) VolumeSlider(level, skin, active) { fraction ->
                    val index = AppsSoundFormat.sliderIndex(fraction, level.maxIndex)
                    val current = repository?.state?.value?.level(stream)?.index
                    if (index != current) {
                        if (repository?.setVolume(stream, index) == true) {
                            refused = null
                            feel.play(view, FeelEvent.Tick)
                        } else refused = stream
                    }
                }
                if (refused == stream) CellLabel("Volume change refused by Android", skin)
            }
        }
    }
}

/** Consumes horizontal drags before the hub pager; TalkBack uses the same discrete mapping. */
@Composable
private fun VolumeSlider(level: StreamVolume, skin: Skin, active: Boolean, change: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    var drag by remember(level.stream) { mutableFloatStateOf(level.fraction) }
    val currentChange by rememberUpdatedState(change)
    val currentLevel by rememberUpdatedState(level)
    val base = Color(skin.colors.lineBase.toInt())
    val accent = Color(skin.colors.accent.toInt())
    Canvas(Modifier.fillMaxWidth().height(Metrics.menuRowHeightDp.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
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
