package com.brightblu.parc.core.data.rhythm

import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Rhythm page shows for one day of the ledger. Everything is null when the day was not recorded. */
data class RhythmShown(
    val date: LocalDate,
    val isToday: Boolean,
    val bins: BooleanArray?,
    val firstPickup: String?,
    val pickups: Int?,
    val screenOnMillis: Long?,
    val longestFreeMillis: Long?,
    /** The fraction of the day that has passed (0..1) for the "now" mark; null on any other day. */
    val nowFraction: Float?,
    val previous: LocalDate?,
    val next: LocalDate?,
) {
    val recorded: Boolean get() = bins != null || pickups != null
}

/** Pure reading of the ledger for the page: which days can be visited, and the numbers of one day. */
object RhythmView {
    /** The days that have rhythm data, oldest first. */
    fun recordedDates(ledger: DayLedger): List<LocalDate> =
        ledger.days.values.filter { it.rhythmBins != null || it.pickups != null }.map { it.date }.sorted()

    /** The latest recorded day before [from], or null. */
    fun previous(ledger: DayLedger, from: LocalDate): LocalDate? = recordedDates(ledger).lastOrNull { it.isBefore(from) }

    /** The earliest recorded day after [from] up to [today]; today itself is always reachable. */
    fun next(ledger: DayLedger, from: LocalDate, today: LocalDate): LocalDate? {
        if (!from.isBefore(today)) return null
        return recordedDates(ledger).firstOrNull { it.isAfter(from) && it.isBefore(today) } ?: today
    }

    /**
     * The numbers of [date]. The screen-on total is the ledger's own (from Android's whole-day numbers) and
     * falls back to the lit bins only when the ledger has none. [nowFraction] needs [dayLengthMillis] and
     * [elapsedMillis] of today, given by the caller because a day is not always 24 hours.
     */
    fun show(ledger: DayLedger, date: LocalDate, today: LocalDate, elapsedMillis: Long, dayLengthMillis: Long): RhythmShown {
        val day: LedgerDay? = ledger.day(date)
        val bins = RhythmBins.decode(day?.rhythmBins)
        val screen = day?.screenOnMillis ?: bins?.let { b -> b.count { it } * RhythmBins.BinMillis }
        return RhythmShown(
            date = date,
            isToday = date == today,
            bins = bins,
            firstPickup = day?.firstPickupMinute?.let { RhythmBins.clock(it) },
            pickups = day?.pickups,
            screenOnMillis = screen,
            longestFreeMillis = day?.phoneFreeMillis,
            nowFraction = if (date == today && dayLengthMillis > 0L) (elapsedMillis.toFloat() / dayLengthMillis).coerceIn(0f, 1f) else null,
            previous = previous(ledger, date),
            next = next(ledger, date, today),
        )
    }

    private val dayName = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** `Today`, `Yesterday`, or `Tue 3 Nov`. */
    fun dateLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dateLabelPlain(date)
    }

    fun dateLabelPlain(date: LocalDate): String = dayName.format(date)

    /** The lit stretches in words for TalkBack, at most [limit] of them: `07:40 to 08:05, 09:15 to 09:30`. */
    fun runsDescription(bins: BooleanArray, limit: Int = 12): String {
        val runs = RhythmBins.runs(bins)
        if (runs.isEmpty()) return "The screen was not on"
        val shown = runs.take(limit).joinToString(", ") { RhythmBins.time(it.first) + " to " + RhythmBins.time(it.last + 1) }
        return "The screen was on $shown" + if (runs.size > limit) ", and ${runs.size - limit} more" else ""
    }
}
