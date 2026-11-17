package com.brightblu.parc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.DayLedgerRepository
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.parcRowPadding
import java.time.LocalDate
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** T190: the Year page id. Lowercase and stable, because it is part of the saved hub format. */
internal object YearPage {
    const val Id = "year"
}

/**
 * T190: the Year page. The numbers come from the day ledger (read only here); the dates to count down to and the
 * choice of colouring are stored on the phone in the hub store and travel in the backup.
 */
@Composable
internal fun YearHubPage(ledger: DayLedgerRepository?, skin: Skin, active: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ledger, active) {
        if (active) while (true) {
            ledger?.refresh()
            delay(Metrics.hubNetworkRefreshMillis)
        }
    }
    val stored = ledger?.ledger?.collectAsState()?.value ?: DayLedger.Empty
    val countdowns = store.yearCountdowns.collectAsState(initial = emptyList()).value
    val metric = store.yearMetric.collectAsState(initial = YearMetric.None).value
    val colour = store.yearColour.collectAsState(initial = YearColour.Accent).value
    val today = ledger?.today() ?: LocalDate.now()
    YearContent(stored, countdowns, metric, colour, today, skin,
        onCycle = { scope.launch { store.setYearMetric(metric.next()) } },
        onClearPassed = { scope.launch { store.setYearCountdowns { Countdowns.withoutPassed(it, today) } } })
}

/** The drawing: progress line, colour label, the grid, the picked day, and the countdown list. */
@Composable
internal fun YearContent(ledger: DayLedger, countdowns: List<Countdown>, metric: YearMetric, colour: YearColour, today: LocalDate, skin: Skin,
    onCycle: () -> Unit, onClearPassed: () -> Unit) {
    val gap = Metrics.hudCellGapDp.dp
    var picked by remember(today) { mutableStateOf<LocalDate?>(null) }
    val shown = picked ?: today
    val levels = remember(ledger, metric, today.year) {
        YearMath.levels(buildMap {
            for (day in ledger.range(LocalDate.of(today.year, 1, 1), LocalDate.of(today.year, 12, 31))) {
                metric.value(day)?.let { put(day.date, it) }
            }
        })
    }
    val marks = remember(countdowns, today.year) { Countdowns.marks(countdowns, today.year) }
    Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(gap)) {
        HudCell(skin, Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                BasicText(YearMath.progressLine(today), style = hudValueStyle(skin))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
                        .semantics { contentDescription = "Past days shaded by: ${metric.label}. Tap to change."; role = Role.Button }
                        .clickable(onClick = onCycle),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText("Shade by · ${metric.label}", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())), maxLines = 1)
                }
                YearGridView(today, levels, marks, picked, skin, metric, colour) { picked = if (it == picked) null else it }
                YearLegend(skin, colour)
                BasicText(YearMath.dayLine(shown, ledger.day(shown)), style = hudLabelStyle(skin))
            }
        }
        CountdownList(countdowns, today, skin, onClearPassed)
    }
}

@Composable
private fun YearGridView(today: LocalDate, levels: Map<LocalDate, Int>, marks: Set<LocalDate>, picked: LocalDate?, skin: Skin,
    metric: YearMetric, colour: YearColour, onPick: (LocalDate) -> Unit) {
    val rowDp = when (LocalHubSize.current) {
        HubSize.Compact -> Metrics.yearRowCompactDp
        HubSize.Standard -> Metrics.yearRowStandardDp
        HubSize.Tall -> Metrics.yearRowTallDp
    }.dp
    val year = today.year
    val strong = Color(skin.colors.textPrimary.toInt())
    val accent = Color(skin.colors.accent.toInt())
    val steps = remember(colour, skin.colors) { YearPalette.steps(colour, skin.colors).map { Color(it.toInt()) } }
    val ringColour = Color(YearPalette.ring(skin.colors).toInt())
    val description = "Year grid, one dot for each day. ${YearMath.progressLine(today)}. Days with a number are solid, empty days are outlined. Shaded by ${metric.label.lowercase()}."
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(Metrics.yearMonthLabelWidthDp.dp).clearAndSetSemantics {}) {
            "JFMAMJJASOND".forEach {
                Box(Modifier.height(rowDp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    BasicText(it.toString(), style = hudLabelStyle(skin).copy(fontSize = Metrics.yearMonthLabelSp.sp, lineHeight = Metrics.yearMonthLabelSp.sp), maxLines = 1)
                }
            }
        }
        Canvas(
            Modifier.weight(1f).height(rowDp * YearGrid.Rows)
                .semantics { contentDescription = description }
                .pointerInput(year) {
                    detectTapGestures { at ->
                        YearGrid.dateAtPoint(year, at.x, at.y, size.width.toFloat(), size.height.toFloat())?.let(onPick)
                    }
                },
        ) {
            val cellW = size.width / YearGrid.Columns
            val cellH = size.height / YearGrid.Rows
            val radius = min(cellW, cellH) * Metrics.yearDotFill / 2f
            val ring = Metrics.yearRingDp.dp.toPx()
            for (row in 0 until YearGrid.Rows) for (col in 0 until YearGrid.Columns) {
                val date = YearGrid.dateAt(year, row, col) ?: continue
                val centre = Offset(cellW * (col + 0.5f), cellH * (row + 0.5f))
                val level = levels[date]
                // T238: filled = solid, empty = a thin outline ring with no fill, so the two never look alike.
                val filled = when {
                    date.isAfter(today) -> null
                    metric == YearMetric.None -> steps[2]
                    level == null -> null
                    else -> steps[(level - 1).coerceIn(0, 3)]
                }
                if (filled != null) drawCircle(filled, radius, centre)
                else {
                    val thin = Metrics.yearEmptyRingDp.dp.toPx()
                    val alpha = if (date.isAfter(today)) Metrics.yearFutureAlpha else Metrics.yearEmptyRingAlpha
                    drawCircle(ringColour.copy(alpha = alpha), radius - thin / 2f, centre, style = Stroke(thin))
                }
                if (date in marks) drawCircle(strong, radius * Metrics.yearMarkFill * 2f, centre)
                if (date == today) drawCircle(strong, radius + ring * 1.5f, centre, style = Stroke(ring))
                if (date == picked) drawCircle(accent, radius + ring * 3f, centre, style = Stroke(ring))
            }
        }
    }
}

