package com.brightblu.parc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.DayLedgerRepository
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.rhythm.RhythmBins
import com.brightblu.parc.core.data.rhythm.RhythmMath
import com.brightblu.parc.core.data.rhythm.RhythmOptions
import com.brightblu.parc.core.data.rhythm.RhythmRepository
import com.brightblu.parc.core.data.rhythm.RhythmShown
import com.brightblu.parc.core.data.rhythm.RhythmView
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.parcRowPadding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** T188: the Rhythm page id. Lowercase and stable, because it is part of the saved hub format. */
internal object RhythmPage {
    const val Id = "rhythm"
}

/** The usage-event reader, provided where the hub is drawn. Null in previews and in demo mode (the demo page draws fixtures). */
internal val LocalRhythmRepository = staticCompositionLocalOf<RhythmRepository?> { null }

/** Words for the page, kept out of the composables so tests can read them. */
internal object RhythmPresentation {
    fun ringDescription(shown: RhythmShown, today: LocalDate): String {
        val bins = shown.bins ?: return "Day ring for ${RhythmView.dateLabel(shown.date, today)}. Not recorded"
        return "Day ring for ${RhythmView.dateLabel(shown.date, today)}, midnight at the top. " + RhythmView.runsDescription(bins)
    }

    /** The line under the date: what a lit stretch of the ring means. */
    const val Legend = "Lit = screen on"
    const val CentreCaption = "screen on"
    const val NotRecorded = "Nothing recorded for this day"
    val HourLabels = listOf("00", "06", "12", "18")

    /** `Today · Tue 10 Nov`, `Yesterday · Mon 9 Nov`, or `Sun 8 Nov`. */
    fun dateLine(date: LocalDate, today: LocalDate): String {
        val plain = RhythmView.dateLabelPlain(date)
        val word = RhythmView.dateLabel(date, today)
        return if (word == plain) plain else "$word · $plain"
    }

    /** The key number inside the ring: `3h 12m`, `45m`, or a dash when nothing is recorded. */
    fun centreNumber(screenOnMillis: Long?): String {
        if (screenOnMillis == null) return "—"
        val minutes = screenOnMillis / 60_000L
        return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${String.format(java.util.Locale.ROOT, "%02d", minutes % 60)}m"
    }

    fun windowText(options: RhythmOptions): String = hour(options.startHour) + " to " + hour(options.endHour)

    /** `06:00`; hour 24 reads `24:00` (the clock helper would wrap it to 00:00). */
    private fun hour(h: Int): String = String.format(java.util.Locale.ROOT, "%02d:00", h)

    fun numbersDescription(shown: RhythmShown, options: RhythmOptions): String = buildString {
        append("First pickup ").append(shown.firstPickup ?: "none")
        append(". Pickups ").append(shown.pickups?.toString() ?: "none")
        append(". Screen on ").append(RhythmMath.duration(shown.screenOnMillis))
        append(". Longest phone-free stretch ").append(RhythmMath.duration(shown.longestFreeMillis)).append(" between ").append(windowText(options).replace(" to ", " and "))
    }
}

/**
 * T188: the Rhythm page. The data comes from [RhythmRepository] (usage events into the day ledger); the page
 * only refreshes and draws. Without usage access it shows the same "Grant usage access" row as the Apps page.
 */
