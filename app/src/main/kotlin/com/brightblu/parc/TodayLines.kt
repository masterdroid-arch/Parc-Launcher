package com.brightblu.parc

import com.brightblu.parc.core.data.agenda.AgendaEvent
import com.brightblu.parc.core.data.agenda.AgendaRules
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.steps.StepMath
import com.brightblu.parc.core.data.timers.TimerBook
import com.brightblu.parc.core.data.timers.TimerStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal object TodayPage { const val Id = "today" }
/**
 * One row of the Today page. [label] names the source ("Weather", "Next event"); [text] is the full line the providers
 * make. [figure] is the key number drawn in the hub numeral style, and [caption] is the short quiet label beside it.
 * The old text-only providers still work: their whole line becomes the value.
 */
internal data class TodayLine(
    val page: String,
    val text: String,
    val label: String = "",
    val figure: String = "",
    val caption: String = "",
) {
    val value: String get() = if (label.isNotEmpty()) text.removePrefix("$label · ").ifBlank { text } else text
    /** What the quiet line under or beside the figure says. */
    val title: String get() = caption.ifBlank { value }
    val icon: TodayIconKind get() = TodayIconKind.forPage(page)
    /** 1.5.1: the quiet right-hand text of a figure row: the caption without the source name it starts or ends with. */
    val detail: String get() = if (label.isEmpty()) caption else caption.removePrefix(label).removePrefix(" · ").removeSuffix(" · $label").trim()
}

internal data class TodayWeather(val place: String, val now: String, val high: String, val low: String, val old: Boolean = false)
internal data class TodayOptions(val gentleLead: Boolean = true) {
    fun encode(): String = gentleLead.toString()
    companion object {
        const val Key = "today_gentle_lead"
        fun decode(raw: String?): TodayOptions = TodayOptions(raw?.toBooleanStrictOrNull() ?: true)
    }
}

/** Inputs have no Android/Compose dependencies. Missing readings stay missing, never zero. */
internal data class TodayInput(
    val now: Instant,
    val zone: ZoneId,
    val visible: Set<String>,
    val calendarAccess: Boolean = false,
    val usageAccess: Boolean = false,
    val stepsAccess: Boolean = false,
    val nextEvent: AgendaEvent? = null,
    val timers: TimerBook = TimerBook(),
    val notes: List<Note> = emptyList(),
    val weather: TodayWeather? = null,
    val steps: Int? = null,
    val stepGoal: Int? = null,
    val sky: SkyPlace? = null,
    /** T198: lines from pages only some builds carry, by page id. */
    val extra: Map<String, String> = emptyMap(),
    val countdowns: List<Countdown> = emptyList(),
    val ledger: DayLedger = DayLedger.Empty,
    val hour24: Boolean = true,
    val exactReminders: Boolean = true,
) {
    val date: LocalDate get() = now.atZone(zone).toLocalDate()
    val millis: Long get() = now.toEpochMilli()
}

internal object TodayLines {
    const val StandardLimit = 6
    private const val HistoryDays = 14L
    private const val MinimumHistory = 7

    /** Ordered providers are deliberately lazy: hidden providers never evaluate their content. */
    fun ordered(input: TodayInput, limit: Int = StandardLimit): List<TodayLine> = with(input) {
        listOf(
            provider("agenda", calendarAccess) { agendaLine(this) },
            provider("timers") { timerLine(this) },
            provider("notes") { reminderLine(this) },
            provider("weather") { weather?.let { weatherLine(it) } },
            provider("steps", stepsAccess) { steps?.let { stepsLine(it, stepGoal) } },
            provider("records") { RecordsMath.todayLine(ledger, date, stepGoal, steps)?.let { looseLine("records", it) } },
            provider("sky") { skyLine(this) },
            provider("year") { countdownLine(this) },
        ).plus(extra.map { (page, text) -> provider(page) { looseLine(page, text) } }).plus(listOf(
            provider("apps", usageAccess) { screenLine(this) },
            provider("notes") { pinnedLine(this) },
        )).filterNotNull().take(limit.coerceAtLeast(0))
    }

    private fun TodayInput.provider(page: String, allowed: Boolean = true, read: () -> TodayLine?): TodayLine? =
        if (page !in visible || !allowed) null else read()?.takeIf { it.text.isNotBlank() }

    /** A provider with only a sentence keeps its whole line; no number is invented for it. */
    fun looseLine(page: String, text: String, label: String = page.replaceFirstChar(Char::uppercase)): TodayLine =
        TodayLine(page, text, label)

