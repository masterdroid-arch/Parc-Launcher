package com.brightblu.parc.core.data.ledger

import com.brightblu.parc.core.data.apps.AppPackages
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.core.data.power.ScreenEvent
import com.brightblu.parc.core.data.power.ScreenOnTimeMath
import com.brightblu.parc.model.AppId
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Which per-app map a top-apps question is about. */
enum class LedgerAppKind { Data, Screen }

/**
 * Pure maths of the day ledger (T185): windows, period sums, closing a day, merging a backfill,
 * month roll-over and pruning. No Android types, no clock, no zone except where a function takes one.
 */
object LedgerMath {

    /** `yyyy-MM`, the key of [DayLedger.months]. */
    fun monthKey(date: LocalDate): String = YearMonth.from(date).toString()

    /** The first and last date a period counts, before clamping to the first ledger date. */
    fun window(period: LedgerPeriod, today: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        LedgerPeriod.Today -> today to today
        LedgerPeriod.Week -> today.minusDays(6) to today
        LedgerPeriod.Month -> today.minusDays(29) to today
        LedgerPeriod.Year -> LocalDate.of(today.year, 1, 1) to today
        LedgerPeriod.All -> (LocalDate.of(1970, 1, 1)) to today
    }

    /** Period sums for the dates [period] covers, ending at [today]. Missing days add nothing. */
    fun summary(ledger: DayLedger, period: LedgerPeriod, today: LocalDate): PeriodSummary {
        var (from, to) = window(period, today)
        val first = ledger.firstDate
        var sinceFirst = false
        if (period == LedgerPeriod.Year || period == LedgerPeriod.All) {
            // Before Parc started recording nothing is missing, it is just not recorded.
            val start = first ?: today
            if (first == null || start.isAfter(from)) {
                from = start
                sinceFirst = true
            }
            if (period == LedgerPeriod.All) sinceFirst = true
        }
        if (to.isBefore(from)) from = to
        val days = ledger.range(from, to)
        return PeriodSummary(
            period = period,
            from = from,
            to = to,
            days = (to.toEpochDay() - from.toEpochDay() + 1).toInt(),
            wifiBytes = days.sumOf { it.wifiBytes ?: 0L },
            mobileBytes = days.sumOf { it.mobileBytes ?: 0L },
            screenOnMillis = days.sumOf { it.screenOnMillis ?: 0L },
            networkDays = days.count { it.hasNetwork },
            screenDays = days.count { it.hasScreen },
            firstDate = first,
            sinceFirst = sinceFirst,
        )
    }

    /**
     * Per-package totals for [period]. Today, 7 and 30 days sum the per-day maps (kept 35 days).
     * This year and All time sum the month sums of closed days plus the maps of days not closed yet
     * (today, or a day still waiting to be closed), so a day is never counted twice.
     */
    fun appTotals(
        ledger: DayLedger,
        period: LedgerPeriod,
        today: LocalDate,
        kind: LedgerAppKind,
    ): Map<String, Long> {
        val out = HashMap<String, Long>()
        fun add(map: Map<String, Long>) {
            for ((pkg, value) in map) if (value > 0L) out[pkg] = (out[pkg] ?: 0L) + value
        }
        fun dayMap(day: LedgerDay) = if (kind == LedgerAppKind.Data) day.appData else day.appScreen
        val (from, to) = window(period, today)
        if (period == LedgerPeriod.Year || period == LedgerPeriod.All) {
            val yearPrefix = "${today.year}-"
            for ((key, month) in ledger.months) {
                if (period == LedgerPeriod.Year && !key.startsWith(yearPrefix)) continue
                add(if (kind == LedgerAppKind.Data) month.data else month.screen)
            }
            for (day in ledger.days.values) {
                if (!day.closed && !day.date.isBefore(from) && !day.date.isAfter(to)) add(dayMap(day))
            }
        } else {
            for (day in ledger.range(from, to)) add(dayMap(day))
        }
        return out
    }

    /**
     * Closes [day]: marks it final and adds its per-app numbers to its month. Closing a day that is
     * already closed in [ledger] changes nothing, so a repeated close (a restart, a second refresh)
     * can never count a day twice.
     */
    fun closeDay(ledger: DayLedger, day: LedgerDay): DayLedger {
        if (ledger.day(day.date)?.closed == true) return ledger
        val key = monthKey(day.date)
        val month = ledger.months[key] ?: MonthApps()
        val nextMonth = MonthApps(
            data = capMap(sumMaps(month.data, day.appData), DayLedger.MaxAppsPerMonth),
            screen = capMap(sumMaps(month.screen, day.appScreen), DayLedger.MaxAppsPerMonth),
        )
        return ledger.copy(months = ledger.months + (key to nextMonth))
            .withDay(day.copy(closed = true))
    }

    /**
     * Stores whole-day answers from Android as closed days. A day already closed is kept as it is.
     * An open day keeps the fields this build does not measure (steps, pickups, phone-free time and
     * unknown fields) and takes Android's numbers for the rest. Days not supplied are untouched.
     */
    fun mergeClosed(ledger: DayLedger, answers: List<LedgerDay>): DayLedger {
        var next = ledger
        for (answer in answers.sortedBy { it.date }) {
            val existing = next.day(answer.date)
            if (existing?.closed == true) continue
            val merged = if (existing == null) answer else answer.copy(
                steps = existing.steps,
                pickups = existing.pickups,
                phoneFreeMillis = existing.phoneFreeMillis,
                firstPickupMinute = existing.firstPickupMinute,
                rhythmBins = existing.rhythmBins,
                extra = existing.extra + answer.extra,
            )
            next = closeDay(next.withDay(merged.copy(closed = false)), merged)
        }
        return next
    }