@Composable
internal fun RhythmHubPage(ledger: DayLedgerRepository?, skin: Skin, active: Boolean) {
    val context = LocalContext.current
    val rhythm = LocalRhythmRepository.current
    val store = remember(context) { HubConfigStore(context) }
    LaunchedEffect(ledger, rhythm, active) {
        if (active) while (true) {
            ledger?.refresh()
            rhythm?.sample()
            delay(Metrics.hubNetworkRefreshMillis)
        }
    }
    val stored = ledger?.ledger?.collectAsState()?.value ?: DayLedger.Empty
    val options = store.rhythmOptions.collectAsState(initial = RhythmOptions()).value
    val ledgerAccess = ledger?.accessGranted?.collectAsState()?.value
    val rhythmAccess = rhythm?.accessGranted?.collectAsState()?.value
    val denied = ledgerAccess == false || rhythmAccess == false
    val zone = ZoneId.systemDefault()
    val today = ledger?.today() ?: LocalDate.now()
    var chosen by remember { mutableStateOf<LocalDate?>(null) }
    // The viewed day moves to the new today after midnight, and never rests on a date after today.
    val date = chosen?.takeIf { !it.isAfter(today) } ?: today
    val nowMillis = System.currentTimeMillis()
    val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val shown = remember(stored, date, today, nowMillis / 60_000L) { RhythmView.show(stored, date, today, nowMillis - dayStart, dayEnd - dayStart) }
    Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        if (denied) HudCell(skin, Modifier.fillMaxWidth()) {
            Column {
                BasicText(hubTemplates.getValue(RhythmPage.Id).empty, style = hudValueStyle(skin))
                UsageAccessRow(skin)
            }
        }
        RhythmContent(shown, options, today, skin, onPrevious = { chosen = shown.previous }, onNext = { chosen = shown.next })
    }
}

/**
 * The drawing. The day switch (arrows and a date) with a one-line legend, the 24-hour ring with its hour labels and the
 * screen-on total in the middle, and the facts. How big the ring is comes from [RhythmLayout]: from the height the hub
 * page really has, so the ring and its first facts fit Standard without scrolling. Stateless.
 */
