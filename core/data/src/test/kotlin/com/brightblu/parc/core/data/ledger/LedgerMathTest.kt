package com.brightblu.parc.core.data.ledger

import com.brightblu.parc.core.data.apps.testEntry
import com.brightblu.parc.core.data.power.ScreenEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T185 pure maths: windows, period sums and labels, closing, merging, roll-over, backfill rules. */
class LedgerMathTest {

    private val today = LocalDate.of(2026, 11, 10)

    private fun day(date: LocalDate, wifi: Long? = null, mobile: Long? = null, screen: Long? = null, closed: Boolean = false,
        data: Map<String, Long> = emptyMap(), apps: Map<String, Long> = emptyMap()) =
        LedgerDay(date, wifi, mobile, screen, closed = closed, appData = data, appScreen = apps)

    private fun ledgerOf(vararg days: LedgerDay) = days.fold(DayLedger()) { l, d -> l.withDay(d) }

    @Test
    fun `periods cycle forward and wrap and unknown ids read as the default`() {
        assertEquals(LedgerPeriod.Week, LedgerPeriod.Today.next())
        assertEquals(LedgerPeriod.Today, LedgerPeriod.All.next())
        assertEquals(LedgerPeriod.Today, LedgerPeriod.fromId("nonsense"))
        assertEquals(LedgerPeriod.Today, LedgerPeriod.fromId(null))
        assertEquals(LedgerPeriod.Year, LedgerPeriod.fromId("year"))
    }

    @Test
    fun `windows end today and have the stated length`() {
        assertEquals(today to today, LedgerMath.window(LedgerPeriod.Today, today))
        assertEquals(today.minusDays(6) to today, LedgerMath.window(LedgerPeriod.Week, today))
        assertEquals(today.minusDays(29) to today, LedgerMath.window(LedgerPeriod.Month, today))
        assertEquals(LocalDate.of(2026, 1, 1) to today, LedgerMath.window(LedgerPeriod.Year, today))
    }

    @Test
    fun `sums add recorded days only and count the days with data`() {
        val ledger = ledgerOf(
            day(today.minusDays(7), wifi = 999), // outside the 7-day window
            day(today.minusDays(6), wifi = 100, mobile = 10, screen = 1_000),
            day(today.minusDays(2), wifi = 50, mobile = 0, screen = 2_000),
            day(today, wifi = 5, screen = 500),
        )

        val s = LedgerMath.summary(ledger, LedgerPeriod.Week, today)

        assertEquals(155L, s.wifiBytes)
        assertEquals(10L, s.mobileBytes)
        assertEquals(3_500L, s.screenOnMillis)
        assertEquals(3, s.networkDays)
        assertEquals(7, s.days)
        assertEquals("7 days · 3 of 7 days", s.networkLabel())
    }

    @Test
    fun `labels are honest for today all time and this year`() {
        val first = LocalDate.of(2026, 8, 12)
        val ledger = ledgerOf(day(first, wifi = 1, screen = 1), day(today, wifi = 2, screen = 2))

        assertEquals("Today", LedgerMath.summary(ledger, LedgerPeriod.Today, today).networkLabel())
        val all = LedgerMath.summary(ledger, LedgerPeriod.All, today)
        assertEquals("All time · since 12 Aug 2026 · 2 of 91 days", all.networkLabel())
        val year = LedgerMath.summary(ledger, LedgerPeriod.Year, today)
        assertEquals("This year · since 12 Aug · 2 of 91 days", year.screenLabel())
        assertEquals(first, year.from)
    }

    @Test
    fun `a full year of data has no since part and an empty ledger shows only the name`() {
        val first = LocalDate.of(2025, 12, 20)
        val full = (0..314).fold(DayLedger()) { l, n -> l.withDay(day(LocalDate.of(2026, 1, 1).plusDays(n.toLong()), wifi = 1)) }
            .withDay(day(first, wifi = 1))
        val year = LedgerMath.summary(full, LedgerPeriod.Year, today)
        assertEquals("This year", year.networkLabel())

        assertEquals("All time", LedgerMath.summary(DayLedger.Empty, LedgerPeriod.All, today).networkLabel())
        assertEquals("7 days", LedgerMath.summary(DayLedger.Empty, LedgerPeriod.Week, today).networkLabel())
        assertEquals(0L, LedgerMath.summary(DayLedger.Empty, LedgerPeriod.Year, today).wifiBytes)
    }

