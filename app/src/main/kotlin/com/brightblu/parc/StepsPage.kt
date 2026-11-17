package com.brightblu.parc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.DayLedgerRepository
import com.brightblu.parc.core.data.ledger.LedgerPeriod
import com.brightblu.parc.core.data.steps.StepBar
import com.brightblu.parc.core.data.steps.StepMath
import com.brightblu.parc.core.data.steps.StepOptions
import com.brightblu.parc.core.data.steps.StepSummary
import com.brightblu.parc.core.data.steps.StepsRepository
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.parcRowPadding
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import java.time.LocalDate
import java.time.format.TextStyle as DayNameStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** T187: the Steps page id. Lowercase and stable, because it is part of the saved hub format. */
internal object StepsPage {
    const val Id = "steps"
}

/** The step counter, provided where the hub is drawn. Null in previews and in demo mode (the demo page draws fixtures). */
internal val LocalStepsRepository = staticCompositionLocalOf<StepsRepository?> { null }

/** Everything [StepsContent] draws, so the real page and the demo page share one drawing. */
internal data class StepsModel(
    val today: Int?,
    val options: StepOptions,
    val bars: List<StepBar>,
    val summary: StepSummary,
)

internal object StepsPresentation {
    fun model(ledger: DayLedger, pending: com.brightblu.parc.core.data.steps.StepPending?, options: StepOptions,
        period: LedgerPeriod, today: LocalDate): StepsModel {
        val saved = ledger.day(today)?.steps
        return StepsModel(
            today = StepMath.todayTotal(saved, pending, today),
            options = options,
            bars = StepMath.week(ledger, today, pending),
            summary = StepMath.summary(ledger, period, today, pending),
        )
    }

    fun heroDescription(model: StepsModel): String {
        val today = model.today ?: return "No step data yet"
        val distance = StepMath.distanceText(StepMath.distanceMeters(today.toLong(), model.options.strideCm))
        val goal = model.options.goal
        val fraction = StepMath.goalFraction(today, goal)
        return "Steps today ${StepMath.count(today.toLong())}, about $distance" +
            if (goal != null && fraction != null) ", ${(fraction * 100).toInt()} percent of the goal of ${StepMath.count(goal.toLong())}" else ""
    }

    fun barsDescription(bars: List<StepBar>): String = "Last seven days. " + bars.joinToString(". ") {
        it.date.dayOfWeek.getDisplayName(DayNameStyle.FULL, Locale.ENGLISH) + " " + (it.steps?.let { s -> StepMath.count(s.toLong()) } ?: "no data")
    }
}

/** Activity recognition permission state plus the request. After a permanent refusal the system shows no dialog, so the app's own page opens. */
internal class StepsAccess(val granted: Boolean, val request: () -> Unit)

@Composable
internal fun rememberStepsAccess(onChange: () -> Unit = {}): StepsAccess {
    val context = LocalContext.current
    fun held() = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(held()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = held()
        if (!ok) runCatching {
            val activity = context as? android.app.Activity
            if (activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        }
        onChange()
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = held()
                if (now != granted) { granted = now; onChange() }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(granted) { StepsAccess(granted) { runCatching { launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) } } }
}

/**
 * T187: the Steps page. Counting is done by [StepsRepository] from the phone's own step counter; this page only
 * asks for the permission (when the user taps Allow), listens while it is visible, and draws. Without the sensor,
 * without permission, and without data it says so in plain words.
 */
@Composable
internal fun StepsHubPage(ledger: DayLedgerRepository?, skin: Skin, active: Boolean) {
    val context = LocalContext.current
    val steps = LocalStepsRepository.current
    val store = remember(context) { HubConfigStore(context) }
    var tick by remember { mutableStateOf(0) }
    val access = rememberStepsAccess { steps?.checkAccess(); tick++ }
    // The ledger document is read when it is first asked to refresh; stepping on it keeps the older days current too.
    LaunchedEffect(ledger, active) {
        if (active) while (true) {
            ledger?.refresh()
            delay(Metrics.hubNetworkRefreshMillis)
        }
    }
    // Listen to the counter only while the page is the visible one; the 5-minute tick covers the rest of the time.
    LaunchedEffect(steps, active, access.granted) { if (active && steps != null) steps.watch() }
    val stored = ledger?.ledger?.collectAsState()?.value ?: DayLedger.Empty
    val live = steps?.live?.collectAsState()?.value ?: StepsRepository.Live()
    val options = store.stepOptions.collectAsState(initial = StepOptions()).value
    val (period, cycle) = rememberHubPeriod(StepsPage.Id)
    val today = ledger?.today() ?: LocalDate.now()
    val model = remember(stored, live.pending, options, period, today) { StepsPresentation.model(stored, live.pending, options, period, today) }
    val hasData = model.summary.daysWithData > 0 || stored.days.values.any { it.steps != null } || model.today != null
    Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        if (live.sensor == false) {
            HudCell(skin, Modifier.fillMaxWidth()) {
                BasicText("This phone has no step counter, so Steps cannot count.", style = hudValueStyle(skin))
            }
        } else if (!access.granted) {
            HubPermissionCard("Steps are counted by the phone's step counter. Allow physical activity access to see them.", "Allow", skin, scroll = false, allow = access.request)
        }
        if (live.sensor != false && access.granted && model.today == null && !hasData) {
            HudCell(skin, Modifier.fillMaxWidth()) {
                BasicText(hubTemplates.getValue(StepsPage.Id).empty, style = hudValueStyle(skin))
            }
        } else if (hasData) {
            StepsContent(model, skin, period, cycle)
        }
    }
}

