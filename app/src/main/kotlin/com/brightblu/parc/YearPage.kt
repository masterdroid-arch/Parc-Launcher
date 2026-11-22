package com.brightblu.parc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
    // T280: Add a date opens the picker here on the page as well as in the options.
    var draft by remember { mutableStateOf<YearDateDraft?>(null) }
    YearContent(stored, countdowns, metric, colour, today, skin,
        onCycle = { scope.launch { store.setYearMetric(metric.next()) } },
        onClearPassed = { scope.launch { store.setYearCountdowns { Countdowns.withoutPassed(it, today) } } },
        // No dead tap at the 50-date limit: the action is absent rather than silently doing nothing.
        onAddDate = if (YearDateEditor.canAdd(countdowns)) ({ draft = YearDateEditor.add(today) }) else null)
    draft?.let { open ->
        YearDateDialog(open, countdowns, today, skin,
            onDraft = { draft = it },
            onSave = {
                val saved = YearDateEditor.save(open, countdowns)
                if (saved == null) draft = open.refused()
                else {
                    scope.launch { store.setYearCountdowns { saved.list } }
                    draft = null
                }
            },
            onDelete = open.index?.let { index -> { scope.launch { store.setYearCountdowns { YearDateEditor.delete(index, it) } }; draft = null } },
            onDismiss = { draft = null })
    }
}

/** The drawing: progress line, colour label, the grid, the picked day, and the countdown list. */
@Composable
internal fun YearContent(ledger: DayLedger, countdowns: List<Countdown>, metric: YearMetric, colour: YearColour, today: LocalDate, skin: Skin,
    onCycle: () -> Unit, onClearPassed: () -> Unit, onAddDate: (() -> Unit)? = null) {
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
        CountdownList(countdowns, today, skin, onClearPassed, onAddDate)
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
                    BasicText(it.toString(), style = hubPairLabelStyle(skin, HubType.monthInitial(skin.typography))
                        .copy(lineHeight = HubType.monthInitial(skin.typography).sizeSp.sp), maxLines = 1)
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
private fun CountdownList(countdowns: List<Countdown>, today: LocalDate, skin: Skin, onClearPassed: () -> Unit,
    onAddDate: (() -> Unit)? = null,
) {
    val rows = remember(countdowns, today) { Countdowns.upcoming(countdowns, today) }
    val passed = remember(countdowns, today) { Countdowns.passed(countdowns, today) }
    HudCell(skin, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            CellLabel("Countdowns", skin)
            if (rows.isEmpty()) BasicText("No dates yet. Add one to count down to it.", style = hudValueStyle(skin))
            rows.forEach { BasicText(Countdowns.rowText(it, today), style = hudValueStyle(skin)) }
            // T280: a real action on the page, not a sentence pointing at the options.
            if (onAddDate != null) Box(
                Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
                    .semantics { contentDescription = "Add a date"; role = Role.Button }
                    .clickable(onClick = onAddDate),
                contentAlignment = Alignment.CenterStart,
            ) { BasicText("Add a date", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())), maxLines = 1) }
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

/** Page options: add, change and delete the dates to count down to. */
@Composable
internal fun YearOptionChoices(skin: Skin) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val scope = rememberCoroutineScope()
    val list = store.yearCountdowns.collectAsState(initial = emptyList()).value
    val colour = store.yearColour.collectAsState(initial = YearColour.Accent).value
    val now = remember { LocalDate.now() }
    var draft by remember { mutableStateOf<YearDateDraft?>(null) }
    // T238: the colour of the filled dots, with its four steps shown beside each name.
    SettingsCard(skin) {
        BasicText("Colour of the filled dots. Empty days are always an outline.", Modifier.parcRowPadding(), style = settingsText(skin, skin.colors.textSecondary, true))
        com.brightblu.parc.feature.settings.ui.SettingsChoice("Colour", colour.label, YearColour.entries.map { it.label }, skin,
            description = "Accent follows the skin.") { scope.launch { store.setYearColour(YearColour.entries[it]) } }
    }
    // T280: this row used to be disabled until an unlabelled name field had text, so a tap did nothing.
    // It is now the way in to the picker, and it says why it cannot open only when the list is full.
    SettingsCard(skin) {
        BasicText("Dates you count down to. They also show on the page.", Modifier.parcRowPadding(), style = settingsText(skin))
        val blocked = YearDateEditor.addBlocked(list)
        SettingRow("Add a date", skin, SettingKind.Action, enabled = blocked == null,
            description = blocked ?: "Give it a name, then pick the day.", icon = "+", divider = list.isNotEmpty()) {
            draft = YearDateEditor.add(now)
        }
    }
    if (list.isNotEmpty()) SettingsCard(skin) {
        list.forEachIndexed { i, c ->
            SettingRow(c.name, skin, SettingKind.Page, YearMath.dateText(c.date, withYear = true) + if (c.yearly) " · every year" else "",
                divider = i != list.lastIndex) { draft = YearDateEditor.edit(i, c) }
        }
    }
    draft?.let { open ->
        YearDateDialog(open, list, now, skin,
            onDraft = { draft = it },
            onSave = {
                val saved = YearDateEditor.save(open, list)
                if (saved == null) draft = open.refused()
                else {
                    scope.launch { store.setYearCountdowns { saved.list } }
                    draft = null
                }
            },
            onDelete = open.index?.let { index -> { scope.launch { store.setYearCountdowns { YearDateEditor.delete(index, it) } }; draft = null } },
            onDismiss = { draft = null })
    }
}