    @Test
    fun `closing a day adds it to its month once and a repeat changes nothing`() {
        val d = day(LocalDate.of(2026, 11, 9), wifi = 1, data = mapOf("a" to 10L), apps = mapOf("a" to 100L))

        val once = LedgerMath.closeDay(DayLedger(), d)
        val twice = LedgerMath.closeDay(once, d)

        assertTrue(once.day(d.date)!!.closed)
        assertEquals(10L, once.months.getValue("2026-11").data["a"])
        assertSame(once, twice)
    }

    @Test
    fun `month roll over puts the last day and the first day in different months`() {
        val oct31 = day(LocalDate.of(2026, 10, 31), data = mapOf("a" to 1L))
        val nov1 = day(LocalDate.of(2026, 11, 1), data = mapOf("a" to 2L))

        val ledger = LedgerMath.closeDay(LedgerMath.closeDay(DayLedger(), oct31), nov1)

        assertEquals(1L, ledger.months.getValue("2026-10").data["a"])
        assertEquals(2L, ledger.months.getValue("2026-11").data["a"])
        // 31 Dec to 1 Jan crosses a year.
        val dec = LedgerMath.closeDay(DayLedger(), day(LocalDate.of(2026, 12, 31), data = mapOf("a" to 5L)))
        assertEquals(setOf("2026-12"), dec.months.keys)
    }

    @Test
    fun `app totals use day maps for short periods and month sums plus open days for long ones`() {
        var ledger = DayLedger()
        ledger = LedgerMath.closeDay(ledger, day(LocalDate.of(2026, 3, 5), data = mapOf("a" to 100L, "b" to 1L)))
        ledger = LedgerMath.closeDay(ledger, day(today.minusDays(3), data = mapOf("a" to 10L), apps = mapOf("a" to 60L)))
        ledger = ledger.withDay(day(today, data = mapOf("a" to 1L, "c" to 4L), apps = mapOf("a" to 6L)))
        ledger = LedgerMath.prune(ledger, today)

        assertEquals(mapOf("a" to 1L, "c" to 4L), LedgerMath.appTotals(ledger, LedgerPeriod.Today, today, LedgerAppKind.Data))
        assertEquals(mapOf("a" to 11L, "c" to 4L), LedgerMath.appTotals(ledger, LedgerPeriod.Week, today, LedgerAppKind.Data))
        assertEquals(mapOf("a" to 66L), LedgerMath.appTotals(ledger, LedgerPeriod.Week, today, LedgerAppKind.Screen))
        // The March day is older than 35 days, so only the month sum answers for it.
        assertEquals(mapOf("a" to 111L, "b" to 1L, "c" to 4L), LedgerMath.appTotals(ledger, LedgerPeriod.Year, today, LedgerAppKind.Data))
        assertEquals(mapOf("a" to 111L, "b" to 1L, "c" to 4L), LedgerMath.appTotals(ledger, LedgerPeriod.All, today, LedgerAppKind.Data))
        // A previous year only counts for All time.
        val old = LedgerMath.closeDay(ledger, day(LocalDate.of(2025, 6, 1), data = mapOf("z" to 9L)))
        assertTrue("z" !in LedgerMath.appTotals(old, LedgerPeriod.Year, today, LedgerAppKind.Data))
        assertEquals(9L, LedgerMath.appTotals(old, LedgerPeriod.All, today, LedgerAppKind.Data)["z"])
    }

    @Test
    fun `merging a backfill closes new days keeps local fields and never rewrites a closed day`() {
        val open = LedgerDay(today.minusDays(1), wifiBytes = 1, steps = 900, pickups = 7)
        val closed = LedgerDay(today.minusDays(2), wifiBytes = 5, closed = true)
        val base = DayLedger().withDay(open).withDay(closed)

        val merged = LedgerMath.mergeClosed(
            base,
            listOf(
                LedgerDay(open.date, wifiBytes = 50, appData = mapOf("a" to 2L)),
                LedgerDay(closed.date, wifiBytes = 999),
                LedgerDay(today.minusDays(5), wifiBytes = 3),
            ),
        )

        val o = merged.day(open.date)!!
        assertEquals(50L, o.wifiBytes)
        assertEquals(900, o.steps)
        assertEquals(7, o.pickups)
        assertTrue(o.closed)
        assertEquals(5L, merged.day(closed.date)!!.wifiBytes)
        assertEquals(3L, merged.day(today.minusDays(5))!!.wifiBytes)
        assertEquals(today.minusDays(5), merged.firstDate)
        assertEquals(2L, merged.months.getValue("2026-11").data["a"])
    }

