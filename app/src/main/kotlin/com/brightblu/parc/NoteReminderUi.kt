package com.brightblu.parc

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.timers.TimerRules
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import java.time.ZoneId

/** T183: which of the note's reminder and timer dialogs is open. */
internal enum class NoteSheet { None, Choose, Remind, Pick, Timer }

/** A small bell drawn from lines, so no emoji and no icon library. */
@Composable
internal fun NoteBell(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(Metrics.noteBellDp.dp)) {
        val w = size.width; val h = size.height
        val stroke = Stroke(Metrics.noteBellStrokeDp.dp.toPx(), cap = StrokeCap.Round)
        val body = Path().apply {
            moveTo(w * 0.2f, h * 0.75f)
            lineTo(w * 0.2f, h * 0.45f)
            cubicTo(w * 0.2f, h * 0.12f, w * 0.8f, h * 0.12f, w * 0.8f, h * 0.45f)
            lineTo(w * 0.8f, h * 0.75f)
        }
        drawPath(body, color, style = stroke)
        drawLine(color, Offset(w * 0.1f, h * 0.75f), Offset(w * 0.9f, h * 0.75f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w * 0.42f, h * 0.88f), Offset(w * 0.58f, h * 0.88f), stroke.width, StrokeCap.Round)
    }
}

/** The bell button in the note header. */
@Composable
internal fun NoteBellButton(skin: Skin, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.menuRowHeightDp.dp).widthIn(min = Metrics.menuRowHeightDp.dp)
        .semantics { contentDescription = "Remind me or start a timer" }.clickable(onClick = onClick)
        .padding(Metrics.lineGapDp.dp), contentAlignment = Alignment.Center) {
        NoteBell(Color(skin.colors.accent.toInt()))
    }
}

@Composable
private fun Choice(text: String, skin: Skin, description: String = text, click: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp).semantics { contentDescription = description }
        .clickable(onClick = click), contentAlignment = Alignment.CenterStart) {
        BasicText(text, style = settingsText(skin, skin.colors.accent))
    }
}

@Composable
private fun CancelButton(skin: Skin, onClick: () -> Unit) {
    TextButton(onClick = onClick) { BasicText("Cancel", style = settingsText(skin, skin.colors.textSecondary)) }
}

/**
 * The dialogs behind the bell: the two choices, the reminder times, the date and time picker and the timer
 * duration picker. Writes go through [store] and [TimerEngine]; the note's text is flushed first so the
 * stored note exists before its reminder is set.
 */