/** T238: "No data  less [4 steps] more", so the grid explains itself. */
@Composable
private fun YearLegend(skin: Skin, colour: YearColour) {
    val steps = remember(colour, skin.colors) { YearPalette.steps(colour, skin.colors).map { Color(it.toInt()) } }
    val ringColour = Color(YearPalette.ring(skin.colors).toInt())
    val dot = Metrics.yearLegendDotDp.dp
    @Composable fun Swatch(fill: Color?) = Canvas(Modifier.width(dot).height(dot)) {
        if (fill != null) drawCircle(fill, size.minDimension / 2f)
        else {
            val thin = Metrics.yearEmptyRingDp.dp.toPx()
            drawCircle(ringColour.copy(alpha = Metrics.yearEmptyRingAlpha), size.minDimension / 2f - thin / 2f, style = Stroke(thin))
        }
    }
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Legend: an outlined dot is a day with no number; solid dots go from less to more." },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp),
    ) {
        Swatch(null)
        BasicText("No data", style = hudLabelStyle(skin), maxLines = 1)
        Box(Modifier.weight(1f))
        BasicText("less", style = hudLabelStyle(skin), maxLines = 1)
        steps.forEach { Swatch(it) }
        BasicText("more", style = hudLabelStyle(skin), maxLines = 1)
    }
}

@Composable
private fun CountdownList(countdowns: List<Countdown>, today: LocalDate, skin: Skin, onClearPassed: () -> Unit) {
    val rows = remember(countdowns, today) { Countdowns.upcoming(countdowns, today) }
    val passed = remember(countdowns, today) { Countdowns.passed(countdowns, today) }
    HudCell(skin, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            CellLabel("Countdowns", skin)
            if (rows.isEmpty()) BasicText("No dates yet. Add one in the options.", style = hudValueStyle(skin))
            rows.forEach { BasicText(Countdowns.rowText(it, today), style = hudValueStyle(skin)) }
            if (passed.isNotEmpty()) {
                CellLabel("Passed", skin)
                passed.forEach { BasicText(Countdowns.passedText(it), style = hudLabelStyle(skin)) }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
                        .semantics { contentDescription = "Clear the passed dates"; role = Role.Button }
                        .clickable(onClick = onClearPassed),
                    contentAlignment = Alignment.CenterStart,
                ) { BasicText("Clear passed", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt()))) }
            }
        }
    }
}

/** The demo page: made-up numbers for this year and three made-up dates; nothing is stored. */
@Composable
internal fun DemoYearPage(skin: Skin) {
    val today = LocalDate.now()
    val ledger = remember(today) {
        var l = DayLedger.Empty
        var d = LocalDate.of(today.year, 1, 1)
        while (!d.isAfter(today)) {
            val n = d.dayOfYear
            // A gentle made-up pattern; a few days are left out so "no data" shows too.
            if (n % 11 != 0) l = l.withDay(LedgerDay(d, wifiBytes = 200_000_000L + (n * 37 % 90) * 10_000_000L, mobileBytes = (n * 53 % 40) * 5_000_000L,
                screenOnMillis = (90 + n * 29 % 200) * 60_000L, steps = 3_000 + n * 631 % 9_000, pickups = 20 + n * 7 % 60))
            d = d.plusDays(1)
        }
        l
    }
    val countdowns = remember(today) {
        listOf(Countdown("Exam", today.plusDays(43)), Countdown("Birthday", today.plusDays(120).withYear(2000), yearly = true),
            Countdown("Course", today.minusDays(10)))
    }
    var metric by remember { mutableStateOf(YearMetric.Steps) }
    var cleared by remember { mutableStateOf(false) }
    YearContent(ledger, if (cleared) Countdowns.withoutPassed(countdowns, today) else countdowns, metric, YearColour.Accent, today, skin,
        onCycle = { metric = metric.next() }, onClearPassed = { cleared = true })
}