    fun agendaLine(input: TodayInput): TodayLine? = input.nextEvent?.takeIf {
        !it.allDay && (it.endMs > input.millis || it.beginMs >= input.millis)
    }?.let {
        val until = AgendaRules.untilText(it.beginMs, input.millis)
        val title = AgendaRules.titleText(it)
        TodayLine("agenda", "$title · $until", "Next event",
            figure = until.removePrefix("in "), caption = "Next event · $title")
    }

    fun agenda(input: TodayInput): String? = agendaLine(input)?.text

    fun timerLine(input: TodayInput): TodayLine? {
        val running = input.timers.timers.filter { it.effective(input.millis) == TimerStatus.Running }.sortedBy { it.endAtMs }
        val next = running.firstOrNull() ?: return null
        val count = if (running.size == 1) next.label.ifBlank { "Timer" } else "${running.size} timers"
        val minutes = (next.left(input.millis) + 59_999L) / 60_000L
        val text = "$count · ${if (running.size > 1) "next in " else ""}$minutes min${if (running.size == 1) " left" else ""}"
        val caption = if (running.size == 1) "Timer · $count" else "Timers · $count running"
        return TodayLine("timers", text, "Timer", figure = "$minutes min", caption = caption)
    }

    fun timers(input: TodayInput): String? = timerLine(input)?.text

    fun reminderLine(input: TodayInput): TodayLine? {
        val note = input.notes.filter { it.remindAt > input.millis }.minByOrNull { it.remindAt } ?: return null
        val whenText = NoteReminderRules.whenText(note.remindAt, input.millis, input.zone, input.hour24)
        val prefix = if (input.exactReminders) "" else "around "
        val label = "Reminder · $prefix$whenText"
        val title = NoteTitles.display(note)
        val time = DateTimeFormatter.ofPattern(if (input.hour24) "HH:mm" else "h:mm a", Locale.getDefault())
            .format(Instant.ofEpochMilli(note.remindAt).atZone(input.zone))
        val day = whenText.removeSuffix(time).trim()
        return TodayLine("notes", "$label · $title", "Reminder", figure = time,
            caption = "Reminder" + (if (prefix.isEmpty()) "" else " around") + " · $day · $title")
    }

    fun reminder(input: TodayInput): String? = reminderLine(input)?.text

    fun weatherLine(data: TodayWeather): TodayLine =
        TodayLine("weather", weather(data), "Weather", figure = data.now,
            caption = "${data.place} · high ${data.high} · low ${data.low}" + if (data.old) " · old forecast" else "")

    fun weather(data: TodayWeather): String =
        "${data.place} · ${data.now} now · high ${data.high} · low ${data.low}" + if (data.old) " · old forecast" else ""

    fun stepsLine(count: Int, goal: Int?): TodayLine =
        TodayLine("steps", steps(count, goal), "Steps", figure = StepMath.count(count.toLong()),
            caption = "Steps" + (goal?.let { " · goal ${StepMath.count(it.toLong())}" } ?: ""))

    fun steps(count: Int, goal: Int?): String = "${StepMath.count(count.toLong())} steps" +
        (goal?.let { " · goal ${StepMath.count(it.toLong())}" } ?: "")

    fun skyLine(input: TodayInput): TodayLine? {
        val place = input.sky?.takeIf { it.valid } ?: return null
        val phoneOffset = input.zone.rules.getOffset(input.now).totalSeconds
        val offset = place.utcOffsetSeconds ?: phoneOffset
        val date = input.now.atOffset(ZoneOffset.ofTotalSeconds(offset)).toLocalDate()
        val sun = SkyMath.sunDay(date, place)
        fun ahead(at: Long): Boolean = at > input.millis &&
            Instant.ofEpochMilli(at).atOffset(ZoneOffset.ofTotalSeconds(offset)).toLocalDate() == date
        val golden = listOfNotNull(sun.morningGolden, sun.eveningGolden).filter { ahead(it.startMillis) }
            .minByOrNull { it.startMillis }
        val event = golden?.let { "Golden hour" to it.startMillis }
            ?: sun.sunsetMillis?.takeIf(::ahead)?.let { "Sunset" to it } ?: return null
        val offsetNote = if (offset != phoneOffset) " · ${SkyView.offsetLabel(offset)}" else ""
        val clock = SkyView.clock(event.second, offset)
        return TodayLine("sky", "${event.first} · $clock$offsetNote", "Sky", figure = clock,
            caption = "${event.first} · Sky$offsetNote")
    }

    fun sky(input: TodayInput): String? = skyLine(input)?.text

