package com.brightblu.parc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brightblu.parc.core.data.agenda.*
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsSection
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Plain-text summaries for the Agenda row on the settings root. Pure. */
internal object AgendaSettingsText {
    fun days(days: Int): String = if (days == 1) "1 day" else "$days days"

    fun summary(settings: AgendaSettings): String {
        val left = settings.excluded.size
        return days(AgendaRules.clampDays(settings.days)) + if (left > 0) " · $left left out" else ""
    }
}

/** What the page last loaded, in one immutable value so the UI swaps it atomically. */
private data class AgendaLoad(val read: AgendaRead, val names: Map<Long, String>, val settings: AgendaSettings, val colors: Map<Long, Int>)

/** Calendar permission state plus the request, shared by the hub page and the settings page. */
internal class CalendarAccess(val granted: Boolean, val request: () -> Unit)

@Composable
internal fun rememberCalendarAccess(onChange: () -> Unit = {}): CalendarAccess {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(AndroidAgendaSource.granted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = AndroidAgendaSource.granted(context)
        if (!ok) runCatching {
            // After a permanent refusal the system shows no dialog; the app's own settings page is the way back.
            val activity = context as? android.app.Activity
            if (activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        }
        onChange()
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = AndroidAgendaSource.granted(context)
                if (now != granted) { granted = now; onChange() }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(granted) { CalendarAccess(granted) { runCatching { launcher.launch(Manifest.permission.READ_CALENDAR) } } }
}

/**
 * T100: the Agenda page. Events come from CalendarContract.Instances, read off the main thread when
 * the page opens and every 5 minutes while it is the visible one. The "in N min" text is refreshed
 * every 30 seconds from the clock without another query. Nothing runs in the background and
 * nothing is stored or sent anywhere.
 */
@Composable
internal fun AgendaHubPage(active: Boolean, tiles: List<String>, expanded: Boolean, skin: Skin) {
    val context = LocalContext.current
    val source = remember(context) { AndroidAgendaSource(context) }
    val prefs = remember(context) { ParcUiPrefs(context) }
    var tick by remember { mutableIntStateOf(0) }
    val access = rememberCalendarAccess { tick++ }
    var load by remember { mutableStateOf<AgendaLoad?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val zone = remember(tick) { ZoneId.systemDefault() }
    LaunchedEffect(active, access.granted, tick) {
        if (!active) return@LaunchedEffect
        var round = 0
        while (true) {
            if (round % RefreshEvery == 0) {
                load = withContext(Dispatchers.IO) { readAgenda(source, prefs, System.currentTimeMillis(), zone) }
            }
            now = System.currentTimeMillis()
            round++
            delay(ClockTickMs)
        }
    }
    val current = load
    val is24 = remember(tick, now / 60_000) { DateFormat.is24HourFormat(context) }
    val days = remember(current, now / 30_000, zone) {
        val data = (current?.read as? AgendaRead.Data)?.events
        if (current == null || data == null) null else {
            val kept = AgendaRules.filter(data, current.names, current.settings).take(AgendaRules.MaxEvents)
            AgendaRules.group(kept, now, zone, current.settings.days)
        }
    }
    val empty = AgendaRules.emptyLine(if (!access.granted) AgendaRead.NoPermission else current?.read, days)
    if (!access.granted) {
        HubPermissionCard("Agenda needs calendar access", "Allow", skin, allow = access.request)
        return
    }
    PageTiles(tiles, expanded, skin, centreSingleTile = empty != null) { _ ->
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            if (empty != null) {
                BasicText(if (days?.all { it.isEmpty } == true) hubTemplates.getValue("agenda").empty else empty, style = hudValueStyle(skin))
                if (!access.granted) AgendaAction("Allow calendar", skin, access.request)
            } else if (days != null && current != null) {
                val next = AgendaRules.next(days, now)
                next?.let { HubHero(AgendaRules.untilText(it.beginMs, now), AgendaRules.titleText(it), skin) }
                days.take(if (LocalHubSize.current == HubSize.Compact) 1 else if (LocalHubSize.current == HubSize.Standard) 2 else days.size).forEach { day ->
                    if (day.isEmpty) return@forEach
                    CellLabel(AgendaRules.dayLabel(day), skin)
                    day.allDay.forEach { e ->
                        AgendaRow("All day", e, current.colors[e.calendarId] ?: e.color, false, null, skin) { notice = openEvent(context, e) }
                    }
                    day.timed.take(if (LocalHubSize.current == HubSize.Compact) 1 else if (LocalHubSize.current == HubSize.Standard) 3 else day.timed.size).forEach { e ->
                        val isNext = e == next
                        AgendaRow(AgendaRules.timeRange(e, day.date, zone, is24), e, current.colors[e.calendarId] ?: e.color, isNext,
                            if (isNext) AgendaRules.untilText(e.beginMs, now) else null, skin) { notice = openEvent(context, e) }
                    }
                }
                if (next == null && days.any { !it.isEmpty }) CellLabel("Nothing more coming up", skin)
            }
            notice?.let { CellLabel(it, skin) }
        }
    }
}

private const val ClockTickMs = 30_000L
private const val RefreshEvery = 10 // 10 x 30 s = every 5 minutes

private fun readAgenda(source: AgendaSource, prefs: ParcUiPrefs, nowMs: Long, zone: ZoneId): AgendaLoad {
    val settings = try { prefs.agenda() } catch (e: Exception) { AgendaSettings() }
    val calendars = try { source.calendars().orEmpty() } catch (e: Exception) { emptyList() }
    val (from, to) = AgendaRules.window(nowMs, zone, settings.days)
    val read = try { source.events(from, to) } catch (e: Exception) { AgendaRead.Data(emptyList()) }
    return AgendaLoad(read, calendars.associate { it.id to it.name }, settings, calendars.associate { it.id to it.color })
}

/** Opens the event in the phone's calendar app; returns a short notice when nothing can show it. */
private fun openEvent(context: Context, event: AgendaEvent): String? {
    val uri = AndroidAgendaSource.eventUri(event.eventId) ?: return "Couldn't open the event"
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMs)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: Exception) {
        "No calendar app can open this event"
    }
}