/** Page options: add, edit and delete the dates to count down to. */
@Composable
internal fun YearOptionChoices(skin: Skin) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val scope = rememberCoroutineScope()
    val list = store.yearCountdowns.collectAsState(initial = emptyList()).value
    val colour = store.yearColour.collectAsState(initial = YearColour.Accent).value
    var editing by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    // M13 review: the date is chosen with the same steppers the note reminder picker uses, never typed.
    val now = remember { LocalDate.now() }
    var pickYear by remember { mutableStateOf(now.year) }
    var pickMonth by remember { mutableStateOf(now.monthValue) }
    var pickDay by remember { mutableStateOf(now.dayOfMonth) }
    var yearly by remember { mutableStateOf(false) }
    val index = editing?.takeIf { it in list.indices }
    val picked = Countdowns.pickedDate(pickYear, pickMonth, pickDay)
    val firstYear = Countdowns.pickerFirstYear(now, index?.let { list[it].date })
    val clean = Countdowns.cleanName(name)
    val canSave = clean.isNotEmpty() && (index != null || list.size < Countdowns.MaxCount)
    fun reset() { editing = null; name = ""; yearly = false; pickYear = now.year; pickMonth = now.monthValue; pickDay = now.dayOfMonth }
    @Composable fun Field(value: String, label: String, max: Int, type: KeyboardType, change: (String) -> Unit) {
        BasicTextField(
            value = value,
            onValueChange = { change(it.take(max)) },
            singleLine = true,
            textStyle = settingsText(skin),
            cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
            keyboardOptions = KeyboardOptions(keyboardType = type),
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp).parcRowPadding().clearAndSetSemantics { contentDescription = label },
        )
    }
    // T238: the colour of the filled dots, with its four steps shown beside each name.
    SettingsCard(skin) {
        BasicText("Colour of the filled dots. Empty days are always an outline.", Modifier.parcRowPadding(), style = settingsText(skin, skin.colors.textSecondary, true))
        com.brightblu.parc.feature.settings.ui.SettingsChoice("Colour", colour.label, YearColour.entries.map { it.label }, skin,
            description = "Accent follows the skin.") { scope.launch { store.setYearColour(YearColour.entries[it]) } }
    }
    SettingsCard(skin) {
        BasicText(if (index != null) "Change this date." else "Add a date to count down to. Give it a name, then pick the day.",
            Modifier.parcRowPadding(), style = settingsText(skin))
        Field(name, "Name", Countdowns.MaxNameLength, KeyboardType.Text) { name = it }
        Stepper("Day", pickDay - 1, 30, skin, text = pickDay.toString()) { pickDay = it + 1 }
        Stepper("Month", pickMonth - 1, 11, skin, text = java.time.Month.of(pickMonth).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)) { pickMonth = it + 1 }
        Stepper("Year", pickYear - firstYear, Countdowns.PickerYears + (now.year - firstYear), skin, text = pickYear.toString()) { pickYear = firstYear + it }
        BasicText(picked.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + YearMath.dateText(picked, withYear = true),
            Modifier.parcRowPadding(), style = settingsText(skin, skin.colors.textSecondary, true))
        SettingRow("Every year", skin, SettingKind.Toggle, "Repeat on the same day each year.", checked = yearly) { yearly = !yearly }
        SettingRow(if (index != null) "Save changes" else "Add date", skin, SettingKind.Action, enabled = canSave,
            description = when {
                index == null && list.size >= Countdowns.MaxCount -> "That is the most dates you can keep."
                else -> ""
            }, icon = "+", divider = index != null) {
            val made = Countdown(clean, picked, yearly)
            scope.launch { store.setYearCountdowns { l -> if (index != null && index in l.indices) l.toMutableList().also { it[index] = made } else l + made } }
            reset()
        }
        if (index != null) {
            SettingRow("Cancel", skin, SettingKind.Action, icon = "−") { reset() }
            SettingRow("Delete this date", skin, SettingKind.Action, destructive = true, icon = "−", divider = false) {
                scope.launch { store.setYearCountdowns { l -> if (index in l.indices) l.filterIndexed { i, _ -> i != index } else l } }
                reset()
            }
        }
    }
    if (list.isNotEmpty()) SettingsCard(skin) {
        list.forEachIndexed { i, c ->
            SettingRow(c.name, skin, SettingKind.Page, YearMath.dateText(c.date, withYear = true) + if (c.yearly) " · every year" else "",
                divider = i != list.lastIndex) {
                editing = i; name = c.name; yearly = c.yearly; pickYear = c.date.year; pickMonth = c.date.monthValue; pickDay = c.date.dayOfMonth
            }
        }
    }
}
