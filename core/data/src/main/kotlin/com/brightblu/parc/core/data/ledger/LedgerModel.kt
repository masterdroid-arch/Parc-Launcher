package com.brightblu.parc.core.data.ledger

import java.time.LocalDate
import kotlinx.serialization.json.JsonElement

/**
 * One calendar day (local date) of Parc's own record (T185). Every measurement is nullable: null
 * means "Android could not answer and nobody recorded it", which is different from a real zero.
 *
 * [steps], [pickups] and [phoneFreeMillis] are reserved for T187 (Steps) and T188 (Rhythm); T185
 * never writes them and never overwrites them. [extra] keeps fields this build does not know, so a
 * newer build's document survives a round trip through an older one.
 *
 * [appData] (bytes, both transports) and [appScreen] (foreground milliseconds) are per-package maps
 * for the last [DayLedger.AppDaysRetained] days only; older days keep their totals and give their
 * per-app numbers to the month sums in [DayLedger.months].
 *
 * [closed] is true once the day is over and was filled from Android's whole-day numbers. A closed
 * day is final: the live refresh never rewrites it.
 */
data class LedgerDay(
    val date: LocalDate,
    val wifiBytes: Long? = null,
    val mobileBytes: Long? = null,
    val screenOnMillis: Long? = null,
    val steps: Int? = null,
    val pickups: Int? = null,
    val phoneFreeMillis: Long? = null,
    /** T188: the minute of the local day (0..1439) of the first pickup, or null when none was recorded. */
    val firstPickupMinute: Int? = null,
    /** T188: the day's screen-on segments at 5-minute resolution, 72 hex characters (288 bits, midnight first). Null when not recorded. */
    val rhythmBins: String? = null,
    val closed: Boolean = false,
    val appData: Map<String, Long> = emptyMap(),
    val appScreen: Map<String, Long> = emptyMap(),
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val hasNetwork: Boolean get() = wifiBytes != null || mobileBytes != null
    val hasScreen: Boolean get() = screenOnMillis != null

    /** True when any measurement (including the later cards' fields) is present. */
    val hasData: Boolean
        get() = hasNetwork || hasScreen || steps != null || pickups != null || phoneFreeMillis != null || rhythmBins != null
}

/** Running per-app sums of the closed days of one calendar month. Keys are package names. */
data class MonthApps(
    val data: Map<String, Long> = emptyMap(),
    val screen: Map<String, Long> = emptyMap(),
)

/**
 * The whole ledger: immutable, small, and safe to publish. [months] is keyed `yyyy-MM`.
 * [firstDate] is the oldest date that has data (null while the ledger is empty), kept even if its
 * day record is later pruned. [backfilled] is true once the first-run backfill has been attempted
 * with usage access granted. [extra] keeps unknown top-level fields.
 */
data class DayLedger(
    val days: Map<LocalDate, LedgerDay> = emptyMap(),
    val months: Map<String, MonthApps> = emptyMap(),
    val firstDate: LocalDate? = null,
    val backfilled: Boolean = false,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    fun day(date: LocalDate): LedgerDay? = days[date]

    /** The recorded days in [from]..[to] inclusive, oldest first. Days nobody recorded are absent. */
    fun range(from: LocalDate, to: LocalDate): List<LedgerDay> =
        if (to.isBefore(from)) emptyList()
        else days.values.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.date }

    /** A copy with [day] stored (replacing the same date) and [firstDate] kept up to date. */
    fun withDay(day: LedgerDay): DayLedger {
        val first = if (!day.hasData) firstDate else firstDate?.let { minOf(it, day.date) } ?: day.date
        return copy(days = days + (day.date to day), firstDate = first)
    }

    companion object {
        /** Per-app numbers are kept per day for this many days, then only in the month sums. */
        const val AppDaysRetained = 35

        /** Most apps kept per day and per month; the long tail is dropped, never summed in. */
        const val MaxAppsPerDay = 40
        const val MaxAppsPerMonth = 120

        val Empty = DayLedger()
    }
}