/**
 * T280: the name and day picker, used by the page and by its options so there is one add flow. The name field
 * is labelled and underlined, so an empty field is a visible place to type rather than blank space. Save stays
 * tappable and says why it did nothing, because a silent no-op was the bug.
 */
@Composable
private fun YearDateDialog(draft: YearDateDraft, existing: List<Countdown>, today: LocalDate, skin: Skin,
    onDraft: (YearDateDraft) -> Unit, onSave: () -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    val firstYear = Countdowns.pickerFirstYear(today, if (draft.isEditing) draft.date else null)
    val lastYear = firstYear + Countdowns.PickerYears + (today.year - firstYear)
    val problem = if (draft.triedSave) YearDateEditor.problem(draft, existing) else null
    val monthName = java.time.Month.of(draft.month).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(draft.title, style = settingsText(skin)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BasicText("Name", style = settingsText(skin, skin.colors.textSecondary, true))
                BasicTextField(
                    value = draft.name,
                    onValueChange = { onDraft(draft.named(it)) },
                    singleLine = true,
                    textStyle = settingsText(skin),
                    cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
                        .semantics { contentDescription = "Name" }.padding(vertical = Metrics.lineGapDp.dp),
                )
                // A hairline, so the empty field reads as a field.
                Box(Modifier.fillMaxWidth().height(skin.shape.surfaceBorderDp.dp).background(Color(skin.colors.lineActive.toInt())))
                BasicText("For example, Exam or a birthday.", Modifier.padding(top = Metrics.lineGapDp.dp),
                    style = settingsText(skin, skin.colors.textSecondary, true))
                Stepper("Day", draft.day - 1, 30, skin, text = draft.day.toString()) { onDraft(draft.onDay(it + 1)) }
                Stepper("Month", draft.month - 1, 11, skin, text = monthName) { onDraft(draft.onMonth(it + 1)) }
                Stepper("Year", draft.year - firstYear, lastYear - firstYear, skin, text = draft.year.toString()) { onDraft(draft.onYear(firstYear + it)) }
                BasicText(draft.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " +
                    YearMath.dateText(draft.date, withYear = true), Modifier.padding(top = Metrics.lineGapDp.dp),
                    style = settingsText(skin, skin.colors.textSecondary, true))
                SettingRow("Every year", skin, SettingKind.Toggle, "Repeat on the same day each year.", checked = draft.yearly) {
                    onDraft(draft.withYearly(!draft.yearly))
                }
                if (problem != null) BasicText(problem, Modifier.padding(top = Metrics.lineGapDp.dp), style = settingsText(skin, skin.colors.warn))
                if (onDelete != null) SettingRow("Delete this date", skin, SettingKind.Action, destructive = true, icon = "−", divider = false) { onDelete() }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { BasicText(draft.saveLabel, style = settingsText(skin, skin.colors.accent)) } },
        dismissButton = { TextButton(onClick = onDismiss) { BasicText("Cancel", style = settingsText(skin, skin.colors.textSecondary)) } },
    )
}
