package com.brightblu.parc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.*
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
internal fun rememberHubTime(): String {
    val format = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var time by remember { mutableStateOf(LocalTime.now().format(format)) }
    LaunchedEffect(Unit) {
        while (true) {
            time = LocalTime.now().format(format)
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    return time
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClockHubPage(skin: Skin, look: LookSettings, time: String, detail: ClockDetail, visible: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val config by store.config.collectAsState(initial = HubConfig())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var alarm by remember { mutableStateOf<Long?>(null) }
    var ownAlarm by remember { mutableStateOf(false) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var optionsOpen by remember { mutableStateOf(false) }
    if (optionsOpen) HubPageOptionsSheet("clock", skin, config, { change -> scope.launch { store.update(change) } }, { optionsOpen = false }, LocalHubSettings.current)
    LaunchedEffect(config.clock.seconds, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                val next = if (DemoMode.enabled) null else context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock
                ownAlarm = next?.showIntent?.creatorPackage == context.packageName
                alarm = next?.takeUnless { ownAlarm }?.triggerTime
                delay(if (config.clock.seconds) 1000L else 60_000L - now % 60_000L)
            }
        }
    }
    val zone = java.time.ZoneId.systemDefault()
    val shownTime = ClockDisplay.time(now, zone, config.clock)
    Box(Modifier.fillMaxSize().combinedClickable(onLongClick = { optionsOpen = true }, onClick = {
        runCatching { context.startActivity(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
    }), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberHubScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            if (visible && look.clockStyle != ClockStyle.Hidden) {
                if (look.clockStyle == ClockStyle.Digital && SevenSegment.supports(shownTime) && !config.clock.seconds) DigitalHubClock(shownTime, skin)
                else BasicText(shownTime, style = TextStyle(color = Color((if (skin.style.header == HeaderStyle.ruled) skin.colors.accent else skin.colors.textPrimary).toInt()),
                    letterSpacing = skin.typography.clock.trackingSp.sp,
                    fontSize = (if (LocalHubSize.current == HubSize.Compact) Metrics.hubValueSp else if (config.clock.seconds || !config.clock.hour24) Metrics.hubValueSp else Metrics.hubClockSp).sp,
                    fontWeight = FontWeight(hubClockWeight(look.clockStyle, skin)),
                    fontFamily = if (look.clockStyle == ClockStyle.Mono) FontFamily.Monospace
                        else FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create(
                            android.graphics.Typeface.create(skin.style.clockFamily.ifEmpty { skin.typography.fallbackFamily }, android.graphics.Typeface.NORMAL),
                            hubClockWeight(look.clockStyle, skin), false)))))
            }
            if (LocalHubSize.current != HubSize.Compact) {
                BasicText(ClockDisplay.date(now, zone), style = hudValueStyle(skin))
                BasicText("Week ${ClockDisplay.week(now, zone)}", style = hudLabelStyle(skin))
                BasicText(alarm?.let {
                    "Alarm ${ClockDisplay.time(it, zone, config.clock.copy(seconds = false))} · ${ClockDisplay.until(it, now)}"
                } ?: if (ownAlarm) "Other alarms hidden while a timer runs" else "No upcoming alarm", style = hudValueStyle(skin))
                if (config.clock.zone.isNotEmpty()) HudCell(skin, Modifier.fillMaxWidth()) {
                    Column {
                        BasicText(ClockDisplay.time(now, java.time.ZoneId.of(config.clock.zone), config.clock.copy(seconds = false)), style = hudValueStyle(skin))
                        BasicText(config.clock.zone.replace('_', ' '), style = hudLabelStyle(skin))
                        BasicText(ClockDisplay.date(now, java.time.ZoneId.of(config.clock.zone)), style = hudLabelStyle(skin))
                    }
                }
            }
            if (LocalHubSize.current != HubSize.Compact && detail.focusLine.isNotBlank()) {
                val state = LocalFocusRepository.current?.state?.collectAsState()?.value
                state?.active?.let { FocusModeChip(it, skin, detail.focusLine) }
                    ?: BasicText(detail.focusLine, style = hudValueStyle(skin, skin.colors.accent))
            }
        }
    }
}

/** Segment paths are built when size/time changes, never inside a draw callback. */
@Composable
private fun DigitalHubClock(time: String, skin: Skin) {
    val h = skin.typography.clock.sizeSp * Metrics.digitalClockHeightRatio * androidx.compose.ui.platform.LocalDensity.current.fontScale
    val width = time.sumOf { (if (it == ':') Metrics.digitalClockColonWidthRatio else Metrics.digitalClockDigitWidthRatio).toDouble() }.toFloat() * h + (time.length - 1) * h * Metrics.digitalClockGapRatio
    Box(Modifier.size(width.dp, h.dp).semantics { contentDescription = time }.drawWithCache {
        val path = Path()
        val height = size.height
        val stroke = height * Metrics.digitalClockStrokeRatio
        val digit = height * Metrics.digitalClockDigitWidthRatio
        val colon = height * Metrics.digitalClockColonWidthRatio
        var x = 0f
        time.forEach { ch ->
            if (ch == ':') {
                val dot = stroke * Metrics.digitalClockDotRadiusRatio
                for (y in listOf(height * .3f, height * .7f)) path.addOval(androidx.compose.ui.geometry.Rect(x + colon / 2 - dot, y - dot, x + colon / 2 + dot, y + dot))
                x += colon
            } else {
                SevenSegment.segmentsFor(ch)?.forEach { segment ->
                    val r = when (segment) {
                        ClockSegment.Top -> androidx.compose.ui.geometry.Rect(x + stroke / 2, 0f, x + digit - stroke / 2, stroke)
                        ClockSegment.Middle -> androidx.compose.ui.geometry.Rect(x + stroke / 2, height / 2 - stroke / 2, x + digit - stroke / 2, height / 2 + stroke / 2)
                        ClockSegment.Bottom -> androidx.compose.ui.geometry.Rect(x + stroke / 2, height - stroke, x + digit - stroke / 2, height)
                        ClockSegment.UpperLeft -> androidx.compose.ui.geometry.Rect(x, stroke / 2, x + stroke, height / 2)
                        ClockSegment.LowerLeft -> androidx.compose.ui.geometry.Rect(x, height / 2, x + stroke, height - stroke / 2)
                        ClockSegment.UpperRight -> androidx.compose.ui.geometry.Rect(x + digit - stroke, stroke / 2, x + digit, height / 2)
                        ClockSegment.LowerRight -> androidx.compose.ui.geometry.Rect(x + digit - stroke, height / 2, x + digit, height - stroke / 2)
                    }
                    path.addRoundRect(androidx.compose.ui.geometry.RoundRect(r, androidx.compose.ui.geometry.CornerRadius(stroke / 2)))
                }
                x += digit
            }
            x += height * Metrics.digitalClockGapRatio
        }
        val color = Color(skin.colors.textPrimary.toInt())
        onDrawBehind { drawPath(path, color) }
    })
}