/** The drawing: today's number with the goal ring, the last seven days, and the period total. Stateless. */
@Composable
internal fun StepsContent(model: StepsModel, skin: Skin, period: LedgerPeriod, onCycle: () -> Unit) {
    val size = LocalHubSize.current
    val goal = model.options.goal
    val fraction = StepMath.goalFraction(model.today, goal)
    val gap = Metrics.hudCellGapDp.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        HudCell(skin, Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = StepsPresentation.heroDescription(model) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                if (fraction != null) GoalRing(fraction, when (size) {
                    HubSize.Compact -> Metrics.stepsArcCompactDp
                    HubSize.Standard -> Metrics.stepsArcStandardDp
                    HubSize.Tall -> Metrics.stepsArcTallDp
                }, skin)
                Column {
                    HubHero(model.today?.let { StepMath.count(it.toLong()) } ?: "—", "steps today", skin)
                    val distance = model.today?.let { StepMath.distanceText(StepMath.distanceMeters(it.toLong(), model.options.strideCm)) }
                    CellLabel(buildString {
                        append(distance?.let { "About $it" } ?: "No step data yet")
                        if (goal != null) append(" · goal ").append(StepMath.count(goal.toLong()))
                    }, skin)
                }
            }
        }
        HudCell(skin, Modifier.fillMaxWidth()) { WeekBars(model.bars, goal, skin, size == HubSize.Compact) }
        HudCell(skin, Modifier.fillMaxWidth()) {
            Column {
                PeriodLabel(model.summary.label, skin, onCycle)
                val distance = StepMath.distanceText(StepMath.distanceMeters(model.summary.total, model.options.strideCm))
                Readout("Total · about $distance", "${StepMath.count(model.summary.total)} steps", skin)
                if (period != LedgerPeriod.Today) {
                    Readout("Daily average", model.summary.average?.let { StepMath.count(it) } ?: "—", skin)
                }
            }
        }
    }
}