    fun countdownLine(input: TodayInput): TodayLine? {
        val row = Countdowns.upcoming(input.countdowns, input.date).firstOrNull { it.days in 0..14 } ?: return null
        val days = when (row.days) { 0L -> "Today"; 1L -> "1 day"; else -> "${row.days} days" }
        val date = YearMath.dateText(row.on, withYear = row.on.year != input.date.year)
        return TodayLine("year", Countdowns.rowText(row, input.date), "Countdown", figure = days,
            caption = "Countdown · ${row.countdown.name} · $date")
    }

    fun countdown(input: TodayInput): String? = countdownLine(input)?.text

    fun screenLine(input: TodayInput): TodayLine? = input.ledger.day(input.date)?.screenOnMillis?.let { value ->
        val minutes = value / 60_000L
        val time = if (minutes < 60) "$minutes min" else String.format(Locale.ROOT, "%d h %02d", minutes / 60, minutes % 60)
        val comparison = comparison(value, average(input.ledger, input.date) { it.screenOnMillis })
        val caption = "Screen time" + (comparison?.let { " · $it" } ?: "")
        TodayLine("apps", "Screen time · $time" + (comparison?.let { " · $it" } ?: ""), "Screen time",
            figure = time, caption = caption)
    }

    fun screen(input: TodayInput): String? = screenLine(input)?.text

    fun pinnedLine(input: TodayInput): TodayLine? = orderedNotes(input.notes).firstOrNull { it.pinned }?.let { note ->
        // T237: the explicit title heads the line; otherwise the first physical line, as before.
        val title = NoteTitles.normalize(note.title).takeIf(String::isNotEmpty)
            ?: NoteMarkup.parse(note.text.lineSequence().firstOrNull().orEmpty())
                .firstOrNull()?.let(NoteMarkup::stripped)?.trim()?.takeIf(String::isNotEmpty)
        title?.let { TodayLine("notes", it, "Pinned note") }
    }

    fun pinned(input: TodayInput): String? = pinnedLine(input)?.text

    /** Previous fourteen local dates; today and missing samples never enter their own baseline. */
    fun average(ledger: DayLedger, today: LocalDate, value: (LedgerDay) -> Long?): Double? {
        val samples = ledger.range(today.minusDays(HistoryDays), today.minusDays(1))
            .mapNotNull(value).filter { it >= 0 }
        return if (samples.size < MinimumHistory) null else samples.map(Long::toDouble).average()
    }

    fun comparison(value: Long, average: Double?): String? = when {
        average == null -> null
        value < average -> "less than usual"
        value > average -> "more than usual"
        else -> "as usual"
    }

    fun heavy(input: TodayInput, options: TodayOptions = TodayOptions()): Boolean {
        if (!options.gentleLead) return false
        if (input.now.atZone(input.zone).hour in 0..4) return true
        if (!input.usageAccess) return false
        val day = input.ledger.day(input.date) ?: return false
        fun above(current: Long?, average: Double?): Boolean =
            current != null && average != null && current.toDouble() > average * 1.25
        return ("apps" in input.visible && above(day.screenOnMillis, average(input.ledger, input.date) { it.screenOnMillis })) ||
            ("rhythm" in input.visible && above(day.pickups?.toLong(), average(input.ledger, input.date) { it.pickups?.toLong() }))
    }

    /** Below this many rows the page has room, so today's quote card fills it (1.2.1). */
    const val FullRows = 4

    /** The quote shows on a heavy day (and then leads), and whenever there are fewer than [FullRows] rows to say. */
    fun showQuote(lines: List<TodayLine>, heavy: Boolean): Boolean = showQuote(lines.size, heavy)

    fun showQuote(rowCount: Int, heavy: Boolean): Boolean = rowCount < FullRows || heavy

    /** T249: Continue reading takes one of the same six Standard slots, so the page keeps its old height. */
    fun shownLimit(limit: Int, hasContinue: Boolean): Int =
        if (hasContinue) (limit - 1).coerceAtLeast(0) else limit

    /** T249: the most recently opened library book, or nothing when no book has ever been opened. */
    fun continueReading(books: List<Book>): Book? = books
        .filter { (it.lastOpenedAt ?: 0L) > 0L }
        .maxWithOrNull(compareBy<Book> { it.lastOpenedAt ?: 0L }.thenBy { it.id })

    /** T249: the percent read on the Continue reading row, never outside 0..100. */
    fun continuePercent(book: Book): Int = (book.progress.coerceIn(0.0, 1.0) * 100).roundToInt()
}
