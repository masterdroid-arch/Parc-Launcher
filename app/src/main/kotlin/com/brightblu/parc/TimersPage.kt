package com.brightblu.parc

import android.Manifest
import android.app.AlarmManager
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brightblu.parc.core.data.timers.*
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * T101: the Timers page. State lives in [TimersStore] (absolute wall-clock end times), alarms in
 * [TimerScheduler]; this page only reads the store and sends changes through [TimerEngine]. The
 * clock below ticks only while the page is the visible one, so nothing runs in the background.
 */
@Composable
internal fun TimersHubPage(active: Boolean, tiles: List<String>, expanded: Boolean, skin: Skin) {
    val context = LocalContext.current
    val store = remember(context) { TimersStore(context) }
    val state by store.state.collectAsState(initial = null)
    val asked by store.asked.collectAsState(initial = true)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var allowed by remember { mutableStateOf(TimerNotifier.notificationsAllowed(context)) }
    var exact by remember { mutableStateOf(TimerScheduler.canScheduleExact(context)) }
    var alarmLine by remember { mutableStateOf<String?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = TimerNotifier.notificationsAllowed(context)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = TimerNotifier.notificationsAllowed(context)
                exact = TimerScheduler.canScheduleExact(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val current = state
    val stopwatchRunning = current?.stopwatch?.running == true
    val timersRunning = current?.book?.timers?.any { it.status == TimerStatus.Running } == true
    LaunchedEffect(active, stopwatchRunning, timersRunning) {
        if (!active) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var round = 0
            while (true) {
                now = System.currentTimeMillis()
                if (round % 40 == 0) alarmLine = readNextAlarm(context)
                round++
                delay(if (stopwatchRunning) 50L else if (timersRunning) 250L else 1000L)
            }
        }
    }
    // Ends a running timer on screen as soon as its time passes, even before the receiver has run.
    LaunchedEffect(active, now / 1000) {
        if (active && current?.book?.timers?.any { it.effective(now) == TimerStatus.Finished && it.status == TimerStatus.Running } == true) {
            TimerEngine.launch(context)
        }
    }

    fun startTimer(ms: Long) {
        TimerEngine.launch(context) { s, at -> s.book.start(ms, at)?.let { s.copy(book = it) } ?: s }
        if (TimerRules.shouldAskNotifications(allowed, asked)) {
            TimerEngine.launchMarkAsked(context)
            runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    if (customOpen) CustomTimerDialog(skin, onDismiss = { customOpen = false }) { ms -> customOpen = false; startTimer(ms) }

    PageTiles(tiles, expanded, skin, leading = {
        if (!allowed) TimerAction("Allow ring", skin, "Allow notifications so timers can ring") {
            TimerEngine.launchMarkAsked(context)
            runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        if (allowed && !exact) CellLabel("Timers may ring late", skin)
    }) { tile ->
        val st = current
        when (tile) {
            "timers" -> TimersTile(st?.book ?: TimerBook(), now, skin,
                onPreset = { minutes -> startTimer(minutes * 60_000L) }, onCustom = { customOpen = true },
                onAdd = { id -> TimerEngine.launch(context) { s, at -> s.copy(book = s.book.addMinute(id, at)) } },
                onPause = { id -> TimerEngine.launch(context) { s, at -> s.copy(book = s.book.pause(id, at)) } },
                onResume = { id -> TimerEngine.launch(context) { s, at -> s.copy(book = s.book.resume(id, at)) } },
                onReset = { id -> TimerEngine.launch(context) { s, at -> s.copy(book = s.book.reset(id).resume(id, at)) } },
                onDelete = { id -> TimerEngine.launch(context) { s, _ -> s.copy(book = s.book.delete(id)) } })
            "stopwatch" -> StopwatchTile(st?.stopwatch ?: Stopwatch(), now, skin,
                onToggle = { TimerEngine.launch(context) { s, at -> s.copy(stopwatch = if (s.stopwatch.running) s.stopwatch.stop(at) else s.stopwatch.start(at)) } },
                onLap = { TimerEngine.launch(context) { s, at -> s.copy(stopwatch = s.stopwatch.lap(at)) } },
                onReset = { TimerEngine.launch(context) { s, _ -> s.copy(stopwatch = s.stopwatch.reset()) } })
            else -> Readout("Next alarm", alarmLine ?: "—", skin)
        }
    }
}

/** The alarm line: the next system alarm, or a note when Android can only report Parc's own timer. */
private fun readNextAlarm(context: android.content.Context): String = try {
    val info = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
    val own = info?.showIntent?.creatorPackage == context.packageName
    if (own) "Hidden while a timer is running"
    else TimerFormat.nextAlarm(info?.triggerTime, ZoneId.systemDefault(), DateFormat.is24HourFormat(context))
} catch (e: Exception) {
    "Unavailable"
}

@Composable
private fun TimersTile(book: TimerBook, now: Long, skin: Skin, onPreset: (Int) -> Unit, onCustom: () -> Unit,
    onAdd: (Int) -> Unit, onPause: (Int) -> Unit, onResume: (Int) -> Unit, onReset: (Int) -> Unit, onDelete: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
        if (book.timers.isEmpty()) HubHero("Ready", hubTemplates.getValue("timers").empty, skin)
        book.timers.forEach { timer ->
            key(timer.id) {
                val status = timer.effective(now)
                val total = TimerFormat.duration(timer.totalMs)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
                        TimerCountdownRing(timerFraction(timer, now), skin)
                        Column(Modifier.weight(1f)) {
                            HubHero(TimerFormat.countdown(timerRemainingMs(timer, now)), "$total · ${TimerFormat.statusText(status)}", skin)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (status == TimerStatus.Running) {
                            TimerAction("Add 1 min", skin, "Add 1 min to $total timer", enabled = timer.totalMs < TimerRules.MaxMs) { onAdd(timer.id) }
                            TimerAction("Pause", skin) { onPause(timer.id) }
                        } else if (status != TimerStatus.Finished) {
                            TimerAction(if (status == TimerStatus.Idle) "Start" else "Resume", skin) { onResume(timer.id) }
                        } else TimerAction("Restart", skin) { onReset(timer.id) }
                        TimerAction("Stop", skin, "Stop $total timer") { onDelete(timer.id) }
                    }
                }
            }
        }
        // Two short rows retain 48 dp targets at 384 dp and 1.3x text.
        TimerRules.PresetMinutes.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { minutes ->
                    TimerAction("$minutes min", skin, "Start ${TimerFormat.presetLabel(minutes)} timer", enabled = !book.isFull) { onPreset(minutes) }
                }
                if (row.size < 3) TimerAction("Custom", skin, "Start a custom timer", enabled = !book.isFull, click = onCustom)
            }
        }
        if (book.isFull) CellLabel("Five timers is the most. Stop one to add another.", skin)
    }
}