@Composable
internal fun RhythmContent(shown: RhythmShown, options: RhythmOptions, today: LocalDate, skin: Skin, onPrevious: () -> Unit, onNext: () -> Unit) {
    val gap = Metrics.hudCellGapDp.dp
    val height = LocalHubContentHeight.current
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val plan = RhythmLayout.plan(LocalHubSize.current, height, maxWidth.value, fontScale)
        val dateBlock: @Composable (Modifier) -> Unit = { m -> DateAndLegend(shown, today, skin, m) }
        val facts = Modifier.semantics(mergeDescendants = true) { contentDescription = RhythmPresentation.numbersDescription(shown, options) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            when (plan.placement) {
                RhythmLayout.Placement.CompactBeside -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DayArrow("‹", "Earlier day", shown.previous != null, skin, onPrevious)
                    RhythmRing(shown, today, plan, skin)
                    dateBlock(Modifier.weight(1f).padding(start = gap))
                    DayArrow("›", "Later day", shown.next != null, skin, onNext)
                }
                else -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DayArrow("‹", "Earlier day", shown.previous != null, skin, onPrevious)
                        dateBlock(Modifier.weight(1f))
                        DayArrow("›", "Later day", shown.next != null, skin, onNext)
                    }
                    if (plan.placement == RhythmLayout.Placement.FactsBeside) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                            RhythmRing(shown, today, plan, skin)
                            Column(facts.weight(1f)) {
                                val rows = listOf(
                                    Triple("First pickup", shown.firstPickup ?: "—", null as String?),
                                    Triple("Pickups", shown.pickups?.toString() ?: "—", null),
                                    Triple("Longest phone-free", shown.longestFreeMillis?.let { RhythmMath.duration(it) } ?: "—", RhythmPresentation.windowText(options)),
                                )
                                rows.forEachIndexed { index, (label, value, note) ->
                                    HubStatRow(label, value, skin, side = note, sideAsCaption = true, modifier = Modifier.heightIn(min = 0.dp))
                                    if (index < rows.lastIndex) HubDivider(skin)
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RhythmRing(shown, today, plan, skin) }
                        Row(facts.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            Box(Modifier.weight(1f)) { Fact("First pickup", shown.firstPickup ?: "—", skin) }
                            Box(Modifier.weight(1f)) { Fact("Pickups", shown.pickups?.toString() ?: "—", skin) }
                            Box(Modifier.weight(1f)) {
                                Fact("Longest phone-free", shown.longestFreeMillis?.let { RhythmMath.duration(it) } ?: "—", skin, RhythmPresentation.windowText(options))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The date of the day shown, and under it what a lit stretch means (or that nothing was recorded). */
@Composable
private fun DateAndLegend(shown: RhythmShown, today: LocalDate, skin: Skin, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(RhythmPresentation.dateLine(shown.date, today), maxLines = 1, style = hubTitleStyle(skin))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
            if (shown.recorded) {
                val lit = Color(skin.colors.accent.toInt())
                Canvas(Modifier.size(Metrics.rhythmSwatchDp.dp)) { drawCircle(lit) }
            }
            BasicText(if (shown.recorded) RhythmPresentation.Legend else RhythmPresentation.NotRecorded, style = hubCaptionStyle(skin))
        }
    }
}

/** A fact under the ring (Tall): the label small and muted, the figure large, an optional note under it. */
@Composable
private fun Fact(label: String, value: String, skin: Skin, note: String? = null) {
    Column {
        BasicText(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = hubCaptionStyle(skin))
        val (number, unit) = remember(value) { HubFigure.split(value) }
        Row(verticalAlignment = Alignment.Bottom) {
            DotNumerals(number, DotRole.countDot, skin.typography, Color(skin.colors.textPrimary.toInt()), Modifier.alignByBaseline(),
                useDot = isAllDot(number) && skin.typography.numeralFamily == "Doto")
            if (unit != null) BasicText(" $unit", Modifier.alignByBaseline(), maxLines = 1, style = hubCaptionStyle(skin))
        }
        if (note != null) BasicText(note, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = hubCaptionStyle(skin))
    }
}

@Composable
private fun DayArrow(glyph: String, label: String, enabled: Boolean, skin: Skin, onClick: () -> Unit) {
    val min = Metrics.minTouchDp.dp
    Box(
        Modifier.size(min).semantics { contentDescription = label; role = Role.Button }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) BasicText(glyph, style = hudValueStyle(skin, skin.colors.accent))
    }
}

/** The ring with its hour labels around it and the screen-on total in the hole. */
@Composable
private fun RhythmRing(shown: RhythmShown, today: LocalDate, plan: RhythmLayout.Plan, skin: Skin) {
    val labelStyle = hubCaptionStyle(skin)
    val centre = RhythmPresentation.centreNumber(shown.screenOnMillis)
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    Box(Modifier.size(plan.boxDp.dp), contentAlignment = Alignment.Center) {
        DayRing(shown, today, plan.ringDp, plan.strokeDp, skin)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The number is sized from the hole, so it can never touch the ring (1.5.1).
            BasicText(centre, maxLines = 1, softWrap = false, style = hudValueStyle(skin).copy(
                fontSize = RhythmLayout.centreSp(plan.ringDp, plan.strokeDp, centre.length, fontScale).sp, fontWeight = FontWeight.SemiBold))
            if (plan.placement != RhythmLayout.Placement.CompactBeside) BasicText(RhythmPresentation.CentreCaption, style = labelStyle)
        }
        if (plan.hourLabels) {
            BasicText("00", Modifier.align(Alignment.TopCenter), style = labelStyle)
            BasicText("06", Modifier.align(Alignment.CenterEnd), style = labelStyle)
            BasicText("12", Modifier.align(Alignment.BottomCenter), style = labelStyle)
            BasicText("18", Modifier.align(Alignment.CenterStart), style = labelStyle)
        }
    }
}

/**
 * The 24-hour ring in one Canvas: midnight at the top, clockwise. The faint track is the whole day, the accent
 * arcs are the stretches with the screen on, four small ticks mark 00, 06, 12 and 18, and a dot marks now on today.
 */