    @Test
    fun `prune drops per app maps of old closed days and keeps their totals`() {
        val old = day(today.minusDays(40), wifi = 7, closed = true, data = mapOf("a" to 1L))
        val recent = day(today.minusDays(3), wifi = 1, closed = true, data = mapOf("a" to 1L))

        val pruned = LedgerMath.prune(ledgerOf(old, recent), today)

        assertTrue(pruned.day(old.date)!!.appData.isEmpty())
        assertEquals(7L, pruned.day(old.date)!!.wifiBytes)
        assertEquals(mapOf("a" to 1L), pruned.day(recent.date)!!.appData)
    }

    @Test
    fun `network backfill drops the zeros before the first traffic and keeps the zeros after it`() {
        fun raw(offset: Long, bytes: Long?) =
            LedgerBackfill.RawNetworkDay(today.minusDays(offset), bytes, bytes?.let { 0L }, emptyMap())

        val trimmed = LedgerBackfill.trimNetworkBackfill(
            listOf(raw(5, 0), raw(4, 0), raw(3, 20), raw(2, 0), raw(1, null)),
        )

        assertEquals(listOf(today.minusDays(3), today.minusDays(2)), trimmed.map { it.date })
        assertTrue(LedgerBackfill.trimNetworkBackfill(listOf(raw(2, 0), raw(1, 0))).isEmpty())
    }

    @Test
    fun `screen backfill answers only days that start after the oldest event`() {
        val zone = ZoneOffset.UTC
        fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val d8 = today.minusDays(2)
        val d9 = today.minusDays(1)
        val events = listOf(
            ScreenEvent(true, at(d8, 22)),
            ScreenEvent(false, at(d9, 1)),
            ScreenEvent(true, at(d9, 10)),
            ScreenEvent(false, at(d9, 12)),
        )

        val perDay = LedgerBackfill.screenOnPerDay(events, listOf(today.minusDays(3), d8, d9), zone)

        assertNull(perDay[today.minusDays(3)])
        assertNull(perDay[d8]) // starts before the oldest event: the state at midnight is unknown
        assertEquals(3L * 3_600_000L, perDay[d9])
        assertTrue(LedgerBackfill.screenOnPerDay(emptyList(), listOf(d9), zone).isEmpty())
    }

    @Test
    fun `top apps join to launchable apps and drop own app unknown packages and zeros`() {
        val a = testEntry("alpha", label = "Alpha")
        val b = testEntry("beta", label = "Beta")
        val own = testEntry("parc", packageName = "com.brightblu.parc")

        val top = LedgerTopApps.of(
            totals = mapOf(a.packageName to 5L, b.packageName to 5L, own.packageName to 99L, "com.hidden" to 50L, "com.zero" to 0L),
            installed = listOf(b, a, own),
            ownPackage = "com.brightblu.parc",
            limit = 5,
        )

        assertEquals(listOf("Alpha", "Beta"), top.map { it.label })
    }

    @Test
    fun `day windows are 23 or 25 hours across a clock change`() {
        val ny = ZoneId.of("America/New_York")
        val fallBack = LocalDate.of(2026, 11, 1)
        val start = fallBack.atStartOfDay(ny).toInstant().toEpochMilli()
        val end = fallBack.plusDays(1).atStartOfDay(ny).toInstant().toEpochMilli()
        assertEquals(25L * 3_600_000L, end - start)
    }
}

/** M13 review: per-app entries of locked-folder apps are removed, totals are kept. */
class LedgerWithoutPackagesTest {
    @org.junit.Test fun removesNamesFromDaysAndMonthsAndKeepsTotals() {
        val day = LedgerDay(java.time.LocalDate.of(2026, 11, 9), wifiBytes = 10, mobileBytes = 2, screenOnMillis = 5,
            appData = mapOf("a" to 1L, "x" to 2L), appScreen = mapOf("x" to 3L))
        val ledger = DayLedger(days = mapOf(day.date to day), months = mapOf("2026-11" to MonthApps(mapOf("a" to 1L, "x" to 2L), mapOf("x" to 3L))))
        val out = LedgerMath.withoutPackages(ledger, setOf("x"))
        org.junit.Assert.assertEquals(mapOf("a" to 1L), out.day(day.date)!!.appData)
        org.junit.Assert.assertTrue(out.day(day.date)!!.appScreen.isEmpty())
        org.junit.Assert.assertEquals(10L, out.day(day.date)!!.wifiBytes)
        org.junit.Assert.assertEquals(MonthApps(mapOf("a" to 1L), emptyMap()), out.months.getValue("2026-11"))
        org.junit.Assert.assertSame(ledger, LedgerMath.withoutPackages(ledger, setOf("none")))
        org.junit.Assert.assertSame(ledger, LedgerMath.withoutPackages(ledger, emptySet()))
    }
}