@Composable
private fun AgendaRow(time: String, event: AgendaEvent, color: Int, next: Boolean, until: String?, skin: Skin, click: () -> Unit) {
    val dot = if (color != 0) Color(color) else Color(skin.colors.textSecondary.toInt())
    val tint = if (next) skin.colors.accent else skin.colors.textPrimary
    val title = AgendaRules.titleText(event)
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
        .semantics { contentDescription = "Open $title, $time${if (until != null) ", $until" else ""}" }
        .clickable(onClick = click), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp * 2)) {
        Box(Modifier.size(Metrics.hudDotDp.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                BasicText(time, Modifier.weight(1f), style = hudValueStyle(skin, if (next) skin.colors.accent else skin.colors.textSecondary), maxLines = 1)
                if (until != null) BasicText(until, style = hudValueStyle(skin, skin.colors.accent), maxLines = 1)
            }
            BasicText(title, style = hudValueStyle(skin, tint), maxLines = 2, overflow = TextOverflow.Ellipsis)
            event.location?.let { BasicText(it, style = hudLabelStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun AgendaAction(text: String, skin: Skin, click: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.touchTargetDp.dp).widthIn(min = Metrics.menuRowHeightDp.dp)
        .semantics { contentDescription = text }.clickable(onClick = click), contentAlignment = Alignment.CenterStart) {
        BasicText(text, style = hudValueStyle(skin, skin.colors.accent))
    }
}

/** Settings → Stats hub → Agenda: days ahead and which calendars to include. */
@Composable
internal fun AgendaSettingsContent(skin: Skin, settings: AgendaSettings, onChange: (AgendaSettings) -> Unit) {
    val context = LocalContext.current
    val source = remember(context) { AndroidAgendaSource(context) }
    var calendars by remember { mutableStateOf<List<AgendaCalendar>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val access = rememberCalendarAccess { tick++ }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(access.granted, tick) {
        calendars = if (!access.granted) null else withContext(Dispatchers.IO) { try { source.calendars() } catch (e: Exception) { null } }
    }
    if (picking) androidx.compose.material3.AlertDialog(
        onDismissRequest = { picking = false }, containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Days ahead", style = settingsText(skin)) },
        text = { Column {
            (AgendaRules.MinDays..AgendaRules.MaxDays).forEach { n ->
                SettingRow(AgendaSettingsText.days(n), skin, SettingKind.Action, icon = if (settings.days == n) "✓" else "○") {
                    onChange(settings.copy(days = n)); picking = false
                }
            }
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { picking = false }) {
            BasicText("Back", style = settingsText(skin, skin.colors.accent))
        } })
    val undo = rememberEditorUndo()
    val latestSettings by rememberUpdatedState(settings)
    EditorScaffold(EditorSpec.Agenda, skin, empty = calendars?.isEmpty() == true,
        undo = if (undo.action != null) ({ undo.run() }) else null,
        preview = { BasicText("Agenda preview: " + AgendaSettingsText.summary(settings), style = settingsText(skin, skin.colors.accent, true)) }) {
        BasicText("Shows today and the days after it from your phone's calendars. Events are read on the phone and never leave it. " +
            "Times follow the phone's 12 or 24 hour setting.",
            Modifier.padding(vertical = Metrics.gridDp.dp * 2), style = settingsText(skin, skin.colors.textSecondary, true))
        SettingsSection("Days", skin)
        SettingsCard(skin) {
            SettingRow("Days ahead", skin, SettingKind.Choice, "From 1 to 7 days, starting today",
                value = AgendaSettingsText.days(settings.days)) { picking = true }
        }
        SettingsSection("Calendars", skin)
        SettingsCard(skin) {
            val list = calendars
            when {
                !access.granted -> {
                    BasicText("Calendar access is off, so the Agenda page has nothing to show.",
                        Modifier.padding(Metrics.gridDp.dp * 4), style = settingsText(skin, skin.colors.textSecondary, true))
                    SettingRow("Allow calendar", skin, SettingKind.Action, icon = "›") { access.request() }
                }
                list == null -> BasicText("Loading", Modifier.padding(Metrics.gridDp.dp * 4), style = settingsText(skin, skin.colors.textSecondary, true))
                list.isEmpty() -> BasicText("No calendars on this phone",
                    Modifier.padding(Metrics.gridDp.dp * 4), style = settingsText(skin, skin.colors.textSecondary, true))
                else -> list.forEach { cal ->
                    val included = settings.includes(cal)
                    SettingRow(cal.name, skin, SettingKind.Toggle, checked = included) {
                        onChange(settings.withCalendar(cal.name, !included))
                        if (included) undo.offer { onChange(latestSettings.withCalendar(cal.name, true)) }
                    }
                }
            }
        }
    }
}
