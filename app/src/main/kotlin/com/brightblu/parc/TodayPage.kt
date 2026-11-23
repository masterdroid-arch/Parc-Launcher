package com.brightblu.parc

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.brightblu.parc.core.data.agenda.*
import com.brightblu.parc.core.data.ledger.DayLedgerRepository
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookMetadataLoader
import com.brightblu.parc.core.data.steps.StepMath
import com.brightblu.parc.core.data.steps.StepOptions
import com.brightblu.parc.core.data.timers.TimerBook
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.SevenSegment
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.textFamily
import com.brightblu.parc.model.readableAccent
import com.brightblu.parc.reader.ReaderContract
import com.brightblu.parc.reader.ReaderMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Isolates failures: one unavailable source must not hide the other providers. */
internal suspend fun <T> todayRead(read: suspend () -> T): T? = try { read() }
catch (e: CancellationException) { throw e }
catch (_: Exception) { null }

internal fun todayAgenda(source: AgendaSource, settings: AgendaSettings, now: Instant, zone: ZoneId): AgendaEvent? {
    val names = source.calendars()?.associate { it.id to it.name } ?: return null
    val (from, to) = AgendaRules.window(now.toEpochMilli(), zone, settings.days)
    val events = (source.events(from, to) as? AgendaRead.Data)?.events ?: return null
    val days = AgendaRules.group(AgendaRules.filter(events, names, settings), now.toEpochMilli(), zone, settings.days)
    return AgendaRules.next(days, now.toEpochMilli())
}