@Composable
internal fun NoteReminderSheets(
    sheet: NoteSheet, setSheet: (NoteSheet) -> Unit, skin: Skin, note: Note, remindAt: Long,
    store: NotesStore, flushText: () -> Unit, notice: (String) -> Unit,
) {
    if (sheet == NoteSheet.None) return
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val is24h = remember(context) { DateFormat.is24HourFormat(context) }
    val exact = remember(context) { TimerScheduler.canScheduleExact(context) }
    val timersStore = remember(context) { TimersStore(context) }
    val timers by timersStore.state.collectAsState(initial = null)
    val asked by timersStore.asked.collectAsState(initial = true)
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askIfNeeded() {
        if (TimerRules.shouldAskNotifications(TimerNotifier.notificationsAllowed(context), asked)) {
            TimerEngine.launchMarkAsked(context)
            runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
    val now = remember(sheet) { System.currentTimeMillis() }
    fun setReminder(at: Long) {
        flushText(); store.setReminder(note.id, at); setSheet(NoteSheet.None)
        if (at > 0) { askIfNeeded(); notice(NoteReminderRules.label(at, now, zone, is24h, exact)) } else notice("Reminder cleared")
    }
    fun near(at: Long) = (if (exact) "" else "around ") + NoteReminderRules.whenText(at, now, zone, is24h)
    val close = { setSheet(NoteSheet.None) }
    when (sheet) {
        NoteSheet.Choose -> AlertDialog(onDismissRequest = close, containerColor = Color(skin.colors.surface.toInt()),
            title = { BasicText("This note", style = settingsText(skin)) },
            text = { Column {
                Choice("Remind me", skin) { setSheet(NoteSheet.Remind) }
                Choice("Start a timer", skin) { setSheet(NoteSheet.Timer) }
            } },
            confirmButton = {}, dismissButton = { CancelButton(skin, close) })
        NoteSheet.Remind -> AlertDialog(onDismissRequest = close, containerColor = Color(skin.colors.surface.toInt()),
            title = { BasicText("Remind me", style = settingsText(skin)) },
            text = { Column {
                val hour = NoteReminderRules.inOneHour(now)
                val evening = NoteReminderRules.thisEvening(now, zone)
                val morning = NoteReminderRules.tomorrowMorning(now, zone)
                Choice("In 1 hour · ${near(hour)}", skin, "In 1 hour") { setReminder(hour) }
                Choice("This evening · ${near(evening)}", skin, "This evening") { setReminder(evening) }
                Choice("Tomorrow morning · ${near(morning)}", skin, "Tomorrow morning") { setReminder(morning) }
                Choice("Pick date and time", skin) { setSheet(NoteSheet.Pick) }
                if (remindAt > 0) Choice("Clear reminder", skin) { setReminder(0) }
                if (!exact) BasicText("Exact alarms are not available here, so a reminder may come a little late.",
                    Modifier.padding(top = Metrics.sectionHeaderGapDp.dp), style = settingsText(skin, skin.colors.textSecondary, true))
            } },
            confirmButton = {}, dismissButton = { CancelButton(skin, close) })
        NoteSheet.Pick -> {
            val start = remember { NoteReminderRules.pickerStart(now, zone) }
            var day by remember { mutableIntStateOf(start.first) }
            var hour by remember { mutableIntStateOf(start.second) }
            var minute by remember { mutableIntStateOf(start.third) }
            val at = NoteReminderRules.fromPicker(day, hour, minute, now, zone)
            AlertDialog(onDismissRequest = close, containerColor = Color(skin.colors.surface.toInt()),
                title = { BasicText("Pick date and time", style = settingsText(skin)) },
                text = { Column {
                    Stepper("Day", day, NoteReminderRules.MaxDaysAhead, skin, text = NoteReminderRules.dayText(day, now, zone)) { day = it }
                    Stepper("Hour", hour, 23, skin) { hour = it }
                    Stepper("Minute", minute, 55, skin, step = NoteReminderRules.MinuteStep) { minute = it }
                    BasicText(if (at == null) "Pick a time in the future" else near(at), Modifier.padding(top = Metrics.sectionHeaderGapDp.dp),
                        style = settingsText(skin, skin.colors.textSecondary, true))
                } },
                confirmButton = { TextButton(onClick = { at?.let(::setReminder) }, enabled = at != null) {
                    BasicText("Set", style = settingsText(skin, if (at == null) skin.colors.lineActive else skin.colors.accent))
                } },
                dismissButton = { CancelButton(skin, close) })
        }
        NoteSheet.Timer -> {
            val full = timers?.book?.isFull == true
            if (full) AlertDialog(onDismissRequest = close, containerColor = Color(skin.colors.surface.toInt()),
                title = { BasicText("Five timers is the most", style = settingsText(skin)) },
                text = { BasicText("Stop one on the Timers page to start another.", style = settingsText(skin, skin.colors.textSecondary, true)) },
                confirmButton = { TextButton(onClick = close) { BasicText("OK", style = settingsText(skin, skin.colors.accent)) } })
            else CustomTimerDialog(skin, onDismiss = close, title = "Timer for this note") { ms ->
                val label = NoteTitles.raw(note)
                TimerEngine.launch(context) { s, at -> s.book.start(ms, at, label, note.id)?.let { s.copy(book = it) } ?: s }
                askIfNeeded()
                notice("Timer started: " + com.brightblu.parc.core.data.timers.TimerFormat.duration(ms))
                close()
            }
        }
        NoteSheet.None -> Unit
    }
}