@Composable
private fun DayRing(shown: RhythmShown, today: LocalDate, sizeDp: Float, strokeDp: Float, skin: Skin) {
    val track = Color(skin.colors.lineBase.toInt())
    val lit = Color(skin.colors.accent.toInt())
    val mark = Color(skin.colors.textPrimary.toInt())
    val tick = Color(skin.colors.textSecondary.toInt()).copy(alpha = Metrics.rhythmTickAlpha)
    Canvas(Modifier.size(sizeDp.dp).semantics { contentDescription = RhythmPresentation.ringDescription(shown, today) }) {
        val stroke = strokeDp.dp.toPx()
        val inset = stroke / 2f
        val box = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
        val perBin = 360f / RhythmBins.Count
        shown.bins?.let { bins ->
            for (run in RhythmBins.runs(bins)) {
                drawArc(lit, -90f + run.first * perBin, (run.last + 1 - run.first) * perBin, false, Offset(inset, inset), box, style = Stroke(stroke))
            }
        }
        val c = Offset(size.width / 2f, size.height / 2f)
        val outer = size.width / 2f
        val length = Metrics.rhythmTickDp.dp.toPx()
        for (q in 0 until 4) {
            val a = Math.toRadians(-90.0 + q * 90.0)
            val from = outer - stroke - length * 0.4f
            drawLine(tick, Offset(c.x + (from * cos(a)).toFloat(), c.y + (from * sin(a)).toFloat()),
                Offset(c.x + ((from - length) * cos(a)).toFloat(), c.y + ((from - length) * sin(a)).toFloat()), 1.dp.toPx())
        }
        shown.nowFraction?.let { f ->
            val a = Math.toRadians(-90.0 + 360.0 * f)
            val r = outer - stroke / 2f
            drawCircle(mark, Metrics.rhythmNowDotDp.dp.toPx(), Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()))
        }
    }
}

/** The demo page: a made-up day, no usage events, no ledger, no permission. */
@Composable
internal fun DemoRhythmPage(skin: Skin) {
    val today = LocalDate.now()
    val shown = remember(today) {
        // Four stretches: morning, lunch, afternoon, a long evening.
        val bins = BooleanArray(RhythmBins.Count)
        fun lit(fromMinute: Int, toMinute: Int) { for (i in fromMinute / 5 until toMinute / 5) bins[i] = true }
        lit(7 * 60 + 25, 7 * 60 + 50); lit(12 * 60 + 40, 13 * 60 + 5); lit(16 * 60 + 15, 16 * 60 + 40); lit(19 * 60 + 30, 21 * 60 + 15)
        val ledger = DayLedger.Empty.withDay(LedgerDay(today, screenOnMillis = 3L * 3_600_000L + 12L * 60_000L, pickups = 41, firstPickupMinute = 7 * 60 + 25,
            phoneFreeMillis = 3L * 3_600_000L + 35L * 60_000L, rhythmBins = RhythmBins.encode(bins)))
        RhythmView.show(ledger, today, today, 14L * 3_600_000L, 24L * 3_600_000L)
    }
    Column(Modifier.fillMaxWidth().hubPageScroll()) {
        RhythmContent(shown, RhythmOptions(), today, skin, {}, {})
    }
}

/** Page options: the hours between which the longest phone-free stretch is looked for. */
@Composable
internal fun RhythmOptionChoices(skin: Skin) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val scope = rememberCoroutineScope()
    val options by store.rhythmOptions.collectAsState(initial = RhythmOptions())
    fun change(f: (RhythmOptions) -> RhythmOptions) { scope.launch { store.setRhythmOptions(f) } }
    val start = HubOptionNumbers.rhythmStart(options.endHour)
    val end = HubOptionNumbers.rhythmEnd(options.startHour)
    SettingsCard(skin) {
        BasicText("The longest phone-free stretch is looked for inside this window, so sleep does not win every day.",
            Modifier.parcRowPadding(), style = settingsText(skin, skin.colors.textSecondary, true))
        HubOptionStepper("Start", HubOptionNumbers.hourText(options.startHour), skin,
            canDecrease = start.canGoDown(options.startHour), canIncrease = start.canGoUp(options.startHour),
            onDecrease = { change { it.withStart(start.down(it.startHour)) } },
            onIncrease = { change { it.withStart(start.up(it.startHour)) } })
        HubOptionStepper("End", HubOptionNumbers.hourText(options.endHour), skin,
            canDecrease = end.canGoDown(options.endHour), canIncrease = end.canGoUp(options.endHour),
            divider = false,
            onDecrease = { change { it.withEnd(end.down(it.endHour)) } },
            onIncrease = { change { it.withEnd(end.up(it.endHour)) } })
    }
}