@Composable
internal fun TodayHubPage(ledger: DayLedgerRepository?, skin: Skin, active: Boolean,
    focusLabel: String? = null) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val hubConfig by store.config.collectAsState(initial = HubConfig())
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var alarm by remember { mutableStateOf<Long?>(null) }
    var ownAlarm by remember { mutableStateOf(false) }
    var clockOptionsOpen by remember { mutableStateOf(false) }
    val clockScope = rememberCoroutineScope()
    val clockOwner = LocalLifecycleOwner.current
    if (clockOptionsOpen) HubPageOptionsSheet(TodayPage.Id, skin, hubConfig,
        { change -> clockScope.launch { store.update(change) } }, { clockOptionsOpen = false }, LocalHubSettings.current)
    LaunchedEffect(hubConfig.clock.seconds, clockOwner, active) {
        // T270: the one-second ticker runs only while Today is the visible page and the app is resumed.
        if (!active) return@LaunchedEffect
        clockOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                clockNow = System.currentTimeMillis()
                val next = if (DemoMode.enabled) null else context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock
                ownAlarm = next?.showIntent?.creatorPackage == context.packageName
                alarm = next?.takeUnless { ownAlarm }?.triggerTime
                delay(if (hubConfig.clock.seconds) 1000L else 60_000L - clockNow % 60_000L)
            }
        }
    }
    val library = LocalLibraryStore.current
    val books by remember(library) { library?.books ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val visible = LocalTodayVisiblePages.current
    // The row opens the Library page, so it is only offered while that page is shown (hidden or Focus-filtered pages hide it).
    val continueBook = remember(books, LibraryPage.Id in visible) {
        if (LibraryPage.Id in visible) TodayLines.continueReading(books) else null
    }
    val steps = LocalStepsRepository.current
    val rhythm = LocalRhythmRepository.current
    var input by remember { mutableStateOf<TodayInput?>(null) }
    val options by store.todayOptions.collectAsState(initial = TodayOptions())
    var resume by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resume++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Same on-screen refresh cadence as the ledger pages. No job, service, permission prompt or network request.
    LaunchedEffect(active, resume, visible, ledger) {
        if (!active) return@LaunchedEffect
        while (true) {
            // M13 review: always. The heavy-day rule reads the ledger even when Apps and Rhythm are hidden.
            todayRead { ledger?.refresh() }
            if ("rhythm" in visible) todayRead { rhythm?.sample() }
            if ("steps" in visible) todayRead { steps?.sample() }
            input = withContext(Dispatchers.IO) {
                val now = Instant.now()
                val zone = ZoneId.systemDefault()
                val calendarAccess = "agenda" in visible && AndroidAgendaSource.granted(context)
                val notes = if ("notes" in visible) todayRead { NotesStore.get(context).notes.first() }.orEmpty() else emptyList()
                val timerBook = if ("timers" in visible) todayRead { TimersStore(context).state.first().book } else null
                val weather = if ("weather" in visible) todayRead { WeatherVariant.today(context, now) } else null
                val place = if ("sky" in visible) todayRead { store.skyPlace.first() ?: WeatherVariant.todayPlace(context) } else null
                val extra = buildMap {
                    for (page in PersonalExtras.pages) if (page.id in visible) todayRead { page.todayLine(context, now, zone) }?.let { put(page.id, it) }
                }
                val stepOptions = if ("steps" in visible || "records" in visible) todayRead { store.stepOptions.first() } else null
                val day = now.atZone(zone).toLocalDate()
                val days = ledger?.ledger?.value ?: com.brightblu.parc.core.data.ledger.DayLedger.Empty
                TodayInput(now, zone, visible,
                    calendarAccess = calendarAccess,
                    usageAccess = ledger?.accessGranted?.value == true,
                    stepsAccess = steps?.live?.value?.let { it.granted && it.sensor == true } == true,
                    nextEvent = if (calendarAccess) todayRead { todayAgenda(AndroidAgendaSource(context), ParcUiPrefs(context).agenda(), now, zone) } else null,
                    timers = timerBook ?: TimerBook(), notes = notes, weather = weather,
                    steps = StepMath.todayTotal(days.day(day)?.steps, steps?.live?.value?.pending, day),
                    stepGoal = (stepOptions ?: StepOptions()).goal, sky = place, extra = extra,
                    countdowns = if ("year" in visible) todayRead { store.yearCountdowns.first() }.orEmpty() else emptyList(),
                    ledger = days, hour24 = DateFormat.is24HourFormat(context),
                    exactReminders = TimerScheduler.canScheduleExact(context))
            }
            delay(Metrics.todayRefreshMs)
        }
    }
    val current = input?.copy(visible = visible)
    val hasContinue = continueBook != null
    val limit = if (LocalHubSize.current == HubSize.Tall) Int.MAX_VALUE
    else TodayLines.shownLimit(TodayLines.StandardLimit, hasContinue)
    val lines = current?.let { TodayLines.ordered(it, limit) }.orEmpty()
    val rowCount = lines.size + if (hasContinue) 1 else 0
    val showQuote = current != null && TodayLines.showQuote(rowCount, TodayLines.heavy(current, options))
    var quote by remember { mutableStateOf<Quote?>(null) }
    var quoteMessage by remember { mutableStateOf("Loading…") }
    val quotes = rememberQuotesStore()
    LaunchedEffect(showQuote, current?.now, resume) {
        if (!showQuote) return@LaunchedEffect
        try {
            quote = quotes.quoteForToday()
            quoteMessage = if (quote == null) "No quotes shown" else ""
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { quote = null; quoteMessage = "Could not read today's quote" }
    }
    val heavy = current != null && TodayLines.heavy(current, options)
    TodayContent(current?.date ?: LocalDate.now(), lines, if (showQuote) quote else null,
        if (showQuote) quoteMessage else if (current == null) "Loading…" else null, skin, LocalTodayOpenPage.current,
        quoteFirst = heavy, continueBook = continueBook,
        clock = {
            TodayClockHeader(skin, hubConfig.clock, clockNow, alarm, ownAlarm, java.time.ZoneId.systemDefault(), focusLabel,
                onOpenAlarms = {
                    runCatching { context.startActivity(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
                },
                onOpenOptions = { clockOptionsOpen = true })
        })
}

/** The navigation and visibility list come from the hub, including an active Focus filter. */
internal val LocalTodayVisiblePages = staticCompositionLocalOf { emptySet<String>() }
internal val LocalTodayOpenPage = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * T249: the Today page. A date header; one compact row per source with a drawn icon, a key figure in the hub numeral
 * style and a short quiet label (the whole row opens the page it came from). The last opened book leads as "Continue
 * reading". While fewer than [TodayLines.FullRows] rows have something to say (or on a heavy day), today's quote card
 * from the Quotes page fills the rest. On a heavy day the card leads, as before.
 */
@Composable
internal fun TodayContent(date: LocalDate, lines: List<TodayLine>, quote: Quote?, quoteMessage: String?, skin: Skin, open: (String) -> Unit,
    quoteFirst: Boolean = false, continueBook: Book? = null, clock: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val card: @Composable () -> Unit = { if (quote != null || quoteMessage != null) TodayQuoteCard(quote, quoteMessage, skin) }
    Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
        // T396: the family build's slim update notice leads the first page (nothing in other builds).
        FamilyUpdate.HubRow(skin)
        clock()
        if (quoteFirst) card()
        if (continueBook != null || lines.isNotEmpty()) HubDivider(skin)
        if (continueBook != null) {
            ContinueReadingRow(continueBook, skin, context) { cover ->
                ReaderContract.open(context, continueBook, cover)
            }
            if (lines.isNotEmpty()) HubDivider(skin)
        }
        if (lines.isNotEmpty()) Column(Modifier.fillMaxWidth()) {
            lines.forEachIndexed { index, line ->
                TodayRow(line, skin) { open(line.page) }
                if (index < lines.lastIndex) HubDivider(skin)
            }
        }
        if (!quoteFirst) card()
    }
}

/**
 * T263: the Clock page's content now leads Today. The time keeps the skin's face and the chosen
 * clock style; the week, alarm, time zone and Focus line stay exactly as the Clock page showed them.
 * A tap opens alarms and a hold opens the page options, as on the old Clock page.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TodayClockHeader(
    skin: Skin,
    options: ClockOptions,
    now: Long,
    alarm: Long?,
    ownAlarm: Boolean,
    zone: java.time.ZoneId,
    focusLabel: String?,
    onOpenAlarms: () -> Unit,
    onOpenOptions: () -> Unit,
) {
    val look = LocalHubLook.current
    val face = ClockFaces.of(skin.typography, look.clockStyle)
    val shownTime = ClockDisplay.time(now, zone, options)
    val ink = Color((if (skin.style.header == com.brightblu.parc.model.HeaderStyle.ruled) skin.colors.accent else skin.colors.textPrimary).toInt())
    val compact = LocalHubSize.current == HubSize.Compact
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onOpenAlarms, onLongClick = onOpenOptions),
        verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
        if (face != ClockFace.Hidden) {
            // T322: the clock is the skin's clock role (44 sp), never a separate hard-coded size.
            val sizeSp = skin.typography.clock.sizeSp
            if (face == ClockFace.Digital && SevenSegment.supports(shownTime) && !options.seconds) DigitalHubClock(shownTime, skin)
            else if (face == ClockFace.Dot) {
                val dot = skin.typography.clockDot
                DotNumerals(shownTime, DotRole.clockDot, skin.typography.copy(clockDot = dot.copy(sizeSp = sizeSp)), ink)
            } else BasicText(shownTime, style = TextStyle(color = ink,
                letterSpacing = skin.typography.clock.trackingSp.sp,
                fontSize = sizeSp.sp,
                fontWeight = FontWeight(hubClockWeight(look.clockStyle, skin)),
                fontFamily = skin.textFamily))
        }
        // 1.5.1: the date leads under the time, the week beside it; then one row per fact in the page's row style.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
            BasicText(Instant.ofEpochMilli(now).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ENGLISH)),
                Modifier.weight(1f, fill = false).semantics { heading() }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = hubTitleStyle(skin, skin.colors.readableAccent))
            if (!compact) BasicText("Week ${ClockDisplay.week(now, zone)}", Modifier.padding(bottom = Metrics.hubBaselineNudgeDp.dp), maxLines = 1, style = hubCaptionStyle(skin))
        }
        if (!compact) {
            Column(Modifier.fillMaxWidth()) {
                HubDivider(skin)
                if (alarm != null) HubStatRow("Alarm", ClockDisplay.time(alarm, zone, options.copy(seconds = false)), skin,
                    icon = TodayIconKind.Alarm, side = ClockDisplay.until(alarm, now), sideAsCaption = true)
                else HubStatRow("Alarm", if (ownAlarm) "Other alarms hidden while a timer runs" else "None set", skin,
                    icon = TodayIconKind.Alarm, figure = false)
                val zoneId = options.zone.ifEmpty { PersonalExtras.defaultClockZone.orEmpty() }
                val other = runCatching { java.time.ZoneId.of(zoneId) }.getOrNull()
                if (other != null) {
                    HubDivider(skin)
                    HubStatRow(ClockDisplay.city(zoneId), ClockDisplay.time(now, other, options.copy(seconds = false)), skin, icon = TodayIconKind.Zone,
                        side = ClockDisplay.dayWord(now, zone, other) + " · " + ClockDisplay.offsetText(now, zone, other), sideAsCaption = true)
                }
            }
            if (!focusLabel.isNullOrBlank()) BasicText(focusLabel, style = hubBodyStyle(skin, skin.colors.readableAccent))
        }
    }
}

@Composable
private fun TodayRow(line: TodayLine, skin: Skin, click: () -> Unit) {
    val hasFigure = line.figure.isNotBlank()
    HubStatRow(line.label, if (hasFigure) line.figure else line.value, skin, icon = line.icon, figure = hasFigure,
        side = if (hasFigure) line.detail.ifBlank { null } else null, sideAsCaption = true,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${line.label}, ${line.value}" }
            .clickable(role = Role.Button, onClickLabel = "Open ${hubPageName(line.page)}", onClick = click))
}

/** T249: the last opened book, its cover, title and percent; tapping it opens the reader at the saved place. */
@Composable
private fun ContinueReadingRow(book: Book, skin: Skin, context: Context, click: (ReaderMotion.Rect?) -> Unit) {
    val loader = remember(context, book.id) { if (DemoMode.enabled) null else BookMetadataLoader(context) }
    val coverStyle = remember(context, skin) { bookCoverStyle(context, skin) }
    var coverBounds by remember(book.id) { mutableStateOf<ReaderMotion.Rect?>(null) }
    val percent = TodayLines.continuePercent(book)
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.hubStatRowDp.dp)
        .semantics(mergeDescendants = true) { contentDescription = "Continue reading, ${book.title}, $percent% read" }
        .clickable(role = Role.Button, onClickLabel = "Continue reading") { click(coverBounds) }
        .padding(vertical = Metrics.lineGapDp.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(Metrics.todayCoverWidthDp.dp).height(Metrics.todayCoverHeightDp.dp)
            .clip(RoundedCornerShape(Metrics.bookShelfCornerDp.dp))
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                coverBounds = ReaderMotion.coverOrNull(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }) {
            RealBookCover(book, loader, coverStyle, skin, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(Metrics.itemGapDp.dp))
        Column(Modifier.weight(1f)) {
            BasicText("Continue reading", style = hubCaptionStyle(skin))
            BasicText(book.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = hubTitleStyle(skin))
        }
        Spacer(Modifier.width(Metrics.itemGapDp.dp))
        Row {
            DotNumerals("$percent%", DotRole.countDot, skin.typography, Color(skin.colors.textPrimary.toInt()),
                Modifier.alignByBaseline())
            BasicText(" read", Modifier.alignByBaseline(), style = hubCaptionStyle(skin))
        }
    }
}

@Composable
private fun TodayQuoteCard(quote: Quote?, message: String?, skin: Skin) {
    Column(Modifier.fillMaxWidth()
        .background(Color(skin.colors.paper.toInt()), RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
        .padding(Metrics.quoteInsetDp.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Metrics.quoteCreditGapDp.dp)) {
        QuoteText(quote?.text ?: message.orEmpty(), quote?.credit, skin)
    }
}

@Composable
internal fun TodayOptionChoices(skin: Skin, config: HubConfig, update: ((HubConfig) -> HubConfig) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val options by store.todayOptions.collectAsState(initial = TodayOptions())
    val scope = rememberCoroutineScope()
    ClockOptionChoices(skin, config, update)
    SettingsCard(skin) {
        SettingRow("Lead with a quote", skin, SettingKind.Toggle,
            "On days with more screen time or pickups than usual, and before 05:00. An empty Today still shows a quote.",
            checked = options.gentleLead, descriptionMaxLines = Int.MAX_VALUE, divider = false) {
            scope.launch { store.setTodayOptions(options.copy(gentleLead = !options.gentleLead)) }
        }
    }
}