@Composable
private fun StopwatchTile(sw: Stopwatch, now: Long, skin: Skin, onToggle: () -> Unit, onLap: () -> Unit, onReset: () -> Unit) {
    val big = remember(skin) { hudValueStyle(skin).copy(fontSize = Metrics.hubValueSp.sp) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
        CellLabel("Stopwatch", skin)
        BasicText(TimerFormat.stopwatch(sw.elapsed(now)), style = big, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TimerAction(if (sw.running) "Stop" else if (sw.isPristine) "Start" else "Resume", skin,
                if (sw.running) "Stop stopwatch" else "Start stopwatch", click = onToggle)
            TimerAction("Lap", skin, "Record a lap", enabled = sw.running, click = onLap)
            TimerAction("Reset", skin, "Reset stopwatch", enabled = !sw.isPristine, click = onReset)
        }
        val shown = sw.laps.size.coerceAtMost(timerLapsShown(LocalHubSize.current))
        for (offset in 0 until shown) {
            val index = sw.laps.lastIndex - offset
            Row(Modifier.fillMaxWidth()) {
                BasicText(TimerFormat.lapName(index), Modifier.weight(1f), style = hudLabelStyle(skin))
                BasicText("+" + TimerFormat.stopwatch(sw.split(index)), Modifier.padding(end = Metrics.rowGapDp.dp), style = hudLabelStyle(skin))
                BasicText(TimerFormat.stopwatch(sw.laps[index]), style = hudValueStyle(skin))
            }
        }
        if (sw.laps.size > shown) CellLabel("+${sw.laps.size - shown} earlier laps", skin)
    }
}

@Composable
private fun TimerCountdownRing(fraction: Float, skin: Skin) {
    Box(Modifier.size(Metrics.touchTargetDp.dp).drawWithCache {
        val stroke = Stroke(Metrics.hudGaugeStrokeDp.dp.toPx())
        val base = Color(skin.colors.lineBase.toInt())
        val accent = Color(skin.colors.accent.toInt())
        val sweep = 360f * fraction
        onDrawBehind {
            drawArc(base, -90f, 360f, false, style = stroke)
            drawArc(accent, -90f, sweep, false, style = stroke)
        }
    })
}

@Composable
private fun TimerAction(text: String, skin: Skin, description: String = text, enabled: Boolean = true, click: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.touchTargetDp.dp).widthIn(min = Metrics.menuRowHeightDp.dp)
        .semantics { contentDescription = description }.clickable(enabled = enabled, onClick = click)
        .padding(horizontal = Metrics.lineGapDp.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = hudValueStyle(skin, if (enabled) skin.colors.accent else skin.colors.lineActive))
    }
}

/** Hours, minutes and seconds with plus and minus buttons; Start is disabled for a zero duration. */
@Composable
private fun CustomTimerDialog(skin: Skin, onDismiss: () -> Unit, onStart: (Long) -> Unit) {
    var hours by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(20) }
    var seconds by remember { mutableIntStateOf(0) }
    val ms = TimerRules.custom(hours, minutes, seconds)
    val zero = hours == 0 && minutes == 0 && seconds == 0
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Custom timer", style = settingsText(skin)) },
        text = { Column {
            Stepper("Hours", hours, 23, skin) { hours = it }
            Stepper("Minutes", minutes, 59, skin) { minutes = it }
            Stepper("Seconds", seconds, 59, skin) { seconds = it }
            BasicText(if (zero) "Pick a time" else TimerFormat.duration(ms), Modifier.padding(top = Metrics.sectionHeaderGapDp.dp),
                style = settingsText(skin, skin.colors.textSecondary, true))
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onStart(ms) }, enabled = !zero) {
            BasicText("Start", style = settingsText(skin, if (zero) skin.colors.lineActive else skin.colors.accent))
        } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) {
            BasicText("Cancel", style = settingsText(skin, skin.colors.textSecondary))
        } })
}

@Composable
private fun Stepper(label: String, value: Int, max: Int, skin: Skin, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f), style = settingsText(skin))
        TimerAction("−", skin, "Fewer $label".lowercase().replaceFirstChar { it.uppercase() }, enabled = value > 0) { change(value - 1) }
        BasicText("%02d".format(value), Modifier.widthIn(min = Metrics.menuRowHeightDp.dp), style = hudValueStyle(skin))
        TimerAction("+", skin, "More $label".lowercase().replaceFirstChar { it.uppercase() }, enabled = value < max) { change(value + 1) }
    }
}