    /** Drops the per-app maps of days older than [DayLedger.AppDaysRetained]; totals stay. */
    fun prune(ledger: DayLedger, today: LocalDate): DayLedger {
        val cutoff = today.minusDays(DayLedger.AppDaysRetained.toLong())
        var changed = false
        val days = ledger.days.mapValues { (_, day) ->
            if (day.date.isBefore(cutoff) && day.closed && (day.appData.isNotEmpty() || day.appScreen.isNotEmpty())) {
                changed = true
                day.copy(appData = emptyMap(), appScreen = emptyMap())
            } else day
        }
        return if (changed) ledger.copy(days = days) else ledger
    }

    /**
     * M13 review: removes every per-app entry of the [excluded] packages (day maps and month sums). Totals (Wi-Fi, mobile,
     * screen-on) are untouched, so the day still counts them. Returns the same instance when nothing was there.
     */
    fun withoutPackages(ledger: DayLedger, excluded: Set<String>): DayLedger {
        if (excluded.isEmpty()) return ledger
        var changed = false
        fun strip(map: Map<String, Long>): Map<String, Long> =
            if (map.keys.none { it in excluded }) map else { changed = true; map.filterKeys { it !in excluded } }
        val days = ledger.days.mapValues { (_, day) ->
            val data = strip(day.appData); val screen = strip(day.appScreen)
            if (data === day.appData && screen === day.appScreen) day else day.copy(appData = data, appScreen = screen)
        }
        val months = ledger.months.mapValues { (_, month) ->
            val data = strip(month.data); val screen = strip(month.screen)
            if (data === month.data && screen === month.screen) month else MonthApps(data, screen)
        }
        return if (changed) ledger.copy(days = days, months = months) else ledger
    }

    /** The largest [limit] entries (ties by key, so the result is stable). */
    fun capMap(map: Map<String, Long>, limit: Int): Map<String, Long> =
        if (map.size <= limit) map
        else map.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit).associate { it.key to it.value }

    private fun sumMaps(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        if (b.isEmpty()) return a
        val out = LinkedHashMap(a)
        for ((k, v) in b) if (v > 0L) out[k] = (out[k] ?: 0L) + v
        return out
    }
}

/** One app row of a period's top list. */
data class LedgerApp(val appId: AppId, val label: String, val packageName: String, val value: Long)

/**
 * Joins per-package totals to launchable apps for display. Apps Parc cannot launch (system
 * processes, removed apps), Parc itself and apps the caller hides (locked folders) have no row;
 * zero totals are omitted; ties sort by label then package, so the order is stable.
 */
object LedgerTopApps {
    fun of(
        totals: Map<String, Long>,
        installed: List<AppEntry>,
        ownPackage: String,
        limit: Int = 5,
    ): List<LedgerApp> {
        val known = AppPackages.byPackage(installed, ownPackage)
        return totals.entries.mapNotNull { (pkg, value) ->
            val app = known[pkg] ?: return@mapNotNull null
            if (value <= 0L) null else LedgerApp(app.id, app.label, pkg, value)
        }.sortedWith(
            compareByDescending<LedgerApp> { it.value }.thenBy { it.label.lowercase() }.thenBy { it.packageName },
        ).take(limit.coerceAtLeast(0))
    }
}

/**
 * The rules that decide which past days Android can really answer for (T185). Kept apart from
 * [LedgerMath] because the screen events are an internal type of this module.
 */
internal object LedgerBackfill {

    /** One day of raw network answers before the backfill rule is applied. */
    data class RawNetworkDay(
        val date: LocalDate,
        val wifiBytes: Long?,
        val mobileBytes: Long?,
        val perPackage: Map<String, Long>,
    ) {
        val total: Long get() = (wifiBytes ?: 0L) + (mobileBytes ?: 0L)
    }

    /**
     * `NetworkStatsManager` answers zero, not an error, for days it has already forgotten, so a zero
     * day cannot be told from a forgotten one. Days before the first day with traffic are therefore
     * treated as unanswered and dropped; zero days after it are real. A day where both transports
     * were refused is dropped as well. Never invents a day.
     */
    fun trimNetworkBackfill(days: List<RawNetworkDay>): List<RawNetworkDay> {
        val answered = days.filter { it.wifiBytes != null || it.mobileBytes != null }.sortedBy { it.date }
        val firstReal = answered.indexOfFirst { it.total > 0L }
        return if (firstReal < 0) emptyList() else answered.drop(firstReal)
    }

    /**
     * Screen-on time per day from screen events. A day can only be answered when the platform still
     * holds events from before the day began (the oldest event is older than the day's midnight),
     * otherwise the state at midnight is unknown. Unanswerable days are absent from the result.
     */
    fun screenOnPerDay(events: List<ScreenEvent>, days: List<LocalDate>, zone: ZoneId): Map<LocalDate, Long> {
        if (events.isEmpty()) return emptyMap()
        val oldest = events.minOf { it.atMillis }
        val out = HashMap<LocalDate, Long>()
        for (date in days) {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (start <= oldest) continue
            out[date] = ScreenOnTimeMath.todayMillis(events, start, end)
        }
        return out
    }
}