/** A quiet ring: a faint track and the goal's progress in the accent, starting at the top and going clockwise. */
@Composable
private fun GoalRing(fraction: Float, sizeDp: Float, skin: Skin) {
    val track = Color(skin.colors.lineBase.toInt())
    val accent = Color(skin.colors.accent.toInt())
    Canvas(Modifier.size(sizeDp.dp).clearAndSetSemantics {}) {
        val stroke = Metrics.stepsArcStrokeDp.dp.toPx()
        val inset = stroke / 2f
        val box = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
        if (fraction > 0f) drawArc(accent, -90f, 360f * fraction, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Seven bars, oldest first, today in the accent. A missing day shows only the track. A thin line marks the goal. */
@Composable
private fun WeekBars(bars: List<StepBar>, goal: Int?, skin: Skin, compact: Boolean) {
    val track = Color(skin.colors.lineBase.toInt())
    val level = Color(skin.colors.textSecondary.toInt())
    val now = Color(skin.colors.accent.toInt())
    val top = (bars.mapNotNull { it.steps }.maxOrNull() ?: 0).coerceAtLeast(goal ?: 0).coerceAtLeast(1)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = StepsPresentation.barsDescription(bars) },
        verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
        CellLabel("Last 7 days", skin)
        Canvas(Modifier.fillMaxWidth().height((if (compact) Metrics.stepsBarsCompactHeightDp else Metrics.stepsBarsHeightDp).dp)) {
            val slot = size.width / bars.size
            val width = slot * Metrics.stepsBarFill
            val corner = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f)
            bars.forEachIndexed { i, bar ->
                val left = slot * i + (slot - width) / 2f
                drawRoundRect(track, Offset(left, 0f), Size(width, size.height), corner)
                val steps = bar.steps
                if (steps != null && steps > 0) {
                    val h = (size.height * steps / top).coerceAtLeast(width)
                    drawRoundRect(if (bar.isToday) now else level, Offset(left, size.height - h), Size(width, h), corner)
                }
            }
            if (goal != null) {
                val y = size.height - size.height * goal / top
                drawLine(level.copy(alpha = Metrics.stepsGoalLineAlpha), Offset(0f, y), Offset(size.width, y), Metrics.stepsGoalLineDp.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { bar ->
                val tint = if (bar.isToday) skin.copy(colors = skin.colors.copy(textSecondary = skin.colors.accent)) else skin
                BasicText(bar.date.dayOfWeek.getDisplayName(DayNameStyle.SHORT, Locale.ENGLISH), Modifier.weight(1f),
                    style = hudLabelStyle(tint).copy(textAlign = TextAlign.Center), maxLines = 1)
            }
        }
    }
}

/** The demo page: made-up numbers, no sensor, no ledger, no permission. */
@Composable
internal fun DemoStepsPage(skin: Skin) {
    val today = LocalDate.now()
    val ledger = remember(today) {
        listOf(6_240, 8_915, 4_380, 10_120, 7_050, 9_300, 5_210).foldIndexed(DayLedger.Empty) { i, l, steps ->
            l.withDay(com.brightblu.parc.core.data.ledger.LedgerDay(today.minusDays((6 - i).toLong()), steps = steps))
        }
    }
    var period by remember { mutableStateOf(LedgerPeriod.Default) }
    val model = remember(ledger, period) { StepsPresentation.model(ledger, null, StepOptions(), period, today) }
    Column(Modifier.fillMaxWidth().hubPageScroll()) {
        StepsContent(model, skin, period) { period = period.next() }
    }
}

/** Page options: stride length for the distance, and the daily goal (on or off, and how many steps). */
@Composable
internal fun StepsOptionChoices(skin: Skin) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val scope = rememberCoroutineScope()
    val options by store.stepOptions.collectAsState(initial = StepOptions())
    fun change(f: (StepOptions) -> StepOptions) { scope.launch { store.setStepOptions(f) } }
    val stride = HubOptionNumbers.Stride
    val goalStep = HubOptionNumbers.Goal
    val goal = options.goal
    SettingsCard(skin) {
        HubOptionStepper("Stride length", HubOptionNumbers.strideText(options.strideCm), skin,
            canDecrease = stride.canGoDown(options.strideCm), canIncrease = stride.canGoUp(options.strideCm),
            description = "Used for the rough distance. Walking is usually 0.65 to 0.80 m.",
            onDecrease = { change { it.withStride(stride.down(it.strideCm)) } },
            onIncrease = { change { it.withStride(stride.up(it.strideCm)) } })
        SettingRow("Daily goal", skin, SettingKind.Toggle, checked = goal != null, divider = goal != null) {
            change { it.copy(goal = if (it.goal == null) StepOptions.DefaultGoal else null) }
        }
        if (goal != null) {
            HubOptionStepper("Goal", HubOptionNumbers.goalText(goal), skin,
                canDecrease = goalStep.canGoDown(goal), canIncrease = goalStep.canGoUp(goal),
                divider = false,
                onDecrease = { change { it.withGoal(goalStep.down(it.goal ?: StepOptions.DefaultGoal)) } },
                onIncrease = { change { it.withGoal(goalStep.up(it.goal ?: StepOptions.DefaultGoal)) } })
        }
    }
}
