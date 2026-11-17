package com.brightblu.parc

import com.brightblu.parc.core.data.ledger.LedgerMath
import com.brightblu.parc.core.data.ledger.LedgerPeriod
import com.brightblu.parc.core.data.ledger.PeriodSummary
import java.time.LocalDate

/** One made-up row on a demo top-apps list. */
internal data class DemoUsageApp(val label: String, val value: Long)

/** A made-up period for the Network and Screen time demo pages. */
internal data class DemoUsagePage(val summary: PeriodSummary, val apps: List<DemoUsageApp>)

internal data class DemoNewApp(val label: String, val daysAgo: Int)

/**
 * T268: fixed figures for the demo Network and Screen time pages. The values depend only on the
 * period and the date passed in, so no real usage, app name or device reading is ever touched.
 * Today, 7 days and 30 days grow with their window; This year and All time start at a made-up
 * first day 180 days ago, like a phone that began recording that day.
 */
internal object DemoUsage {
    private const val FirstDayDaysAgo = 180L
    private const val WifiBytesPerDay = 420_000_000L
    private const val MobileBytesPerDay = 180_000_000L
    private const val ScreenMillisPerDay = 8_280_000L

    private val networkAppsPerDay = listOf(
        "Maps" to 62_000_000L,
        "Music" to 38_000_000L,
        "Reader" to 12_000_000L,
    )
    private val screenAppsPerDay = listOf(
        "Reader" to 3_200_000L,
        "Notes" to 2_100_000L,
        "Music" to 1_400_000L,
    )

    /** Fictional app names for the demo New apps tile. */
    val newApps = listOf(
        DemoNewApp("Panel", 1),
        DemoNewApp("Field Guide", 4),
        DemoNewApp("Studio", 6),
    )

    /** Fictional app sizes for the demo Biggest apps tile, in bytes. */
    val biggestApps = listOf(
        "Field Guide" to 2_400_000_000L,
        "Studio" to 1_100_000_000L,
        "Panel" to 640_000_000L,
        "Reader" to 220_000_000L,
    )

    fun network(period: LedgerPeriod, today: LocalDate): DemoUsagePage =
        page(period, today, WifiBytesPerDay, MobileBytesPerDay, ScreenMillisPerDay, networkAppsPerDay)

    fun screen(period: LedgerPeriod, today: LocalDate): DemoUsagePage =
        page(period, today, WifiBytesPerDay, MobileBytesPerDay, ScreenMillisPerDay, screenAppsPerDay)

    private fun page(
        period: LedgerPeriod,
        today: LocalDate,
        wifiPerDay: Long,
        mobilePerDay: Long,
        screenPerDay: Long,
        appsPerDay: List<Pair<String, Long>>,
    ): DemoUsagePage {
        val first = today.minusDays(FirstDayDaysAgo)
        val (windowFrom, windowTo) = LedgerMath.window(period, today)
        var from = windowFrom
        var sinceFirst = false
        if (period == LedgerPeriod.Year || period == LedgerPeriod.All) {
            if (first.isAfter(from)) {
                from = first
                sinceFirst = true
            }
            if (period == LedgerPeriod.All) sinceFirst = true
        }
        if (windowTo.isBefore(from)) from = windowTo
        val days = (windowTo.toEpochDay() - from.toEpochDay() + 1).toInt().coerceAtLeast(1)
        val summary = PeriodSummary(
            period = period,
            from = from,
            to = windowTo,
            days = days,
            wifiBytes = wifiPerDay * days,
            mobileBytes = mobilePerDay * days,
            screenOnMillis = screenPerDay * days,
            networkDays = days,
            screenDays = days,
            firstDate = first,
            sinceFirst = sinceFirst,
        )
        return DemoUsagePage(summary, appsPerDay.map { (label, value) -> DemoUsageApp(label, value * days) })
    }
}
